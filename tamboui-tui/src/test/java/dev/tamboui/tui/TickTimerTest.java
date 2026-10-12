/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TickTimerTest {

    private static final Duration PERIOD = Duration.ofMillis(16);

    @Test
    @DisplayName("the first period passes one tick period after the timer is created")
    void firstPeriodPassesOnePeriodAfterCreation() {
        FakeClock clock = new FakeClock(5_000_000_000L);
        TickTimer timer = new TickTimer(PERIOD, clock);

        clock.advance(PERIOD.minusNanos(1));
        assertThat(timer.periodPassed()).isFalse();

        clock.advance(Duration.ofNanos(1));
        assertThat(timer.periodPassed()).isTrue();
        assertThat(timer.periodPassed()).as("checked again at once").isFalse();
    }

    @Test
    @DisplayName("a late check does not delay the periods after it")
    void periodsStayOnTheirGridAfterALateCheck() {
        FakeClock clock = new FakeClock(5_000_000_000L);
        TickTimer timer = new TickTimer(PERIOD, clock);

        clock.advance(Duration.ofMillis(26));
        assertThat(timer.periodPassed()).isTrue();

        // The second period ends at 32 ms, not 16 ms after the late check
        clock.advance(Duration.ofMillis(5));
        assertThat(timer.periodPassed()).as("at 31 ms").isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(timer.periodPassed()).as("at 32 ms").isTrue();
    }

    @Test
    @DisplayName("ticks keep coming after the clock jumps far ahead, as after a debugger pause")
    void periodsKeepPassingAfterTheClockJumpsAhead() {
        FakeClock clock = new FakeClock(5_000_000_000L);
        TickTimer timer = new TickTimer(PERIOD, clock);

        clock.advance(PERIOD);
        assertThat(timer.periodPassed()).isTrue();

        clock.advance(Duration.ofHours(1));
        assertThat(timer.periodPassed()).as("right after the jump").isTrue();
        clock.advance(PERIOD);
        assertThat(timer.periodPassed()).as("one period after the jump").isTrue();
    }

    @Test
    @DisplayName("a period does not pass early when the clock's value overflows before it ends")
    void periodDoesNotPassEarlyWhenTheClockValueOverflowsBeforeItEnds() {
        // The first period ends 6 ms past Long.MAX_VALUE, where the clock's value turns negative
        FakeClock clock = new FakeClock(Long.MAX_VALUE - Duration.ofMillis(10).toNanos());
        TickTimer timer = new TickTimer(PERIOD, clock);

        assertThat(timer.periodPassed()).as("right after the timer is created").isFalse();

        clock.advance(PERIOD);
        assertThat(timer.periodPassed()).as("when the period ends").isTrue();
    }

    @Test
    @DisplayName("a period passes when the clock's value overflows after it ends")
    void periodPassesWhenTheClockValueOverflowsAfterItEnds() {
        // The first period ends 4 ms before Long.MAX_VALUE, and the check comes 6 ms late
        FakeClock clock = new FakeClock(Long.MAX_VALUE - Duration.ofMillis(20).toNanos());
        TickTimer timer = new TickTimer(PERIOD, clock);

        clock.advance(Duration.ofMillis(22));
        assertThat(timer.periodPassed()).isTrue();
    }

    /**
     * A clock that only moves when the test advances it.
     */
    private static final class FakeClock implements LongSupplier {
        private long nanos;

        FakeClock(long nanos) {
            this.nanos = nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }
}
