/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.image.protocol;

import dev.tamboui.image.ImageData;
import dev.tamboui.layout.Rect;

/**
 * Where a character-based protocol draws an image in an area.
 * <p>
 * The image keeps its own size, one image pixel per virtual pixel of the protocol, and is
 * centered on whole cells of the area, so the cells around it keep their content. An image
 * wider or taller than the virtual pixel grid of the area is shrunk to the width or height
 * of the grid.
 */
final class CellPlacement {

    private static final int TRANSPARENT = 0;

    private final ImageData image;
    private final Rect cells;

    private CellPlacement(ImageData image, Rect cells) {
        this.image = image;
        this.cells = cells;
    }

    /**
     * Places an image in the center of an area.
     *
     * @param image      the image, already scaled for the area
     * @param area       the area to draw in, not empty
     * @param resolution the virtual pixels per cell of the protocol
     * @return the placement of the image
     */
    static CellPlacement centered(ImageData image, Rect area, ImageProtocol.Resolution resolution) {
        int pixelsPerColumn = resolution.widthMultiplier();
        int pixelsPerRow = resolution.heightMultiplier();
        ImageData fitted = image.resize(
            Math.min(image.width(), area.width() * pixelsPerColumn),
            Math.min(image.height(), area.height() * pixelsPerRow));
        int columns = (fitted.width() + pixelsPerColumn - 1) / pixelsPerColumn;
        int rows = (fitted.height() + pixelsPerRow - 1) / pixelsPerRow;
        Rect cells = new Rect(
            area.x() + (area.width() - columns) / 2,
            area.y() + (area.height() - rows) / 2,
            columns,
            rows);
        return new CellPlacement(fitted, cells);
    }

    /**
     * Returns the image to draw, which is no larger than the virtual pixel grid of the area.
     *
     * @return the image
     */
    ImageData image() {
        return image;
    }

    /**
     * Returns the cells the image covers.
     *
     * @return the cells, inside the area
     */
    Rect cells() {
        return cells;
    }

    /**
     * Returns the pixel at a virtual pixel position, counted from the top-left corner of
     * {@link #cells()}.
     * <p>
     * The last column and row of cells can reach past the right and bottom edges of the image;
     * the pixels there are transparent.
     *
     * @param x the virtual pixel column
     * @param y the virtual pixel row
     * @return the ARGB pixel value
     */
    int pixelAt(int x, int y) {
        if (x < image.width() && y < image.height()) {
            return image.pixelAt(x, y);
        }
        return TRANSPARENT;
    }
}
