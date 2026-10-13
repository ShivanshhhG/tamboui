/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.backend.panama;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.layout.Size;

import static org.assertj.core.api.Assertions.assertThat;

class PanamaBackendCloseTest {

    private static final String MODE_2027_OFF = "\u001b[?2027l";

    @Test
    @DisplayName("close() turns grapheme cluster mode (2027) off before the terminal modes are restored")
    void closeTurnsMode2027Off() throws Exception {
        // TuiRunner closes the backend without calling disableRawMode() first
        FakeTerminal terminal = new FakeTerminal();
        PanamaBackend backend = new PanamaBackend(terminal);
        backend.enableRawMode();
        backend.enterAlternateScreen();

        backend.close();

        String output = terminal.output();
        assertThat(output).contains(MODE_2027_OFF);
        // On Windows, an escape sequence written after the console modes are restored can show up as text
        assertThat(output.indexOf(MODE_2027_OFF)).isLessThan(output.indexOf(FakeTerminal.MODES_RESTORED));
    }

    private static final class FakeTerminal implements PlatformTerminal {
        static final String MODES_RESTORED = "<modes restored>";

        private final StringBuilder output = new StringBuilder();
        private boolean rawMode;

        @Override
        public void enableRawMode() {
            rawMode = true;
        }

        @Override
        public void disableRawMode() {
            if (rawMode) {
                rawMode = false;
                output.append(MODES_RESTORED);
            }
        }

        @Override
        public Size getSize() {
            return new Size(80, 24);
        }

        @Override
        public int read(int timeoutMs) {
            return -2;
        }

        @Override
        public int peek(int timeoutMs) {
            return -2;
        }

        @Override
        public void write(byte[] data) {
            write(data, 0, data.length);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            output.append(new String(buffer, offset, length, StandardCharsets.UTF_8));
        }

        @Override
        public void write(String s) {
            output.append(s);
        }

        @Override
        public Charset getCharset() {
            return StandardCharsets.UTF_8;
        }

        @Override
        public boolean isRawModeEnabled() {
            return rawMode;
        }

        @Override
        public void onResize(Runnable handler) {
        }

        @Override
        public void close() {
            // Like the Unix and Windows terminals, closing restores the modes
            disableRawMode();
        }

        String output() {
            return output.toString();
        }
    }
}
