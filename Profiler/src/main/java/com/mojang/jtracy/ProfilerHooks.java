package com.mojang.jtracy;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicBoolean;

public interface ProfilerHooks {
    static void setThread(@Nonnull final String name) {
        TracyBindings.setThreadName(name, 0);
    }

    static long createFrame(@Nonnull final String name) {
        return TracyBindings.leakName(name);
    }

    static boolean markFrame(final long id) {
        TracyBindings.markFrame(id);
        return true;
    }

    static int beginZone(@Nonnull final String name) {
        return TracyBindings.beginZone(name, "", "", 0);
    }

    static void endZone(final int id) {
        TracyBindings.endZone(id);
    }

    static boolean startup(@Nonnull String appInfo) {
        if (!Init.STARTED.compareAndSet(false, true)) return false;

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

    class Init {
        static final AtomicBoolean STARTED = new AtomicBoolean(false);
    }
}
