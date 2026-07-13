package dev.dagsu.profiler;

import javax.annotation.Nonnull;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface Profiler {
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    @interface Thread {
        String value() default "";
        String var() default "";
    }

    @Target(ElementType.LOCAL_VARIABLE)
    @Retention(RetentionPolicy.CLASS)
    @interface Frame {
        String value() default "";
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    @interface Scope {
        String value() default "";
    }

    static void start(@Nonnull final String appInfo) {
        com.mojang.jtracy.ProfilerHooks.startup(appInfo);
    }
}
