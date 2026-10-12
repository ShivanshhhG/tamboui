/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.error.RuntimeIOException;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.TickEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RunnerBackendErrorTest {

    private static final String SCHEDULER_THREAD = "runner-backend-error-test-scheduler";

    @Test
    @DisplayName("TuiRunner keeps ticking after the scheduler fails to read the terminal size")
    void tuiRunnerKeepsTickingAfterSchedulerSizeError() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, SCHEDULER_THREAD));
        SizeFailingBackend backend = new SizeFailingBackend();
        TuiRunner runner = TuiRunner.create(testConfig(backend)
            .tickRate(Duration.ofMillis(10))
            .scheduler(scheduler)
            .build());
        Semaphore ticks = new Semaphore(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = start(() -> runner.run((event, r) -> {
            if (event instanceof TickEvent) {
                ticks.release();
            }
            return false;
        }, frame -> {
        }), failure);
        try {
            assertThat(ticks.tryAcquire(2, TimeUnit.SECONDS)).isTrue();

            // The resize handler reads the new size, then the scheduler fails to read it
            backend.failNextSizeOnScheduler.set(true);
            backend.resize(100, 30);
            assertThat(backend.schedulerFailed.await(2, TimeUnit.SECONDS)).isTrue();
            ticks.drainPermits();

            assertThat(ticks.tryAcquire(5, 2, TimeUnit.SECONDS))
                .as("5 more ticks after the failed size read")
                .isTrue();
        } finally {
            runner.close();
            thread.join(1000);
            scheduler.shutdownNow();
        }
        assertThat(failure.get()).isNull();
    }

    @Test
    @DisplayName("TuiRunner's resize handler does not throw when the size cannot be read")
    void tuiRunnerResizeHandlerSurvivesSizeError() throws Exception {
        SizeFailingBackend backend = new SizeFailingBackend();
        TuiRunner runner = TuiRunner.create(testConfig(backend)
            .noTick()
            .resizeGracePeriod(Duration.ofMillis(10))
            .build());
        CountDownLatch firstRender = new CountDownLatch(1);
        CountDownLatch resizedRender = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = start(() -> runner.run((event, r) -> false, frame -> {
            firstRender.countDown();
            if (frame.area().width() == 100) {
                resizedRender.countDown();
            }
        }), failure);
        try {
            assertThat(firstRender.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatCode(() -> backend.resize(0, 0)).doesNotThrowAnyException();

            backend.resize(100, 30);
            assertThat(resizedRender.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.close();
            thread.join(1000);
        }
        assertThat(failure.get()).isNull();
    }

    @Test
    @DisplayName("InlineTuiRunner's resize handler does not throw when the size cannot be read")
    void inlineTuiRunnerResizeHandlerSurvivesSizeError() throws Exception {
        SizeFailingBackend backend = new SizeFailingBackend();
        InlineTuiRunner runner = InlineTuiRunner.create(backend, InlineTuiConfig.builder(4)
            .noTick()
            .pollTimeout(Duration.ofMillis(20))
            .build());
        CountDownLatch firstRender = new CountDownLatch(1);
        CountDownLatch resizedRender = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = start(() -> runner.run((event, r) -> false, frame -> {
            firstRender.countDown();
            if (frame.area().width() == 50) {
                resizedRender.countDown();
            }
        }), failure);
        try {
            assertThat(firstRender.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatCode(() -> backend.resize(0, 0)).doesNotThrowAnyException();

            backend.resize(50, 24);
            assertThat(resizedRender.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.close();
            thread.join(1000);
        }
        assertThat(failure.get()).isNull();
    }

    private static TuiConfig.Builder testConfig(TestBackend backend) {
        return TuiConfig.builder()
            .backend(backend)
            .rawMode(false)
            .alternateScreen(false)
            .hideCursor(false)
            .shutdownHook(false)
            .pollTimeout(Duration.ofMillis(10));
    }

    private static Thread start(RunnerLoop loop, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                loop.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "runner-backend-error-test");
        thread.start();
        return thread;
    }

    /**
     * The event loop of a runner, run on a background thread.
     */
    private interface RunnerLoop {
        void run() throws Exception;
    }

    /**
     * Fails to read the size the way the Panama backend does: with an unchecked
     * {@link RuntimeIOException} when the terminal reports 0x0, and here also once
     * on the scheduler thread when asked to.
     */
    private static final class SizeFailingBackend extends TestBackend {
        final AtomicBoolean failNextSizeOnScheduler = new AtomicBoolean();
        final CountDownLatch schedulerFailed = new CountDownLatch(1);

        SizeFailingBackend() {
            super(80, 24);
        }

        @Override
        public Size size() throws IOException {
            Size size = super.size();
            if (size.width() == 0 || size.height() == 0) {
                throw new RuntimeIOException("Failed to get terminal size (errno=0)");
            }
            if (Thread.currentThread().getName().equals(SCHEDULER_THREAD)
                && failNextSizeOnScheduler.compareAndSet(true, false)) {
                schedulerFailed.countDown();
                throw new RuntimeIOException("Failed to get terminal size (errno=25)");
            }
            return size;
        }
    }
}
