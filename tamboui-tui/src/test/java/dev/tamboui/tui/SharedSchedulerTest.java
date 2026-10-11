/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.terminal.TestBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runners given a scheduler in their config, which they share, for example one runner per
 * connection.
 */
class SharedSchedulerTest {

    // Long enough that the runner's periodic task stays queued, and does not run, during a test
    private static final Duration PERIOD = Duration.ofMinutes(1);

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("TuiRunner.close() cancels its periodic task on a shared scheduler")
    void tuiRunnerCancelsItsTaskOnClose() throws Exception {
        TuiRunner runner = TuiRunner.create(TuiConfig.builder()
            .backend(new TestBackend(80, 24))
            .rawMode(false)
            .alternateScreen(false)
            .hideCursor(false)
            .shutdownHook(false)
            .tickRate(PERIOD)
            .resizeGracePeriod(PERIOD)
            .scheduler(scheduler)
            .build());
        assertThat(scheduler.getQueue()).as("tasks of the open runner").hasSize(1);

        runner.close();
        // The scheduler drops a cancelled task when it comes due; purge() drops it now
        scheduler.purge();

        assertThat(scheduler.getQueue()).as("tasks left after close").isEmpty();
        assertThat(scheduler.isShutdown()).isFalse();
    }

    @Test
    @DisplayName("InlineTuiRunner.close() cancels its periodic task on a shared scheduler")
    void inlineTuiRunnerCancelsItsTaskOnClose() throws Exception {
        InlineTuiRunner runner = InlineTuiRunner.create(new TestBackend(80, 24), InlineTuiConfig.builder(4)
            .tickRate(PERIOD)
            .scheduler(scheduler)
            .build());
        assertThat(scheduler.getQueue()).as("tasks of the open runner").hasSize(1);

        runner.close();
        // The scheduler drops a cancelled task when it comes due; purge() drops it now
        scheduler.purge();

        assertThat(scheduler.getQueue()).as("tasks left after close").isEmpty();
        assertThat(scheduler.isShutdown()).isFalse();
    }
}
