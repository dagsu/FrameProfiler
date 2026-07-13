package com.mojang.jtracy;

import javax.annotation.Nonnull;

public interface ProfilerHooks {
    boolean ENABLED = Boolean.getBoolean("tracy");

    static void setThread(@Nonnull final String name) {
        if (!ENABLED) return;
        TracyBindings.setThreadName(name, 0);
    }

    static long createFrame(@Nonnull final String name) {
        return name(name);
    }

    static boolean markFrame(final long id) {
        if (!ENABLED) return false;
        TracyBindings.markFrame(id);
        return true;
    }

    static int beginZone(@Nonnull final String name) {
        if (!ENABLED) return 0;
        return TracyBindings.beginZone(name, "", "", 0);
    }

    static void endZone(final int id) {
        if (!ENABLED) return;
        TracyBindings.endZone(id);
    }

    static long name(@Nonnull final String name) {
        if (!ENABLED) return 0L;
        return TracyBindings.leakName(name);
    }

    static boolean startup(@Nonnull String appInfo) {
        if (!ENABLED) return false;

        try {
            TracyClient.load();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }

        if (TracyClient.isAvailable()) {
            TracyClient.reportAppInfo(appInfo);
            return true;
        }
        return false;
    }
}
