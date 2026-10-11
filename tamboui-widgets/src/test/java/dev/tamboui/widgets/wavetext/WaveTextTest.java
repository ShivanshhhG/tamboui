/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.widgets.wavetext;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.CharWidth;

import static dev.tamboui.assertj.BufferAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class WaveTextTest {

    // man + ZWJ + woman + ZWJ + girl: one character, 2 columns wide
    private static final String FAMILY = "👨\u200D👩\u200D👧";

    @Test
    @DisplayName("WaveText dims the characters under the wave peak")
    void dimsCharactersUnderPeak() {
        WaveText wave = WaveText.builder()
            .text("Loading...")
            .color(Color.rgb(200, 200, 200))
            .dimFactor(0.5)
            .peakWidth(2)
            .build();
        Rect area = new Rect(0, 0, 12, 1);
        Buffer buffer = Buffer.empty(area);

        wave.render(area, buffer, new WaveTextState(10));

        // At tick 10 the peak is on "o" and reaches one character to each side
        Color dim = Color.rgb(100, 100, 100);
        Color bright = Color.rgb(200, 200, 200);
        assertThat(buffer)
            .hasRow(0, "Loading...  ")
            .hasForegroundAt(0, 0, dim)
            .hasForegroundAt(1, 0, dim)
            .hasForegroundAt(2, 0, dim)
            .hasForegroundAt(3, 0, bright)
            .hasForegroundAt(9, 0, bright);
    }

    @Test
    @DisplayName("WaveText gives each CJK character two cells")
    void cjkCharactersTakeTwoCells() {
        Rect area = new Rect(0, 0, 4, 1);
        Buffer buffer = Buffer.empty(area);

        WaveText.of("中文", Color.CYAN).render(area, buffer, new WaveTextState());

        assertThat(buffer)
            .hasSymbolAt(0, 0, "中")
            .hasSymbolAt(1, 0, "")
            .hasSymbolAt(2, 0, "文")
            .hasSymbolAt(3, 0, "");
    }

    @Test
    @DisplayName("WaveText draws an emoji whole")
    void emojiIsDrawnWhole() {
        Rect area = new Rect(0, 0, 8, 1);
        Buffer buffer = Buffer.empty(area);

        WaveText.of("🚀 Go", Color.CYAN).render(area, buffer, new WaveTextState());

        assertThat(buffer)
            .hasSymbolAt(0, 0, "🚀")
            .hasSymbolAt(1, 0, "")
            .hasRow(0, "🚀 Go   ");
    }

    @Test
    @DisplayName("WaveText keeps a ZWJ emoji sequence whole")
    void zwjSequenceIsDrawnWhole() {
        Rect area = new Rect(0, 0, 8, 1);
        Buffer buffer = Buffer.empty(area);

        WaveText.of(FAMILY + " Go", Color.CYAN).render(area, buffer, new WaveTextState());

        assertThat(buffer)
            .hasSymbolAt(0, 0, FAMILY)
            .hasSymbolAt(1, 0, "")
            .hasRow(0, FAMILY + " Go   ");
    }

    @Test
    @DisplayName("WaveText leaves out a wide character that does not fit the area")
    void wideCharacterThatDoesNotFitIsLeftOut() {
        Buffer buffer = Buffer.empty(new Rect(0, 0, 5, 1));
        Rect area = new Rect(0, 0, 3, 1);

        WaveText.of("中文", Color.CYAN).render(area, buffer, new WaveTextState());

        // 文 would take columns 2 and 3, but the area ends after column 2
        assertThat(buffer)
            .hasSymbolAt(0, 0, "中")
            .hasRow(0, "中   ");
    }

    @Test
    @DisplayName("WaveText moves the wave by character, so a wide character counts as one")
    void waveMovesByCharacter() {
        for (long tick = 0; tick < 60; tick++) {
            assertThat(characterColors("加载中...", tick))
                .as("tick %d", tick)
                .isEqualTo(characterColors("abc...", tick));
            assertThat(characterColors("🔥 Hot", tick))
                .as("tick %d", tick)
                .isEqualTo(characterColors("x Hot", tick));
            assertThat(characterColors(FAMILY + " Hot", tick))
                .as("tick %d", tick)
                .isEqualTo(characterColors("x Hot", tick));
        }
    }

    private static List<Color> characterColors(String text, long tick) {
        Rect area = new Rect(0, 0, 20, 1);
        Buffer buffer = Buffer.empty(area);
        WaveText.of(text, Color.rgb(200, 200, 200)).render(area, buffer, new WaveTextState(tick));

        List<Color> colors = new ArrayList<>();
        for (int x = 0; x < CharWidth.of(text); x++) {
            Cell cell = buffer.get(x, 0);
            if (!cell.isContinuation()) {
                colors.add(cell.style().fg().orElse(null));
            }
        }
        return colors;
    }
}
