/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.toolkit.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.AbstractElementTest;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.RenderContext;

import static dev.tamboui.assertj.BufferAssertions.assertThat;
import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.text;
import static org.assertj.core.api.Assertions.assertThat;

class ComponentRenderTest extends AbstractElementTest {

    static class TextComponent extends Component<TextComponent> {
        @Override
        protected Element render() {
            return text("INSIDE");
        }
    }

    static class NullComponent extends Component<NullComponent> {
        @Override
        protected Element render() {
            return null;
        }
    }

    @Test
    @DisplayName("A component in a column is given room and renders its content")
    void componentInColumnRenders() {
        Rect area = new Rect(0, 0, 10, 3);
        Buffer buffer = Buffer.empty(area);
        Frame frame = Frame.forTesting(buffer);

        column(text("BEFORE"), new TextComponent().id("c"), text("AFTER"))
            .render(frame, area, DefaultRenderContext.createEmpty());

        assertThat(buffer).hasRow(0, "BEFORE    ");
        assertThat(buffer).hasRow(1, "INSIDE    ");
        assertThat(buffer).hasRow(2, "AFTER     ");
    }

    @Test
    @DisplayName("A component's preferred size is that of its rendered content")
    void preferredSizeFollowsContent() {
        RenderContext context = DefaultRenderContext.createEmpty();

        Size size = new TextComponent().preferredSize(80, 24, context);

        assertThat(size.width()).isEqualTo(6);
        assertThat(size.height()).isEqualTo(1);
    }

    @Test
    @DisplayName("A component whose render() returns null prefers no size")
    void preferredSizeIsZeroWhenRenderReturnsNull() {
        RenderContext context = DefaultRenderContext.createEmpty();

        Size size = new NullComponent().preferredSize(80, 24, context);

        assertThat(size).isEqualTo(Size.ZERO);
    }
}
