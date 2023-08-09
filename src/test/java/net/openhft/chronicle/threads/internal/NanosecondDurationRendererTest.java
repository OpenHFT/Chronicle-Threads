package net.openhft.chronicle.threads.internal;

import org.junit.jupiter.api.Test;

import static net.openhft.chronicle.threads.internal.NanosecondDurationRenderer.renderNanosecondDuration;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NanosecondDurationRendererTest {

    @Test
    void timesInSecondScaleShouldBeRenderedAsSeconds() {
        assertEquals("1.3s", renderNanosecondDuration(1_342_723_123));
    }

    @Test
    void timesInMillisecondScaleShouldBeRenderedAsMilliseconds() {
        assertEquals("42.7ms", renderNanosecondDuration(42_723_123));
    }

    @Test
    void timesInMicrosecondScaleShouldBeRenderedAsMicroseconds() {
        assertEquals("23.1us", renderNanosecondDuration(23_123));
    }
}