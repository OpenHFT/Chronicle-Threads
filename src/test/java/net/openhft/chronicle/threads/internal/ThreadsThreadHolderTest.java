package net.openhft.chronicle.threads.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadsThreadHolderTest {

    @Test
    void testNanosecondsToMillisWithTenthsPrecision() {
        assertEquals(1.2d, ThreadsThreadHolder.nanosecondsToMillisWithTenthsPrecision(1_234_567), 0.000000001);
    }
}