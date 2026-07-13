package dev.dagsu;

import dev.dagsu.profiler.Profiler;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class Example {
    @Nonnull
    protected final String threadName;
    protected final AtomicBoolean running = new AtomicBoolean(true);

    public Example(@Nonnull final String threadName) {
        this.threadName = threadName;
    }

    @Profiler.Thread(var = "threadName")
    public void run() {
        @Profiler.Frame("Frame")
        final boolean frameMark = true;

        int sum = 0;
        while (running.get() && frameMark) {
            tick();
        }

        System.out.println(sum);
    }

    @Profiler.Scope
    protected static void tick() {
        begin();
        network();
        physics();
        update();
        frame();
        end();
    }

    @Profiler.Scope
    protected static void network() {
        nanoWait(100, 2_000);
    }

    @Profiler.Scope
    protected static void physics() {
        nanoWait(1_000, 3_000);
    }

    @Profiler.Scope
    protected static void begin() {
        nanoWait(200, 1_000);
    }

    @Profiler.Scope
    protected static void update() {
        tickChunks();
        tickEntities();
    }

    @Profiler.Scope
    protected static void frame() {
        gatherChunks();
        gatherEntities();
        sortScene();
        renderScene();
    }

    @Profiler.Scope
    protected static void end() {
        nanoWait(10, 800);
    }

    @Profiler.Scope
    protected static void tickChunks() {
        nanoWait(5_000, 20_000);
    }

    @Profiler.Scope
    protected static void tickEntities() {
        nanoWait(10_000, 25_000);
    }

    @Profiler.Scope
    protected static void gatherChunks() {
        nanoWait(500, 1_000);
    }

    @Profiler.Scope
    protected static void gatherEntities() {
        nanoWait(700, 2_000);
    }

    @Profiler.Scope
    protected static void sortScene() {
        nanoWait(1_000, 2_000);
    }

    @Profiler.Scope
    protected static void renderScene() {
        nanoWait(1_000, 3_000);
    }

    static void nanoWait(final int min, final int max) {
        final var rand = ThreadLocalRandom.current();

        final int nanos = rand.nextInt(500, 5_000);
        final long end = System.nanoTime() + nanos;
        do {
            Thread.onSpinWait();
        } while (System.nanoTime() < end);
    }

    static void main(String[] args) throws InterruptedException {
        Profiler.start("Example");

        final var example = new Example("MainThread");
        final var thread = new Thread(example::run);
        thread.start();

        Thread.sleep(Duration.ofSeconds(20));

        example.running.set(false);
        thread.join();
    }
}
