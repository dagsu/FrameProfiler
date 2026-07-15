package dev.dagsu.profiler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface Profiler {
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    @interface Setup {
        String value() default "";
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    @interface Thread {
        String value() default "";
        String var() default "";
    }

    @Target(ElementType.LOCAL_VARIABLE)
    @Retention(RetentionPolicy.SOURCE)
    @interface Frame {
        String value() default "";
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    @interface Scope {
        String value() default "";
    }
}
