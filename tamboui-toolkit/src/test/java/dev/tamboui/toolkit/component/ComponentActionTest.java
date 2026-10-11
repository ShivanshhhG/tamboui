/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.toolkit.component;

import org.junit.jupiter.api.Test;

import dev.tamboui.annotations.bindings.OnAction;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.AbstractElementTest;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import static dev.tamboui.toolkit.Toolkit.text;
import static org.assertj.core.api.Assertions.assertThat;

class ComponentActionTest extends AbstractElementTest {

    static class ConfirmComponent extends Component<ConfirmComponent> {
        int confirmed;

        @OnAction(Actions.CONFIRM)
        void onConfirm(Event event) {
            confirmed++;
        }

        @Override
        protected Element render() {
            return text("Press Enter");
        }
    }

    @Test
    void enterReachesConfirmHandler() {
        // Enter is both "select" and "confirm" in the standard bindings; only "confirm" has a handler
        ConfirmComponent component = new ConfirmComponent();
        Rect area = new Rect(0, 0, 20, 1);
        component.render(Frame.forTesting(Buffer.empty(area)), area, DefaultRenderContext.createEmpty());

        EventResult result = component.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER), true);

        assertThat(result).isEqualTo(EventResult.HANDLED);
        assertThat(component.confirmed).isEqualTo(1);
    }
}
