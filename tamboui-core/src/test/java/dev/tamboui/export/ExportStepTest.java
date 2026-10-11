/*
 * Copyright (c) 2026 TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;

import static dev.tamboui.export.ExportRequest.export;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExportStepTest {

    static Stream<Arguments> formats() {
        return Stream.of(
            Arguments.of(Named.of("SVG", Formats.SVG)),
            Arguments.of(Named.of("HTML", Formats.HTML)),
            Arguments.of(Named.of("text", Formats.TEXT)));
    }

    @ParameterizedTest
    @MethodSource("formats")
    void toWriterThrowsTheIOExceptionOfTheWriter(Format<?> format) {
        IOException diskFull = new IOException("No space left on device");
        Writer writer = new Writer() {
            @Override
            public void write(char[] chars, int offset, int length) throws IOException {
                throw diskFull;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        IOException thrown = assertThrows(IOException.class, () -> export(fullBuffer()).as(format).to(writer));
        assertSame(diskFull, thrown);
    }

    @ParameterizedTest
    @MethodSource("formats")
    void toOutputStreamThrowsTheIOExceptionOfTheStream(Format<?> format) {
        IOException brokenPipe = new IOException("Broken pipe");
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw brokenPipe;
            }
        };

        IOException thrown = assertThrows(IOException.class, () -> export(fullBuffer()).as(format).to(out));
        assertSame(brokenPipe, thrown);
    }

    @ParameterizedTest
    @MethodSource("formats")
    void encoderThrowsTheIOExceptionOfTheOutputAsUncheckedIOException(Format<?> format) {
        IOException brokenPipe = new IOException("Broken pipe");
        Appendable out = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw brokenPipe;
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw brokenPipe;
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw brokenPipe;
            }
        };

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> encode(format, out));
        assertSame(brokenPipe, thrown.getCause());
    }

    private static <O extends ExportOptions> void encode(Format<O> format, Appendable out) {
        Buffer buffer = fullBuffer();
        format.encoder().encode(buffer, buffer.area(), format.defaultOptions(), out);
    }

    /**
     * A buffer whose export, in every format, is larger than the 8 KB an
     * {@code OutputStreamWriter} buffers, so the stream is written to while the export is
     * encoded instead of only when it is flushed.
     */
    private static Buffer fullBuffer() {
        char[] row = new char[120];
        Arrays.fill(row, 'x');
        Buffer buffer = Buffer.empty(new Rect(0, 0, row.length, 100));
        for (int y = 0; y < 100; y++) {
            buffer.setString(0, y, new String(row), Style.EMPTY);
        }
        return buffer;
    }
}
