package com.github.noamm9.loader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.*;

final class BuildCache {
    private static final String ACTION_URL = "https://api.noamm.org/na/data/action";
    private static final String ARTIFACT_URL = ACTION_URL + "/artifact/";
    private static final int DOWNLOAD_TIMEOUT = 60;

    private final Path dir;
    private final String minecraft;
    private final Path jar;
    private final Path info;

    BuildCache(Path dir, String minecraft) {
        this.dir = dir;
        this.minecraft = minecraft;
        this.jar = dir.resolve("NoammAddons-" + minecraft + "-cheat.jar");
        this.info = dir.resolve("loader.properties");
    }

    private static HttpRequest request(String url, int timeoutSeconds) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", NoammLoader.NAME)
            .build();
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    Path resolve() {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        try {
            update(client);
        }
        catch (Exception e) {
            NoammLoader.LOGGER.warn("Update check failed, using the cached build", e);
        }
        finally {
            client.close();
        }

        return Files.isRegularFile(jar) ? jar : null;
    }

    private void update(HttpClient client) throws IOException, InterruptedException, NoSuchAlgorithmException {
        var response = client.send(request(ACTION_URL, 10), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + ACTION_URL);

        var suffix = "-" + minecraft + "-cheat.jar";
        var build = ActionBuild.parseLatest(response.body(), suffix);
        if (build == null) throw new IOException("The latest Action build has no *" + suffix + " artifact");

        if (isCached(build)) {
            NoammLoader.LOGGER.info("%s is up to date (%s)".formatted(jar.getFileName(), build.shortSha()));
            return;
        }

        NoammLoader.LOGGER.info("Downloading %s (%s)".formatted(build.artifactName(), build.shortSha()));
        var startTime = System.currentTimeMillis();
        download(client, build);
        writeInfo(build);
        NoammLoader.LOGGER.info("Finished Downloading in %sms".formatted(System.currentTimeMillis() - startTime));
    }

    private boolean isCached(ActionBuild build) {
        var props = readInfo();
        if (props == null || ! Files.isRegularFile(jar)) return false;

        return build.sha().equals(props.get("sha")) && minecraft.equals(props.get("mc"));
    }

    private Properties readInfo() {
        if (! Files.isRegularFile(info)) return null;

        var props = new Properties();
        try (var reader = Files.newBufferedReader(info)) {
            props.load(reader);
            return props;
        }
        catch (IOException | RuntimeException e) {
            NoammLoader.LOGGER.warn("Could not read " + info, e);
            return null;
        }
    }

    private void download(HttpClient client, ActionBuild build) throws IOException, InterruptedException, NoSuchAlgorithmException {
        Files.createDirectories(dir);
        var tmp = jar.resolveSibling(jar.getFileName() + ".tmp");

        try {
            var future = client.sendAsync(request(ARTIFACT_URL + build.artifactId(), DOWNLOAD_TIMEOUT), HttpResponse.BodyHandlers.ofFile(tmp));
            HttpResponse<Path> response;

            try {
                response = future.get(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS);
            }
            catch (ExecutionException | TimeoutException e) {
                future.cancel(true);
                throw new IOException("Failed to download " + build.artifactName(), e);
            }

            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " downloading " + build.artifactName());
            var expected = build.digest().replaceFirst("^sha256:", "");
            var actual = sha256(tmp);
            if (! actual.equalsIgnoreCase(expected)) throw new IOException("Checksum mismatch for " + build.artifactName() + ": expected " + expected + ", got " + actual);

            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void writeInfo(ActionBuild build) throws IOException {
        var props = new Properties();
        props.setProperty("sha", build.sha());
        props.setProperty("mc", minecraft);

        var tmp = info.resolveSibling(info.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(tmp)) {
            props.store(writer, NoammLoader.NAME);
        }

        Files.move(tmp, info, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}