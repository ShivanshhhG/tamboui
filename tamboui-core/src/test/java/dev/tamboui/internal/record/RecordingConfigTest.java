/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.record;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link RecordingConfig} property handling, in particular enabling
 * recording with only {@code tamboui.record.config} set: naming the input tape is
 * the explicit act, and the cast output is derived from the tape name so agents
 * and scripts can drive-and-record with a single property.
 */
class RecordingConfigTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (AnsiTerminalCapture.isInstalled()) {
            AnsiTerminalCapture.uninstall();
        }
        System.clearProperty("tamboui.record");
        System.clearProperty("tamboui.record.config");
        System.clearProperty("tamboui.record.fps");
        System.clearProperty("tamboui.record.duration");
        System.clearProperty("tamboui.record.width");
        System.clearProperty("tamboui.record.height");
        RecordingConfig.clearActive();
    }

    @Test
    @DisplayName("config property alone enables recording with derived .cast output")
    void configAloneDerivesCastOutput() {
        Path tape = tempDir.resolve("script.tape");
        System.setProperty("tamboui.record.config", tape.toString());

        assertThat(RecordingConfig.isEnabled()).isTrue();
        RecordingConfig config = RecordingConfig.load();

        assertThat(config).isNotNull();
        assertThat(config.configFile()).isEqualTo(tape);
        assertThat(config.outputPath()).isEqualTo(tempDir.resolve("script.cast"));
    }

    @Test
    @DisplayName("derived output appends .cast when the config file has no extension")
    void derivedOutputWithoutExtension() {
        Path tape = tempDir.resolve("script");
        System.setProperty("tamboui.record.config", tape.toString());

        RecordingConfig config = RecordingConfig.load();

        assertThat(config).isNotNull();
        assertThat(config.outputPath()).isEqualTo(tempDir.resolve("script.cast"));
    }

    @Test
    @DisplayName("explicit output property wins over derivation")
    void explicitOutputWins() {
        Path tape = tempDir.resolve("script.tape");
        Path cast = tempDir.resolve("elsewhere.cast");
        System.setProperty("tamboui.record", cast.toString());
        System.setProperty("tamboui.record.config", tape.toString());

        RecordingConfig config = RecordingConfig.load();

        assertThat(config).isNotNull();
        assertThat(config.outputPath()).isEqualTo(cast);
        assertThat(config.configFile()).isEqualTo(tape);
    }

    @Test
    @DisplayName("recording stays disabled when neither property is set")
    void disabledWithoutProperties() {
        assertThat(RecordingConfig.isEnabled()).isFalse();
        assertThat(RecordingConfig.load()).isNull();
    }

    @Test
    @DisplayName("an fps of 0 falls back to the default, so printing while recording does not divide by zero")
    void zeroFpsFallsBackToTheDefault() throws Exception {
        System.setProperty("tamboui.record", tempDir.resolve("out.cast").toString());
        System.setProperty("tamboui.record.fps", "0");

        RecordingConfig config = loadReportingTo(new ByteArrayOutputStream());

        // load() installs the capture as System.out, which works out the frame interval on every flush
        assertThatCode(() -> System.out.println("printed while recording")).doesNotThrowAnyException();
        assertThat(config.fps()).isEqualTo(10);
    }

    @Test
    @DisplayName("a frame rate, duration or size that is not a positive integer is reported, and the default is used")
    void numbersThatAreNotPositiveAreReportedAndReplacedByTheDefaults() throws Exception {
        System.setProperty("tamboui.record", tempDir.resolve("out.cast").toString());
        System.setProperty("tamboui.record.fps", "-3");
        System.setProperty("tamboui.record.duration", "0");
        System.setProperty("tamboui.record.width", "0");
        System.setProperty("tamboui.record.height", "ten");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        RecordingConfig config = loadReportingTo(err);

        assertThat(config.fps()).isEqualTo(10);
        assertThat(config.maxDurationMs()).isEqualTo(10000);
        assertThat(config.width()).isEqualTo(80);
        assertThat(config.height()).isEqualTo(24);
        assertThat(err.toString("UTF-8"))
                .contains("Ignoring tamboui.record.fps=-3")
                .contains("Ignoring tamboui.record.duration=0")
                .contains("Ignoring tamboui.record.width=0")
                .contains("Ignoring tamboui.record.height=ten");
    }

    @Test
    @DisplayName("positive numbers are used as given, without a warning")
    void positiveNumbersAreUsed() throws Exception {
        System.setProperty("tamboui.record", tempDir.resolve("out.cast").toString());
        System.setProperty("tamboui.record.fps", "30");
        // read as Integer.getInteger reads it
        System.setProperty("tamboui.record.duration", "0x7530");
        System.setProperty("tamboui.record.width", "120");
        System.setProperty("tamboui.record.height", "40");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        RecordingConfig config = loadReportingTo(err);

        assertThat(config.fps()).isEqualTo(30);
        assertThat(config.maxDurationMs()).isEqualTo(30000);
        assertThat(config.width()).isEqualTo(120);
        assertThat(config.height()).isEqualTo(40);
        assertThat(err.toString("UTF-8")).isEmpty();
    }

    /** Loads the config, with what it reports on standard error written to {@code err}. */
    private static RecordingConfig loadReportingTo(ByteArrayOutputStream err) throws Exception {
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true, "UTF-8"));
        try {
            return RecordingConfig.load();
        } finally {
            System.setErr(originalErr);
        }
    }
}
