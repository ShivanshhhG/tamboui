/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.backend.aesh;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.error.ErrorAction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AeshBackendCloseTest {

    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdown() {
        later.shutdownNow();
    }

    @Test
    @DisplayName("read returns end of input when the connection closes while it waits")
    void readEndsWhenConnectionCloses() throws Exception {
        FakeConnection connection = new FakeConnection();
        AeshBackend backend = new AeshBackend(connection);
        later.schedule(() -> connection.close(), 20, TimeUnit.MILLISECONDS);

        assertThat(backend.read(2000)).isEqualTo(-1);
        assertThat(backend.read(0)).isEqualTo(-1);
        assertThat(backend.peek(0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("input that arrived before the connection closed is read before the end of input")
    void pendingInputIsReadFirst() throws Exception {
        FakeConnection connection = new FakeConnection();
        AeshBackend backend = new AeshBackend(connection);
        connection.type("q");
        connection.close();

        assertThat(backend.read(0)).isEqualTo('q');
        assertThat(backend.read(0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("output after the connection closes is dropped instead of written to the connection")
    void outputAfterCloseIsDropped() throws Exception {
        FakeConnection connection = new FakeConnection();
        AeshBackend backend = new AeshBackend(connection);
        backend.enterAlternateScreen();
        connection.close();
        backend.writeRaw("frame");

        assertThatCode(backend::flush).doesNotThrowAnyException();
        assertThatCode(backend::close).doesNotThrowAnyException();
        assertThat(connection.output()).doesNotContain("frame");
    }

    @Test
    @DisplayName("a close handler set on the connection before the backend still runs")
    void earlierCloseHandlerStillRuns() throws Exception {
        FakeConnection connection = new FakeConnection();
        AtomicBoolean appHandlerRan = new AtomicBoolean();
        connection.setCloseHandler(ignored -> appHandlerRan.set(true));
        AeshBackend backend = new AeshBackend(connection);

        connection.close();

        assertThat(appHandlerRan).isTrue();
        assertThat(backend.read(0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("a TuiRunner on the backend ends when its connection closes")
    void runnerEndsWhenConnectionCloses() throws Exception {
        FakeConnection connection = new FakeConnection();
        AeshBackend backend = new AeshBackend(connection);
        List<Throwable> renderErrors = new CopyOnWriteArrayList<>();
        TuiConfig config = TuiConfig.builder()
                .backend(backend)
                .shutdownHook(false)
                .errorHandler((error, context) -> {
                    renderErrors.add(error.cause());
                    return ErrorAction.QUIT_IMMEDIATELY;
                })
                .build();
        AtomicReference<TuiRunner> runnerRef = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch drawn = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);

        // Redraws on every event, ticks included, like an app showing live data
        Thread app = new Thread(() -> {
            try (TuiRunner runner = TuiRunner.create(config)) {
                runnerRef.set(runner);
                runner.run((event, r) -> true, frame -> drawn.countDown());
            } catch (Exception e) {
                failure.set(e);
            } finally {
                ended.countDown();
            }
        });
        app.setDaemon(true);
        app.start();
        try {
            assertThat(drawn.await(5, TimeUnit.SECONDS)).isTrue();

            connection.close();

            assertThat(ended.await(5, TimeUnit.SECONDS)).as("the runner ends").isTrue();
            assertThat(failure.get()).isNull();
            assertThat(renderErrors).isEmpty();
        } finally {
            TuiRunner runner = runnerRef.get();
            if (runner != null) {
                runner.quit();
            }
        }
    }
}
