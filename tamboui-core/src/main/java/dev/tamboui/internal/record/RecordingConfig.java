/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Configuration for Asciinema (.cast) recording, loaded from system properties.
 * This is an internal API and not part of the public contract.
 */
public final class RecordingConfig {

    private static final String PREFIX = "tamboui.record";
    private static RecordingConfig activeConfig = null;
    private static volatile boolean shutdownHookRegistered = false;

    private final Path outputPath;
    private final int fps;
    private final int maxDurationMs;
    private final int width;
    private final int height;
    private final Path configFile;

    RecordingConfig(Path outputPath, int fps, int maxDurationMs, int width, int height, Path configFile) {
        this.outputPath = outputPath;
        this.fps = fps;
        this.maxDurationMs = maxDurationMs;
        this.width = width;
        this.height = height;
        this.configFile = configFile;
    }

    /**
     * Loads recording configuration from system properties and installs capture.
     * This method installs AnsiTerminalCapture immediately so that all System.out
     * output is captured from the start, not just after the first Backend is created.
     *
     * @return configuration if recording is enabled, null otherwise
     */
    public static synchronized RecordingConfig load() {
        // Return cached config if already loaded
        if (activeConfig != null) {
            return activeConfig;
        }

        String output = System.getProperty(PREFIX);
        String configPath = System.getProperty(PREFIX + ".config");
        if (output == null && configPath == null) {
            return null; // Recording disabled
        }

        // Naming an input tape is the explicit act; when no output is given, derive the
        // cast path from the tape name (script.tape -> script.cast) so a single property
        // is enough to drive-and-record.
        Path outputPath = output != null ? Paths.get(output) : deriveCastPath(Paths.get(configPath));

        RecordingConfig config = new RecordingConfig(
                outputPath,
                positiveInteger("fps", 10),
                positiveInteger("duration", 10000),
                positiveInteger("width", 80),
                positiveInteger("height", 24),
                configPath != null ? Paths.get(configPath) : null
        );

        // Install System.out capture immediately so all output is captured
        AnsiTerminalCapture.install(config);
        activeConfig = config;

        // Register shutdown hook to write cast file at process exit
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(RecordingConfig::writeShutdownCast));
        }

        return config;
    }

    /**
     * Returns the active recording config, or null if not recording.
     *
     * @return the active config, or null
     */
    public static synchronized RecordingConfig active() {
        return activeConfig;
    }

    /**
     * Checks if recording is enabled via system properties.
     * This does NOT install the capture - use {@link #load()} for that.
     *
     * @return true if recording is configured
     */
    public static boolean isEnabled() {
        return System.getProperty(PREFIX) != null || System.getProperty(PREFIX + ".config") != null;
    }

    /**
     * Derives the cast output path from a config/tape path by replacing its extension
     * with {@code .cast}, e.g. {@code demo/script.tape} becomes {@code demo/script.cast}.
     *
     * @param configFile the config/tape file path
     * @return the derived cast output path in the same directory
     */
    private static Path deriveCastPath(Path configFile) {
        String name = configFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String castName = (dot > 0 ? name.substring(0, dot) : name) + ".cast";
        Path parent = configFile.getParent();
        return parent != null ? parent.resolve(castName) : Paths.get(castName);
    }

    /**
     * Reads the {@code tamboui.record.<name>} property, which must be a positive integer. Any other value would break
     * the recording: a frame rate of 0 divides by zero on every frame and on every {@code System.out} flush, a negative
     * size fails to create the screen, and a size or duration of 0 records nothing. Such a value, or one that is not a
     * number, is reported on standard error and the default is used.
     *
     * @param name         the property name after {@code tamboui.record.}
     * @param defaultValue the value to use when the property is not set or not a positive integer
     * @return the value of the property, or the default
     */
    private static int positiveInteger(String name, int defaultValue) {
        String property = PREFIX + "." + name;
        String value = System.getProperty(property);
        if (value == null) {
            return defaultValue;
        }
        try {
            // Read as Integer.getInteger() read it before, so a value such as 0x10 still works
            int number = Integer.decode(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // reported below, as a number that is not positive
        }
        System.err.println("Warning: Ignoring " + property + "=" + value + ", which is not a positive integer; using "
                + defaultValue);
        return defaultValue;
    }

    /**
     * Clears the active config (called when RecordingBackend writes from draw frames).
     */
    static synchronized void clearActive() {
        activeConfig = null;
    }

    /**
     * Shutdown hook to write captured frames to cast file.
     */
    private static void writeShutdownCast() {
        RecordingConfig config;
        int captureWidth;
        int captureHeight;
        synchronized (RecordingConfig.class) {
            config = activeConfig;
            if (config == null || !AnsiTerminalCapture.isInstalled()) {
                return;
            }
            // Capture dimensions before uninstalling
            captureWidth = AnsiTerminalCapture.getWidth();
            captureHeight = AnsiTerminalCapture.getHeight();
        }

        try {
            List<RawFrame> capturedFrames = AnsiTerminalCapture.uninstall();
            if (capturedFrames.isEmpty()) {
                return;
            }

            String cast = AsciinemaAnimation.fromRawFrames(capturedFrames, captureWidth, captureHeight);

            Path outputPath = config.outputPath();
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.write(outputPath, cast.getBytes(StandardCharsets.UTF_8));
            // Use original System.out since we just uninstalled the capture
            System.out.println("Recording saved to: " + outputPath);
            System.out.println("Frames captured: " + capturedFrames.size());
        } catch (IOException e) {
            System.err.println("Failed to write recording: " + e.getMessage());
        } finally {
            synchronized (RecordingConfig.class) {
                activeConfig = null;
            }
        }
    }

    /**
     * Creates a config with explicit values (used by TuiRunner for config file support).
     *
     * @param outputPath    the output file path
     * @param fps           the frames per second
     * @param maxDurationMs the maximum recording duration in milliseconds
     * @param width         the recording width in columns
     * @param height        the recording height in rows
     * @return a new recording config
     */
    public static RecordingConfig of(Path outputPath, int fps, int maxDurationMs, int width, int height) {
        return new RecordingConfig(outputPath, fps, maxDurationMs, width, height, null);
    }

    /**
     * Returns the output file path.
     *
     * @return the output path
     */
    public Path outputPath() {
        return outputPath;
    }

    /**
     * Returns the frames per second.
     *
     * @return the FPS
     */
    public int fps() {
        return fps;
    }

    /**
     * Returns the maximum recording duration in milliseconds.
     *
     * @return the maximum duration
     */
    public int maxDurationMs() {
        return maxDurationMs;
    }

    /**
     * Returns the recording width in columns.
     *
     * @return the width
     */
    public int width() {
        return width;
    }

    /**
     * Returns the recording height in rows.
     *
     * @return the height
     */
    public int height() {
        return height;
    }

    /**
     * Returns the config file path, or null if loaded from system properties.
     *
     * @return the config file path, or null
     */
    public Path configFile() {
        return configFile;
    }
}
