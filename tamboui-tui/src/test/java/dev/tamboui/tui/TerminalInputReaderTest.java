/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.error.RuntimeIOException;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TerminalInputReaderTest {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(10);


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


    @Test
    @DisplayName("an unchecked exception from a read does not stop the reader")
    void keepsReadingAfterOccasionalUncheckedErrors() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        for (int i = 0; i < 15; i++) {
            backend.script.add(new RuntimeIOException("Failed to get terminal size (errno=0)"));
            backend.script.add(ScriptedBackend.NO_INPUT);
        }
        backend.script.add((int) 'a');
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        TerminalInputReader reader = new TerminalInputReader(backend, events, BindingSets.defaults(), running,
            POLL_TIMEOUT);

        reader.start();
        try {
            Event event = events.poll(2, TimeUnit.SECONDS);

            assertThat(event).as("key typed after the errors").isInstanceOf(KeyEvent.class);
            assertThat(((KeyEvent) event).isChar('a')).isTrue();
            assertThat(reader.isAlive()).isTrue();
            assertThat(running.get()).isTrue();
        } finally {
            running.set(false);
            reader.stop(1000);
        }
    }

    @Test
    @DisplayName("when every read fails, the reader ends the runner instead of leaving it without input")
    void endsTheRunnerWhenReadingKeepsFailing() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.failEveryRead = true;
        AtomicBoolean running = new AtomicBoolean(true);
        TerminalInputReader reader = new TerminalInputReader(backend, new LinkedBlockingQueue<Event>(),
            BindingSets.defaults(), running, POLL_TIMEOUT);

        reader.start();
        reader.stop(2000);

        assertThat(reader.isAlive()).isFalse();
        assertThat(running.get()).as("runner still running").isFalse();
    }

    /**
     * A backend whose reads follow a script: a code point to return, {@link #NO_INPUT}
     * for a read that times out, or an exception to throw.
     */
    private static final class ScriptedBackend extends TestBackend {
        static final Integer NO_INPUT = -2;

        final BlockingQueue<Object> script = new LinkedBlockingQueue<>();
        volatile boolean failEveryRead;

        ScriptedBackend() {
            super(80, 24);
        }

        @Override
        public int read(int timeoutMs) throws IOException {
            if (failEveryRead) {
                throw new RuntimeIOException("poll() failed (errno=9)");
            }
            Object next;
            try {
                next = script.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return NO_INPUT;
            }
            if (next == null) {
                return NO_INPUT;
            }
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            return (Integer) next;
        }
    }
}
