/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.internal.record;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiTerminalCaptureTest {

    @TempDir
    Path tempDir;

    private PrintStream originalOut;

    @BeforeEach
    void discardTheTee() {
        // The capture passes what is printed on to System.out as it was at install: discard it, not fill the test log
        originalOut = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                // discarded
            }
        }));
    }

    @AfterEach
    void tearDown() {
        if (AnsiTerminalCapture.isInstalled()) {
            AnsiTerminalCapture.uninstall();
        }
        System.setOut(originalOut);
    }

    @Test
    @DisplayName("Output printed after the recording duration is not kept in memory")
    void outputPrintedAfterTheDurationIsNotKept() {
        // A duration of -1 ms is over before anything is printed
        AnsiTerminalCapture.install(RecordingConfig.of(tempDir.resolve("out.cast"), 10, -1, 80, 24));
        char[] chars = new char[1023];
        Arrays.fill(chars, 'x');
        String line = new String(chars);

        // 1 MiB, as an app goes on printing once the recording has ended
        for (int i = 0; i < 1024; i++) {
            System.out.println(line);
        }

        assertThat(AnsiTerminalCapture.pendingBytes()).isZero();
        assertThat(AnsiTerminalCapture.uninstall()).isEmpty();
    }

    @Test
    @DisplayName("Output printed within the recording duration is captured")
    void outputPrintedWithinTheDurationIsCaptured() {
        AnsiTerminalCapture.install(RecordingConfig.of(tempDir.resolve("out.cast"), 10, 60000, 80, 24));

        System.out.println("hello");
        List<RawFrame> frames = AnsiTerminalCapture.uninstall();

        assertThat(frames).hasSize(1);
        assertThat(new String(frames.get(0).data(), StandardCharsets.UTF_8)).startsWith("hello");
    }
}
