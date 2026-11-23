package com.github.noamm9.loader;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

@SuppressWarnings("unused")
final class LoaderLogger {
    private final String prefix;

    LoaderLogger(String name) {
        this.prefix = "[" + name + "] ";
    }

    void info(String message) {
        Log.info(LogCategory.GENERAL, prefix + message);
    }

    void warn(String message) {
        Log.warn(LogCategory.GENERAL, prefix + message);
    }

    void warn(String message, Throwable exc) {
        Log.warn(LogCategory.GENERAL, prefix + message, exc);
    }

    void error(String message) {
        Log.error(LogCategory.GENERAL, prefix + message);
    }

    void error(String message, Throwable exc) {
        Log.error(LogCategory.GENERAL, prefix + message, exc);
    }
}
