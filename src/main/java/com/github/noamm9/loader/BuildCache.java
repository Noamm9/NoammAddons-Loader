package com.github.noamm9.loader;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.*;

final class BuildCache {
    private static final String ACTION_URL = "https://api.noamm.org/na/data/action";
    private static final String ARTIFACT_URL = ACTION_URL + "/artifact/";
    private static final String USER_AGENT = NoammLoader.NAME;
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
            .header("User-Agent", USER_AGENT)
            .build();
    }

    private static String shortSha(ActionBuild build) {
        return build.sha().substring(0, Math.min(7, build.sha().length()));
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
            Log.warn(LogCategory.GENERAL, "[" + NoammLoader.NAME + "] Update check failed, using the cached build", e);
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
            Log.info(LogCategory.GENERAL, "[%s] %s is up to date (%s)", NoammLoader.NAME, jar.getFileName(), shortSha(build));
            return;
        }

        Log.info(LogCategory.GENERAL, "[%s] Downloading %s (%s)", NoammLoader.NAME, build.artifactName(), shortSha(build));
        download(client, build);
    }

    private boolean isCached(ActionBuild build) {
        if (! Files.isRegularFile(jar) || ! Files.isRegularFile(info)) return false;

        var props = new Properties();
        try (var reader = Files.newBufferedReader(info)) {
            props.load(reader);
        }
        catch (IOException | RuntimeException e) {
            Log.warn(LogCategory.GENERAL, "[" + NoammLoader.NAME + "] Couldn't read " + info, e);
            return false;
        }

        return build.sha().equals(props.get("sha")) && minecraft.equals(props.get("mc"));
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
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp)));
            if (! actual.equalsIgnoreCase(expected)) throw new IOException("Checksum mismatch for " + build.artifactName() + ": expected " + expected + ", got " + actual);

            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(tmp);
        }

        var props = new Properties();
        props.setProperty("sha", build.sha());
        props.setProperty("mc", minecraft);
        props.setProperty("digest", build.digest());
        try (var writer = Files.newBufferedWriter(info)) {
            props.store(writer, NoammLoader.NAME);
        }
    }
}