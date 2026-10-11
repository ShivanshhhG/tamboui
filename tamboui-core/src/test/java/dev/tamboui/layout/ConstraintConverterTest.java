/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ConstraintConverterTest {

    private final ConstraintConverter converter = ConstraintConverter.INSTANCE;

    @Test
    @DisplayName("percentage above 100 converts to empty")
    void percentageAbove100IsEmpty() {
        assertThat(converter.convert("100%")).contains(Constraint.percentage(100));
        assertThat(converter.convert("150%")).isEmpty();
    }

    @Test
    @DisplayName("ratio with a zero denominator converts to empty")
    void ratioWithZeroDenominatorIsEmpty() {
        assertThat(converter.convert("1/3")).contains(Constraint.ratio(1, 3));
        assertThat(converter.convert("1/0")).isEmpty();
    }

    @Test
    @DisplayName("numbers too large for an int convert to empty")
    void numbersTooLargeForAnIntAreEmpty() {
        assertThat(converter.convert("99999999999")).isEmpty();
        assertThat(converter.convert("99999999999%")).isEmpty();
        assertThat(converter.convert("99999999999fr")).isEmpty();
        assertThat(converter.convert("fill(99999999999)")).isEmpty();
        assertThat(converter.convert("min(99999999999)")).isEmpty();
        assertThat(converter.convert("max(99999999999)")).isEmpty();
        assertThat(converter.convert("1/99999999999")).isEmpty();
    }
}
