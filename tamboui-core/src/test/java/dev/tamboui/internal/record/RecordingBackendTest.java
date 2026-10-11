/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.record;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.TestBackend;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingBackendTest {

    private static final Rect AREA = new Rect(0, 0, 10, 1);
    // Big enough that applying a draw takes a while, so that a frame taken in the middle of it would be caught
    private static final Rect SCREEN = new Rect(0, 0, 120, 40);
    private static final int DRAWS = 10000;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        RecordingConfig.clearActive();
    }

    @Test
    @DisplayName("A screen drawn within the frame interval of the previous frame ends the cast")
    void lastScreenDrawnWithinFrameIntervalIsRecorded() throws IOException {
        Path cast = tempDir.resolve("out.cast");
        // At 1 fps the second draw lands in the same frame interval as the first one
        RecordingBackend backend = new RecordingBackend(new TestBackend(10, 1), RecordingConfig.of(cast, 1, 60000, 10, 1));
        Buffer empty = Buffer.empty(AREA);
        Buffer saving = screen("Saving...");
        Buffer saved = screen("Saved");

        draw(backend, empty, saving);
        draw(backend, saving, saved);
        backend.close();

        List<String> outputs = frameOutputs(cast);
        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0)).contains("Saving...");
        assertThat(outputs.get(1)).contains("Saved").doesNotContain("Saving");
    }

    @Test
    @DisplayName("No frame is added when every draw was captured")
    void noExtraFrameWhenEveryDrawWasCaptured() throws IOException {
        Path cast = tempDir.resolve("out.cast");
        RecordingBackend backend = new RecordingBackend(new TestBackend(10, 1), RecordingConfig.of(cast, 1, 60000, 10, 1));

        draw(backend, Buffer.empty(AREA), screen("Saved"));
        backend.close();

        List<String> outputs = frameOutputs(cast);
        assertThat(outputs).hasSize(1);
        assertThat(outputs.get(0)).contains("Saved");
    }

    @Test
    @DisplayName("A frame captured by read() while a tape plays is a screen as drawn, not half of the next one")
    void framesCapturedWhileTapePlaysAreWholeScreens() throws Exception {
        Path cast = tempDir.resolve("out.cast");
        // At 1000 fps a frame is captured every millisecond, by whichever of draw() and read() comes first
        RecordingBackend backend = new RecordingBackend(new TestBackend(SCREEN.width(), SCREEN.height()),
                new RecordingConfig(cast, 1000, 60000, SCREEN.width(), SCREEN.height(), tape("Sleep 60s\n")));

        drawWhilePlaying(backend, drawing -> {
            // Each read() plays the Sleep and captures a frame, as on the input thread
            while (drawing.get()) {
                backend.read(0);
            }
        });
        backend.close();

        List<String> frames = frameOutputs(cast);
        assertThat(frames).isNotEmpty();
        assertThat(mixedScreens(frames)).as("frames that mix two screens, of %d", frames.size()).isEmpty();
    }

    @Test
    @DisplayName("A tape screenshot taken while the app draws is a screen as drawn, not half of the next one")
    void screenshotsTakenWhileAppDrawsAreWholeScreens() throws Exception {
        int count = 20;
        StringBuilder tape = new StringBuilder();
        for (int i = 0; i < count; i++) {
            tape.append("Screenshot shot-").append(i).append(".txt\n");
        }
        // The tape goes on after the screenshots, so read() does not end the recording
        tape.append("Sleep 60s\n");
        RecordingBackend backend = new RecordingBackend(new TestBackend(SCREEN.width(), SCREEN.height()),
                new RecordingConfig(tempDir.resolve("out.cast"), 10, 60000, SCREEN.width(), SCREEN.height(),
                        tape(tape.toString())));

        drawWhilePlaying(backend, drawing -> {
            // Each read() plays the next Screenshot
            for (int i = 0; i < count; i++) {
                backend.read(0);
            }
        });
        backend.close();

        List<String> shots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shots.add(new String(Files.readAllBytes(tempDir.resolve("shot-" + i + ".txt")), StandardCharsets.UTF_8));
        }
        assertThat(mixedScreens(shots)).as("screenshots that mix two screens, of %d", count).isEmpty();
    }

    @Test
    @DisplayName("The cast written at the end of the tape is whole when the app quitting interrupts the thread writing it")
    void castWrittenAtTheEndOfTheTapeIsWholeWhenItsThreadIsInterrupted() throws IOException {
        Path cast = tempDir.resolve("out.cast");
        AtomicInteger exits = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(new TestBackend(10, 1),
                new RecordingConfig(cast, 1, 60000, 10, 1, tape("Type \"q\"\n")), exits::incrementAndGet, () -> { });
        draw(backend, Buffer.empty(AREA), screen("Saving..."));
        draw(backend, screen("Saving..."), screen("Saved"));
        assertThat(backend.read(0)).isEqualTo('q');

        // The tape has ended, so this read() writes the cast, on the input thread. Meanwhile the app quits on the q,
        // and TuiRunner.close() interrupts the input thread. On Java 8 that interrupt makes Files.write fail
        Thread.currentThread().interrupt();
        try {
            backend.read(0);
        } finally {
            Thread.interrupted();
        }

        assertThat(exits).hasValue(1);
        List<String> frames = frameOutputs(cast);
        assertThat(frames).as("frames in the cast").hasSize(2);
        assertThat(frames.get(1)).contains("Saved").doesNotContain("Saving");
    }

    @Test
    @DisplayName("Closing while the end of the tape writes the cast returns once the cast is written")
    void closeWaitsForTheCastWrittenAtTheEndOfTheTape() throws Exception {
        Path cast = tempDir.resolve("out.cast");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingBackend backend = new RecordingBackend(new TestBackend(10, 1),
                new RecordingConfig(cast, 1, 60000, 10, 1, tape("Type \"q\"\n")), () -> { }, () -> {
                    // Holds the input thread in the middle of writing the cast
                    writing.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        draw(backend, Buffer.empty(AREA), screen("Saving..."));
        draw(backend, screen("Saving..."), screen("Saved"));
        assertThat(backend.read(0)).isEqualTo('q');

        ExecutorService inputThread = Executors.newSingleThreadExecutor();
        try {
            // The tape has ended, so the input thread writes the cast...
            Future<Integer> tapeEnd = inputThread.submit(() -> backend.read(0));
            assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
            // ...while the app quits on the q and closes the backend. Once close() returns, main() returns and the JVM
            // exits, without waiting for the input thread, a daemon thread
            FutureTask<List<String>> closing = new FutureTask<>(() -> {
                backend.close();
                return Files.exists(cast) ? frameOutputs(cast) : Collections.<String>emptyList();
            });
            Thread appThread = new Thread(closing);
            appThread.start();
            awaitWaitingOrEnded(appThread);
            release.countDown();

            assertThat(closing.get(10, TimeUnit.SECONDS)).as("frames in the cast when close() returned").hasSize(2);
            tapeEnd.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            inputThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("A cast that cannot be written at the end of the tape is reported, not ignored")
    void castThatCannotBeWrittenAtTheEndOfTheTapeIsReported() throws IOException {
        // A directory is in the way of the cast
        Path cast = Files.createDirectory(tempDir.resolve("out.cast"));
        AtomicInteger exits = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(new TestBackend(10, 1),
                new RecordingConfig(cast, 1, 60000, 10, 1, tape("Type \"q\"\n")), exits::incrementAndGet, () -> { });
        draw(backend, Buffer.empty(AREA), screen("Saved"));
        assertThat(backend.read(0)).isEqualTo('q');

        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, "UTF-8"));
        try {
            // The tape has ended, so this read() writes the cast
            backend.read(0);
        } finally {
            System.setErr(originalErr);
        }

        assertThat(exits).hasValue(1);
        assertThat(err.toString("UTF-8")).contains("Failed to write recording: " + cast);
    }

    /** Waits until the thread waits for something, or has ended. */
    private static void awaitWaitingOrEnded(Thread thread) throws InterruptedException {
        Set<Thread.State> waitingOrEnded = EnumSet.of(Thread.State.WAITING, Thread.State.TIMED_WAITING,
                Thread.State.TERMINATED);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!waitingOrEnded.contains(thread.getState())) {
            assertThat(deadline - System.nanoTime())
                    .as("time left for %s to wait or end, in state %s", thread.getName(), thread.getState())
                    .isPositive();
            thread.join(1);
        }
    }

    /** Plays the tape with read(), as the input thread does, until it is done or {@code drawing} turns false. */
    private interface Playback {
        void play(AtomicBoolean drawing) throws Exception;
    }

    /**
     * Runs the playback on another thread while this one draws a screen of X and a screen of O in turn, each draw
     * changing every cell: at least {@value #DRAWS} draws, and on until the playback is done.
     */
    private static void drawWhilePlaying(RecordingBackend backend, Playback playback) throws Exception {
        Buffer xs = Buffer.filled(SCREEN, new Cell("X", Style.EMPTY));
        Buffer os = Buffer.filled(SCREEN, new Cell("O", Style.EMPTY));
        draw(backend, Buffer.empty(SCREEN), xs);

        AtomicBoolean drawing = new AtomicBoolean(true);
        CountDownLatch playing = new CountDownLatch(1);
        ExecutorService inputThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> reading = inputThread.submit(() -> {
                playing.countDown();
                playback.play(drawing);
                return null;
            });
            assertThat(playing.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < DRAWS || !reading.isDone(); i++) {
                if (i == DRAWS) {
                    drawing.set(false);
                }
                // As the render thread does, work out the changes, then draw them
                draw(backend, i % 2 == 0 ? xs : os, i % 2 == 0 ? os : xs);
            }
            drawing.set(false);
            reading.get(10, TimeUnit.SECONDS);
        } finally {
            inputThread.shutdownNow();
        }
    }

    /** The screens, of X and O, that show part of each. */
    private static List<String> mixedScreens(List<String> screens) {
        List<String> mixed = new ArrayList<>();
        for (int i = 0; i < screens.size(); i++) {
            String screen = screens.get(i);
            int xs = 0;
            int os = 0;
            for (int c = 0; c < screen.length(); c++) {
                if (screen.charAt(c) == 'X') {
                    xs++;
                } else if (screen.charAt(c) == 'O') {
                    os++;
                }
            }
            if (xs > 0 && os > 0) {
                mixed.add("#" + i + ": " + xs + " X and " + os + " O");
            }
        }
        return mixed;
    }

    private Path tape(String content) throws IOException {
        Path tape = tempDir.resolve("test.tape");
        Files.write(tape, content.getBytes(StandardCharsets.UTF_8));
        return tape;
    }

    private static Buffer screen(String text) {
        Buffer buffer = Buffer.empty(AREA);
        buffer.setString(0, 0, text, Style.EMPTY);
        return buffer;
    }

    private static void draw(RecordingBackend backend, Buffer previous, Buffer next) throws IOException {
        DiffResult diff = new DiffResult();
        previous.diff(next, diff);
        backend.draw(diff);
        diff.clear();
    }

    /** The output of each frame event in the cast, leaving out the empty event that pads the duration. */
    private static List<String> frameOutputs(Path cast) throws IOException {
        List<String> outputs = new ArrayList<>();
        List<String> lines = Files.readAllLines(cast, StandardCharsets.UTF_8);
        // The first line is the header
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String output = line.substring(line.indexOf("\"o\", \"") + 6, line.lastIndexOf('"'));
            if (!output.isEmpty()) {
                outputs.add(output);
            }
        }
        return outputs;
    }
}
