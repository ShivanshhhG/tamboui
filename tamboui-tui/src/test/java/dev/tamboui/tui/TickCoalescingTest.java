/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.TickEvent;

import static org.assertj.core.api.Assertions.assertThat;

class TickCoalescingTest {

    private static final Duration TICK_RATE = Duration.ofMillis(1);
    private static final int PERIODS_WHILE_BUSY = 10;

    @Test
    @DisplayName("TuiRunner queues one tick, not a backlog, while the handler is busy")
    void tuiRunnerQueuesOneTickWhileHandlerIsBusy() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        TuiConfig config = TuiConfig.builder()
            .backend(new TestBackend(80, 24))
            .rawMode(false)
            .alternateScreen(false)
            .hideCursor(false)
            .shutdownHook(false)
            .pollTimeout(Duration.ofMillis(10))
            .tickRate(TICK_RATE)
            .scheduler(scheduler)
            .build();
        TuiRunner runner = TuiRunner.create(config);
        BusyHandler handler = new BusyHandler();
        Thread thread = handler.start(() -> runner.run((event, r) -> handler.handle(event, r::pollEvent), frame -> {
        }));
        try {
            handler.assertQueuesOneTickWhileBusy(scheduler);
        } finally {
            handler.release.countDown();
            runner.close();
            thread.join(1000);
            scheduler.shutdownNow();
        }
        assertThat(handler.failure.get()).isNull();
    }

    @Test
    @DisplayName("InlineTuiRunner queues one tick, not a backlog, while the handler is busy")
    void inlineTuiRunnerQueuesOneTickWhileHandlerIsBusy() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        InlineTuiConfig config = InlineTuiConfig.builder(2)
            .pollTimeout(Duration.ofMillis(10))
            .tickRate(TICK_RATE)
            .scheduler(scheduler)
            .build();
        InlineTuiRunner runner = InlineTuiRunner.create(new TestBackend(80, 24), config);
        BusyHandler handler = new BusyHandler();
        Thread thread = handler.start(() -> runner.run((event, r) -> handler.handle(event, r::pollEvent), frame -> {
        }));
        try {
            handler.assertQueuesOneTickWhileBusy(scheduler);
        } finally {
            handler.release.countDown();
            runner.close();
            thread.join(1000);
            scheduler.shutdownNow();
        }
        assertThat(handler.failure.get()).isNull();
    }

    /**
     * Stays busy with the first tick until released, then takes everything that was queued
     * meanwhile. Later ticks are collected.
     */
    private static final class BusyHandler {
        private final CountDownLatch busy = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch drained = new CountDownLatch(1);
        private final AtomicInteger ticksQueuedWhileBusy = new AtomicInteger();
        private final BlockingQueue<TickEvent> laterTicks = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread start(RunnerLoop loop) {
            Thread thread = new Thread(() -> {
                try {
                    loop.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "tick-coalescing-test");
            thread.start();
            return thread;
        }

        boolean handle(Event event, Function<Duration, Event> poll) {
            if (!(event instanceof TickEvent)) {
                return false;
            }
            if (busy.getCount() == 0) {
                laterTicks.add((TickEvent) event);
                return false;
            }
            busy.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            Event queued;
            while ((queued = poll.apply(Duration.ZERO)) != null) {
                if (queued instanceof TickEvent) {
                    ticksQueuedWhileBusy.incrementAndGet();
                }
            }
            drained.countDown();
            return false;
        }

        void assertQueuesOneTickWhileBusy(ManualScheduler scheduler) throws InterruptedException {
            // Let the clock pass enough tick periods that every period run below is due
            Thread.sleep(50);

            scheduler.runPeriod();
            assertThat(busy.await(2, TimeUnit.SECONDS)).isTrue();

            // The handler stays busy for many tick periods
            for (int i = 0; i < PERIODS_WHILE_BUSY; i++) {
                scheduler.runPeriod();
            }
            release.countDown();

            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ticksQueuedWhileBusy.get())
                .as("ticks queued while the handler was busy")
                .isEqualTo(1);

            // Once the queued tick is handled, the next period queues a tick again
            scheduler.runPeriod();
            assertThat(laterTicks.poll(2, TimeUnit.SECONDS))
                .as("tick of the next period")
                .isNotNull();
        }
    }

    /**
     * The event loop of a runner, run on a background thread.
     */
    private interface RunnerLoop {
        void run() throws Exception;
    }

    /**
     * A scheduler that keeps the runner's periodic task instead of running it, so the test
     * decides when each tick period passes.
     */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private volatile Runnable periodicTask;

        ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            periodicTask = command;
            return schedule(() -> {
            }, 1, TimeUnit.DAYS);
        }

        void runPeriod() {
            periodicTask.run();
        }
    }
}
