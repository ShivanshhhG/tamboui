/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.record;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/**
 * A backend wrapper that records frames to an Asciinema cast file.
 * This backend is headless - it does not output to the real terminal.
 * This is an internal API and not part of the public contract.
 */
public final class RecordingBackend implements Backend {

    // How long close() waits for the cast that another thread writes: a big recording takes a while to build, while a
    // write that is stuck must not keep the app from exiting
    private static final long CAST_WAIT_SECONDS = 10;

    private final Backend delegate;
    private final RecordingConfig config;
    private final Size overrideSize;
    // The screen as drawn so far. draw() runs on the render thread, while read() captures frames and plays the tape
    // on the input thread, where Wait and Screenshot read the screen: the buffer, the frames and the capture state
    // below are only used under the buffer's lock
    private final Buffer buffer;
    private final List<TimedFrame> frames;
    private final InteractionPlayer interactionPlayer;
    private final long startTimeNanos;
    private long lastCaptureTimeNanos;
    // timestamp of the latest draw the fps throttle skipped, or -1 once a frame has been captured
    // after it; the cast ends with that screen if no later frame captures it
    private long pendingDrawMs = -1;
    // time spent in the Hide sections of the tape, left out of the frame timestamps
    private long hiddenNanos;
    private long hiddenSinceNanos;
    private volatile boolean recording;
    private volatile boolean closed;
    private volatile boolean hasDrawn;  // Track if draw() was ever called
    // Counted down by the thread that stopped the recording once it has written the cast, or found nothing to write
    private final CountDownLatch castWritten = new CountDownLatch(1);
    private final Runnable exit;
    private final Runnable beforeCastWrite;

    /**
     * Creates a new recording backend wrapping the given delegate.
     *
     * @param delegate the delegate backend
     * @param config   the recording configuration
     */
    public RecordingBackend(Backend delegate, RecordingConfig config) {
        this(delegate, config, () -> System.exit(0), () -> { });
    }

    /**
     * Creates a recording backend with hooks for tests. {@code exit} runs where {@link #read(int)} exits the JVM once
     * it has written the cast at the end of the tape or of the duration limit. {@code beforeCastWrite} runs on the
     * thread that writes the cast, after the cast is built and before the file is written, so a test can hold that
     * thread there.
     */
    RecordingBackend(Backend delegate, RecordingConfig config, Runnable exit, Runnable beforeCastWrite) {
        this.delegate = delegate;
        this.config = config;
        this.exit = exit;
        this.beforeCastWrite = beforeCastWrite;
        this.overrideSize = new Size(config.width(), config.height());
        this.buffer = Buffer.empty(new Rect(0, 0, config.width(), config.height()));
        this.frames = new ArrayList<>();
        this.interactionPlayer = new InteractionPlayer(
                InteractionPlayer.loadFromFile(config.configFile(), config.outputPath()), buffer);
        this.startTimeNanos = System.nanoTime();
        this.lastCaptureTimeNanos = 0;
        this.recording = true;
        this.closed = false;
        // Note: AnsiTerminalCapture is installed by RecordingConfig.load()
        // which is called before this constructor
    }

    @Override
    public Size size() throws IOException {
        return overrideSize;
    }

    @Override
    public void draw(DiffResult diff) throws IOException {
        synchronized (buffer) {
            // Apply updates to our buffer, iterating runs of adjacent changed cells
            for (int run = 0, runs = diff.runCount(); run < runs; run++) {
                int start = diff.runStart(run);
                int end = start + diff.runLength(run);
                int x = diff.xOf(start);
                int y = diff.yOf(start);
                for (int i = start; i < end; i++) {
                    buffer.set(x++, y, diff.cellAt(i));
                }
            }
            hasDrawn = true;

            // Capture frame if recording
            if (recording) {
                captureFrame(true);
            }
        }
        // Don't delegate - headless recording
    }

