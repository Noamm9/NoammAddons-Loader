package com.github.noamm9.loader;

import net.fabricmc.loader.api.*;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

// registered as a language adapter because thats the earliest point fabric runs mod code
public final class NoammLoader implements LanguageAdapter {
    static final String NAME = "NoammAddons-Loader";
    static final String MOD_ID = "noammaddons";

    public NoammLoader() {
        try {
            load();
        }
        catch (NoammLoaderException e) {
            crash(e.getMessage(), e.getCause() != null ? e.getCause() : e);
        }
        catch (Throwable t) {
            crash("Failed to load NoammAddons into Fabric Loader " + FabricLoaderImpl.VERSION, t);
        }
    }

    private static void crash(String message, Throwable t) {
        Log.error(LogCategory.GENERAL, "[" + NAME + "] " + message, t);

        var provider = FabricLoaderImpl.INSTANCE.tryGetGameProvider();
        if (provider == null || ! provider.displayCrash(t, message)) FabricGuiEntry.displayError(message, t, true);
        System.exit(1);
    }

    private static void load() throws Exception {
        var loader = FabricLoaderImpl.INSTANCE;
        if (loader.isDevelopmentEnvironment()) return;

        if (loader.isModLoaded(MOD_ID)) {
            Log.info(LogCategory.GENERAL, "[%s] %s is already installed in mods, skipping", NAME, MOD_ID);
            return;
        }

        var minecraft = loader.getModContainer("minecraft")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElseThrow(() -> new NoammLoaderException("Minecraft isn't loaded"));

        var jar = new BuildCache(loader.getConfigDir().resolve("NoammAddons").resolve("loader"), minecraft).resolve();
        if (jar == null) {
            Log.warn(LogCategory.GENERAL, "[%s] No NoammAddons build available for %s, starting without it", NAME, minecraft);
            return;
        }

        new ModInjector(loader).inject(jar);
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException(NAME + " is not a real language adapter");
    }

    public static class NoammLoaderException extends Exception {
        public NoammLoaderException(String s) {
            super(s);
        }

        public NoammLoaderException(String s, Throwable t) {
            super(s, t);
        }
    }
}