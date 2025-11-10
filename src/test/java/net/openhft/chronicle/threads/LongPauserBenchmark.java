//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark used to gauge the overhead of waking a {@link LongPauser}.
 *
 * A helper thread loops calling {@link LongPauser#pause()} and then yields.
 * The main thread repeatedly invokes {@link LongPauser#unpause()} a fixed
 * number of times and measures the elapsed time. Dividing the total by the
 * iteration count reveals the average cost of a single unpark operation.
 */
public final class LongPauserBenchmark {

    public static void main(String[] args) {
        final LongPauser pauser = new LongPauser(1, 1, 100, 1000, TimeUnit.MICROSECONDS);
        Thread thread = new Thread(() -> {
            while (!Thread.interrupted()) {
                pauser.pause();
                Thread.yield();
            }
        });
        thread.start();

        for (int t = 0; t < 3; t++) {
            long start = System.nanoTime();
            int runs = 10000000;
            for (int i = 0; i < runs; i++)
                pauser.unpause();
            long time = System.nanoTime() - start;
            System.out.printf("Average time to unpark was %,d ns%n", time / runs);
            Jvm.pause(20);
        }
        thread.interrupt();
    }
}