    // Called under the buffer's lock
    private void captureFrame(boolean drawn) {
        long nowNanos = System.nanoTime();
        // The frames of a Hide section are left out, and so is the time it took, so playback does not pause there
        if (interactionPlayer.isHidden()) {
            if (hiddenSinceNanos == 0) {
                hiddenSinceNanos = nowNanos;
            }
            return;
        }
        if (hiddenSinceNanos != 0) {
            hiddenNanos += nowNanos - hiddenSinceNanos;
            hiddenSinceNanos = 0;
        }
        long elapsedNanos = nowNanos - startTimeNanos - hiddenNanos;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        // Check duration limit
        if (elapsedMs > config.maxDurationMs()) {
            recording = false;
            return;
        }

        // Throttle based on FPS
        long frameIntervalNanos = TimeUnit.MILLISECONDS.toNanos(1000 / config.fps());
        if (nowNanos - lastCaptureTimeNanos >= frameIntervalNanos) {
            frames.add(new TimedFrame(buffer.copy(), elapsedMs));
            lastCaptureTimeNanos = nowNanos;
            pendingDrawMs = -1;
        } else if (drawn) {
            pendingDrawMs = elapsedMs;
        }
    }

    /**
     * Returns true if recording is still active.
     *
     * @return true if recording
     */
    public boolean isRecording() {
        if (!recording) {
            return false;
        }
        // Check duration limit
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos) > config.maxDurationMs()) {
            recording = false;
            return false;
        }
        return true;
    }

    @Override
    public void flush() throws IOException {
        // Pass through to System.out for AnsiTerminalCapture to capture frames
        System.out.flush();
    }

    @Override
    public void clear() throws IOException {
        synchronized (buffer) {
            buffer.clear();
        }
    }

    @Override
    public void showCursor() throws IOException {
        // Headless - no output
    }

    @Override
    public void hideCursor() throws IOException {
        // Headless - no output
    }

    @Override
    public Position getCursorPosition() throws IOException {
        return new Position(0, 0);
    }

    @Override
    public void setCursorPosition(Position position) throws IOException {
        // Headless - no output
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        // Headless - no output
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        // Headless - no output
    }

    @Override
    public void enableRawMode() throws IOException {
        // Headless - no output
    }

    @Override
    public void disableRawMode() throws IOException {
        // Headless - no output
    }

    @Override
    public void enableMouseCapture() throws IOException {
        // Headless - no output
    }

    @Override
    public void disableMouseCapture() throws IOException {
        // Headless - no output
    }

    @Override
    public void scrollUp(int lines) throws IOException {
        // Headless - no output
    }

    @Override
    public void scrollDown(int lines) throws IOException {
        // Headless - no output
    }

    @Override
    public void writeRaw(byte[] data) throws IOException {
        // Pass through to System.out for AnsiTerminalCapture to process
        // This allows InlineDisplay output to be captured
        System.out.write(data);
        System.out.flush();
    }

    @Override
    public void writeRaw(String data) throws IOException {
        // Pass through to System.out for AnsiTerminalCapture to process
        // This allows InlineDisplay output to be captured
        System.out.print(data);
        System.out.flush();
    }

    @Override
    public void onResize(Runnable handler) {
        // No resize events in recording mode
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        // If we have interactions, play them back
        if (!interactionPlayer.isFinished()) {
            // Not under the lock: a Sleep in the tape waits in here
            int b = interactionPlayer.nextCodePoint(Math.min(timeoutMs, 100));
            // Capture frame during read to ensure we get frames during Sleep commands
            // Only capture if draw() has been called (TUI demos), not for inline demos
            // which use System.out and AnsiTerminalCapture instead
            synchronized (buffer) {
                if (recording && hasDrawn) {
                    captureFrame(false);
                }
            }
            return b;
        }

        // Interactions finished - if we HAD interactions, exit now
        // (the recording file should end with a quit key)
        if (!interactionPlayer.hasNoInteractions() && stop()) {
            writeCastAndExit();
        }

        // No interactions defined - wait for duration limit
        if (!isRecording() && stop()) {
            writeCastAndExit();
        }

        // Wait a bit
        try {
            Thread.sleep(Math.min(timeoutMs, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return -2;
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        // Peek at pending bytes from interaction player
        return interactionPlayer.peekCodePoint();
    }

    @Override
    public void close() throws IOException {
        if (!stop()) {
            // The end of the tape or of the duration limit stopped the recording on the input thread, which writes the
            // cast while the app quits. Once close() returns, main() can return, and the JVM exits without waiting for
            // the input thread, a daemon thread
            awaitCast();
            return;
        }

        // Write cast file only if we have frames from Backend.draw() calls (TUI demos)
        // System.out capture is handled by the shutdown hook
        writeCast();

        // Close delegate
        delegate.close();
    }

    /**
     * Ends the recording. The render thread can close the backend while the input thread reaches the end of the
     * tape or the duration limit, so this checks and sets {@code closed} under the lock: only the caller that gets
     * {@code true} writes the cast. No frame is captured after this.
     */
    private boolean stop() {
        synchronized (buffer) {
            if (closed) {
                return false;
            }
            closed = true;
            recording = false;
            return true;
        }
    }

    /**
     * Writes the cast, then exits, when the end of the tape or of the duration limit stopped the recording in
     * {@link #read(int)}. The cast is written before the exit, whose shutdown hooks may close this backend and so wait
     * for it.
     */
    private void writeCastAndExit() {
        try {
            writeCast();
        } catch (IOException e) {
            System.err.println("Failed to write recording: " + e.getMessage());
        }
        exit.run();
    }

    /**
     * Writes the cast on the thread that {@linkplain #stop() stopped} the recording, then lets a {@link #close()} that
     * waits for it go on.
     */
    private void writeCast() throws IOException {
        try {
            writeCastFromDrawFrames();
        } finally {
            castWritten.countDown();
        }
    }

    /**
     * Waits, at most {@value #CAST_WAIT_SECONDS} seconds, for the thread that stopped the recording to write the cast.
     * An interrupt does not end the wait early, which would let the JVM exit with the cast cut off; it is kept for the
     * caller.
     */
    private void awaitCast() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CAST_WAIT_SECONDS);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!castWritten.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                        System.err.println("Gave up waiting " + CAST_WAIT_SECONDS + "s for the recording to be saved to: "
                                + config.outputPath());
                    }
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeCastFromDrawFrames() throws IOException {
        List<TimedFrame> recorded;
        synchronized (buffer) {
            // Write cast file from Backend.draw() captured frames (TUI demos)
            // For inline demos (no draw() calls), frames will be empty and we let
            // the shutdown hook write the System.out captured frames instead
            if (frames.isEmpty()) {
                return;  // Let shutdown hook handle AnsiTerminalCapture frames
            }

            // A draw since the last captured frame fell within the fps throttle: add the screen as it
            // is now, or the recording ends on the frame before it
            if (pendingDrawMs >= 0) {
                frames.add(new TimedFrame(buffer.copy(), pendingDrawMs));
                pendingDrawMs = -1;
            }
            // The recording has stopped, so no frame is added any more: write them outside the lock
            recorded = new ArrayList<>(frames);
        }

        // We have draw() frames - uninstall System.out capture and write draw frames
        AnsiTerminalCapture.uninstall();
        RecordingConfig.clearActive();  // Prevent shutdown hook from also writing

        AsciinemaAnimation animation = new AsciinemaAnimation(recorded, config.fps());
        String cast = animation.toCast();

        Path outputPath = config.outputPath();
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        beforeCastWrite.run();
        // Not Files.write: on Java 8 an interrupt closes the channel it writes to, and the app quitting interrupts the
        // input thread, which writes the cast at the end of the tape
        try (OutputStream out = new FileOutputStream(outputPath.toFile())) {
            out.write(cast.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("Recording saved to: " + outputPath);
        System.out.println("Frames captured: " + recorded.size());
    }
}
