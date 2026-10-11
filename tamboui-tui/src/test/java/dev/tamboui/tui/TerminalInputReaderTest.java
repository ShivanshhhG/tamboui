/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.event.Event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TerminalInputReaderTest {

    @Test
    void eofStopsTheReaderAndSharedRunner() {
        AtomicInteger reads = new AtomicInteger();
        TestBackend backend = new TestBackend(80, 24) {
            @Override
            public int read(int timeoutMs) {
                reads.incrementAndGet();
                return -1;
            }
        };
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        TerminalInputReader reader = new TerminalInputReader(
            backend, events, BindingSets.defaults(), running, Duration.ofMillis(10));

        assertTimeoutPreemptively(Duration.ofSeconds(2), reader::run);

        assertThat(running).isFalse();
        assertThat(reads).hasValue(1);
        assertThat(events).isEmpty();
    }

    @Test
    void stoppingReaderInterruptsAStalledPaste() throws InterruptedException {
        CountDownLatch stalled = new CountDownLatch(1);
        TestBackend backend = new TestBackend(80, 24) {
            private final int[] input = {27, '[', '2', '0', '0', '~', 'x'};
            private int index;

            @Override
            public int read(int timeoutMs) {
                if (index < input.length) {
                    return input[index++];
                }
                stalled.countDown();
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -2;
            }

            @Override
            public int peek(int timeoutMs) {
                return index < input.length ? input[index] : -2;
            }
        };
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        TerminalInputReader reader = new TerminalInputReader(
            backend, events, BindingSets.defaults(), running, Duration.ofMillis(50));

        reader.start();
        assertThat(stalled.await(2, TimeUnit.SECONDS)).isTrue();
        running.set(false);
        reader.stop(1000);

        assertThat(reader.isAlive()).isFalse();
        assertThat(events).isEmpty();
    }
}
