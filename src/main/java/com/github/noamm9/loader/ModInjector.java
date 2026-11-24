package com.github.noamm9.loader;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.SystemProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

final class ModInjector {
    private final FabricLoaderImpl loader;

    ModInjector(FabricLoaderImpl loader) {
        this.loader = loader;
    }

    void inject(Path jar) throws Exception {
        var candidates = pick(discover(jar));
        if (candidates.stream().noneMatch(it -> it.getId().equals(NoammLoader.MOD_ID))) {
            throw new NoammLoader.NoammLoaderException(jar + " doesn't contain " + NoammLoader.MOD_ID + ", delete it to download it again");
        }

        var processedMods = loader.getGameDir().resolve(".fabric").resolve("processedMods");
        var containers = new ArrayList<ModContainerImpl>();

        for (ModCandidateImpl candidate : candidates) {
            if (! candidate.hasPath()) {
                try {
                    candidate.setPaths(List.of(candidate.copyToDir(processedMods, false)));
                }
                catch (IOException e) {
                    throw new NoammLoader.NoammLoaderException("Failed to extract " + candidate.getId() + " to " + processedMods, e);
                }
            }

            var container = new ModContainerImpl(candidate);
            for (Path path : container.getCodeSourcePaths()) {
                FabricLauncherBase.getLauncher().addToClassPath(path);
            }

            containers.add(container);
        }

        register(containers);

        for (ModContainerImpl container : containers) {
            var metadata = container.getMetadata();
            NoammLoader.LOGGER.info("Loaded %s %s".formatted(metadata.getId(), metadata.getVersion().getFriendlyString()));
        }
    }

    void removeInstalled(Path activeJar) {
        for (var candidate : discoverInstalled()) {
            var jar = candidate.getOriginPaths().getFirst();
            try {
                if (! Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSameFile(jar, activeJar)) continue;
                if (Files.deleteIfExists(jar)) NoammLoader.LOGGER.info("Removed standalone NoammAddons JAR " + jar);
            }
            catch (IOException e) {
                NoammLoader.LOGGER.warn("Could not remove standalone NoammAddons JAR " + jar, e);
            }
        }
    }

    private List<ModCandidateImpl> discoverInstalled() {
        var mods = loader.getModsDirectory().toPath();
        try {
            var discoverer = newDiscoverer();
            discoverer.addCandidateFinder(new DirectoryModCandidateFinder(mods, false));
            var disabled = new HashMap<String, Set<ModCandidateImpl>>();
            var candidates = discoverer.discoverMods(loader, disabled);
            var otherEnvironments = disabled.getOrDefault(NoammLoader.MOD_ID, Set.of());
            return Stream.concat(candidates.stream(), otherEnvironments.stream())
                .filter(candidate -> candidate.isRoot() && candidate.getId().equals(NoammLoader.MOD_ID))
                .filter(candidate -> Files.isRegularFile(candidate.getOriginPaths().getFirst(), LinkOption.NOFOLLOW_LINKS))
                .toList();
        } catch (ModResolutionException | RuntimeException e) {
            NoammLoader.LOGGER.warn("Could not inspect the mods folder " + mods, e);
            return List.of();
        }
    }

    private ModDiscoverer newDiscoverer() {
        return new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(loader.getConfigDir()));
    }

    private List<ModCandidateImpl> discover(Path jar) throws NoammLoader.NoammLoaderException {
        var previous = System.getProperty(SystemProperties.ADD_MODS);
        System.setProperty(SystemProperties.ADD_MODS, jar.toAbsolutePath().toString());

        try {
            var discoverer = newDiscoverer();
            discoverer.addCandidateFinder(new ArgumentModCandidateFinder(false));
            return discoverer.discoverMods(loader, new HashMap<>());
        }
        catch (ModResolutionException e) {
            throw new NoammLoader.NoammLoaderException("Couldn't read " + jar + ", delete it to download it again", e);
        }
        finally {
            if (previous == null) System.clearProperty(SystemProperties.ADD_MODS);
            else System.setProperty(SystemProperties.ADD_MODS, previous);
        }
    }

    private List<ModCandidateImpl> pick(List<ModCandidateImpl> discovered) {
        var picked = new LinkedHashMap<String, ModCandidateImpl>();

        for (ModCandidateImpl candidate : discovered) {
            if (candidate.isBuiltin()) continue;

            var loadedId = loader.getModContainer(candidate.getId()).map(mod -> mod.getMetadata().getId());
            var replacesAlias = candidate.getId().equals(NoammLoader.MOD_ID) && loadedId.filter(NoammLoader.LOADER_ID::equals).isPresent();
            if (loadedId.isPresent() && ! replacesAlias) continue;

            picked.merge(candidate.getId(), candidate, (a, b) -> a.getVersion().compareTo(b.getVersion()) >= 0 ? a : b);
        }

        return new ArrayList<>(picked.values());
    }

    @SuppressWarnings("unchecked")
    private void register(List<ModContainerImpl> containers) throws ReflectiveOperationException {
        var modMapField = FabricLoaderImpl.class.getDeclaredField("modMap");
        var modsField = FabricLoaderImpl.class.getDeclaredField("mods");
        modMapField.setAccessible(true);
        modsField.setAccessible(true);

        var modMap = (Map<String, ModContainerImpl>) modMapField.get(loader);
        for (ModContainerImpl container : containers) {
            modMap.put(container.getMetadata().getId(), container);
            for (String provides : container.getMetadata().getProvides()) {
                modMap.put(provides, container);
            }
        }

        var mods = new ArrayList<>(loader.getModsInternal());
        mods.addAll(containers);
        modsField.set(loader, mods);
    }
}