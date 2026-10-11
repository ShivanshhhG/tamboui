/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.image.protocol;

import java.io.OutputStream;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.image.ImageData;
import dev.tamboui.image.capability.TerminalImageProtocol;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Renders images using Unicode half-block characters.
 * <p>
 * Each terminal cell can display two vertical half-blocks (▀▄█),
 * providing 1x2 virtual pixel resolution per cell.
 * <p>
 * The top half uses the foreground color and the bottom half uses the background color,
 * allowing two independent colors per cell.
 * <p>
 * The image is drawn at its own size, one pixel per half cell, centered in the area, and the
 * cells around it are left untouched. Scaling the image to the area is up to the caller, as
 * the {@link dev.tamboui.image.Image} widget does according to its
 * {@link dev.tamboui.image.ImageScaling}. An image wider or taller than the area is shrunk
 * to the width or height of the area.
 */
public final class HalfBlockProtocol implements ImageProtocol {

    /** Upper half block character. */
    private static final String UPPER_HALF = "▀";

    /** Lower half block character. */
    private static final String LOWER_HALF = "▄";

    /** Full block character. */
    private static final String FULL_BLOCK = "█";

    private static final Resolution RESOLUTION = new Resolution(1, 2);

    /**
     * Creates a new half-block protocol instance.
     */
    public HalfBlockProtocol() {
    }

    @Override
    public void render(ImageData image, Rect area, Buffer buffer, OutputStream rawOutput) {
        if (area.isEmpty()) {
            return;
        }

        CellPlacement placement = CellPlacement.centered(image, area, RESOLUTION);
        Rect cells = placement.cells();

        // Render each cell
        for (int cellY = 0; cellY < cells.height(); cellY++) {
            for (int cellX = 0; cellX < cells.width(); cellX++) {
                int topPixel = placement.pixelAt(cellX, cellY * 2);
                int bottomPixel = placement.pixelAt(cellX, cellY * 2 + 1);

                boolean topVisible = ImageData.isVisible(topPixel);
                boolean bottomVisible = ImageData.isVisible(bottomPixel);

                if (!topVisible && !bottomVisible) {
                    continue; // Transparent cell
                }

                String symbol;
                Style style;

                if (topVisible && bottomVisible) {
                    // Both halves filled - use upper half block with top as FG, bottom as BG
                    symbol = UPPER_HALF;
                    Color topColor = argbToColor(topPixel);
                    Color bottomColor = argbToColor(bottomPixel);
                    style = Style.EMPTY.fg(topColor).bg(bottomColor);
                } else if (topVisible) {
                    // Only top half - use upper half block
                    symbol = UPPER_HALF;
                    Color topColor = argbToColor(topPixel);
                    style = Style.EMPTY.fg(topColor);
                } else {
                    // Only bottom half - use lower half block
                    symbol = LOWER_HALF;
                    Color bottomColor = argbToColor(bottomPixel);
                    style = Style.EMPTY.fg(bottomColor);
                }

                buffer.setString(cells.x() + cellX, cells.y() + cellY, symbol, style);
            }
        }
    }

    @Override
    public boolean requiresRawOutput() {
        return false;
    }

    @Override
    public Resolution resolution() {
        return RESOLUTION;
    }

    @Override
    public String name() {
        return "Half-Block";
    }

    @Override
    public TerminalImageProtocol protocolType() {
        return TerminalImageProtocol.HALF_BLOCK;
    }

    /**
     * Converts an ARGB pixel to a TamboUI Color.
     */
    private static Color argbToColor(int argb) {
        int r = ImageData.red(argb);
        int g = ImageData.green(argb);
        int b = ImageData.blue(argb);
        return Color.rgb(r, g, b);
    }
}
