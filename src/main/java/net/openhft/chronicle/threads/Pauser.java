/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Strategy interface for controlling how an idle thread waits for work.
 *
 * <p>The pauser is typically employed inside an event loop. When no work is
 * found {@link #pause()} is invoked; once work is performed the pauser is
 * {@link #reset()} back to its most responsive state. The usual pattern is:</p>
 *
 * <pre>{@code
 * while (running) {
 *     if (doWork()) {
 *         pauser.reset();
 *     } else {
 *         pauser.pause();
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations are expected to be thread-safe for use by a single event
 * loop thread. Calling the same pauser from multiple threads is not
 * supported unless stated otherwise.</p>
 *
 * <p>The factory methods will fall back to {@link #sleepy()} or
 * {@link #balanced()} when the host machine lacks sufficient processors. The
 * chosen mode affects CPU usage and latency. See {@link SleepyWarning} for the
 * detection logic.</p>
 *
 * <p>Configurations may be serialised via {@link PauserMode}.</p>
 */
public interface Pauser {

    int MIN_PROCESSORS = Jvm.getInteger("pauser.minProcessors", 4);

    boolean BALANCED = getBalanced(); // calculated once
    boolean SLEEPY = getSleepy();  // calculated once
    int MIN_BUSY = Integer.getInteger("balances.minBusy", OS.isWindows() ? 100_000 : 10_000);

    static boolean getBalanced() {
        int procs = AffinityLock.cpuLayout().cpus();
        return procs < MIN_PROCESSORS * 2;
    }

    static boolean getSleepy() {
        int procs = AffinityLock.cpuLayout().cpus();
        return procs < MIN_PROCESSORS;
    }

    /**
     * Returns a pauser that initially yields for {@code minBusy} microseconds
     * before entering a longer pause. If the machine has too few processors this
     * method falls back to {@link #balanced()} or {@link #sleepy()}.
     *
     * <p>Yielding gives low latency at the cost of extra CPU time.</p>
     *
     * @param minBusy minimal busy-yield period in microseconds
     * @return a pauser tuned for low latency or a fall-back if resources are limited
     */
    static Pauser yielding(int minBusy) {
        SleepyWarning.warnSleepy();
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : new YieldingPauser(minBusy);
    }

    /**
     * A very low CPU pauser. It yields once for roughly a millisecond and then
     * sleeps between one and twenty milliseconds. This gives the highest
     * latency but places minimal load on the processor.
     *
     * @return a pauser intended for background or low priority tasks
     */
    static TimingPauser sleepy() {
        return new LongPauser(0, 50, 500, 20_000, TimeUnit.MICROSECONDS);
    }

    /**
     * A pauser that spends a small amount of time busy and then backs off by
     * yielding and sleeping up to twenty milliseconds. It balances CPU
     * utilisation with moderate latency. Automatically selected on machines with
     * fewer than twice {@link #MIN_PROCESSORS} cores when {@link #busy()} or
     * {@link #yielding()} is requested.
     *
     * @return a pauser suitable for mixed workloads
     */
    static TimingPauser balanced() {
        return balancedUpToMillis(20);
    }

    /**
     * As {@link #balanced()} but the back-off sleeps will not exceed the given
     * limit. Use this when a service must not sleep for too long whilst still
     * avoiding excessive CPU use.
     *
     * @param millis maximum back-off period in milliseconds
     * @return a pauser with bounded sleep duration
     */
    static TimingPauser balancedUpToMillis(int millis) {
        return SLEEPY ? sleepy()
                : new LongPauser(MIN_BUSY, 800, 200, millis * 1000L, TimeUnit.MICROSECONDS);
    }

    /**
     * Creates a pauser that always sleeps for the specified time. It uses very
     * little CPU and therefore gives the highest latency of all strategies.
     *
     * @param millis fixed sleep time in milliseconds
     * @return a pauser that simply sleeps
     */
    static MilliPauser millis(int millis) {
        return new MilliPauser(millis);
    }

    /**
     * Creates a pauser that sleeps for {@code minMillis} and gradually backs
     * off up to {@code maxMillis}. Useful when a thread may be idle for long
     * periods but should still wake periodically.
     *
     * @param minMillis starting sleep time in milliseconds
     * @param maxMillis maximum sleep time in milliseconds
     * @return a pauser with growing sleep periods
     */
    static Pauser millis(int minMillis, int maxMillis) {
        return new LongPauser(0, 0, minMillis, maxMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Convenience method equivalent to {@link #yielding(int)} with a small
     * busy-yield period. Provides low latency when the machine has spare
     * processors and gracefully falls back otherwise.
     *
     * @return a pauser that initially yields
     */
    static Pauser yielding() {
        return yielding(2);
    }

    /**
     * Returns a pauser that continuously busy-spins. This offers the lowest
     * possible latency but consumes an entire core. When the runtime cannot
     * spare a core the method falls back to {@link #balanced()} or
     * {@link #sleepy()}.
     *
     * @return a pauser that never deliberately waits
     */
    @NotNull
    static Pauser busy() {
        SleepyWarning.warnSleepy();
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : BusyPauser.INSTANCE;
    }

    /**
     * Similar to {@link #busy()} but allows timed pauses so that a monitoring
     * thread can wait with a timeout. It still consumes a core when active and
     * will downgrade to {@link #balanced()} or {@link #sleepy()} if resources are
     * scarce.
     *
     * @return a pauser combining busy spinning with timed waits
     */
    @NotNull
    static TimingPauser timedBusy() {
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : new BusyTimedPauser();
    }

    /**
     * Called when the thread finds no work to do. Depending on the
     * implementation the call may busy-spin, yield or sleep. Successive calls
     * usually increase the pause duration until {@link #reset()} is invoked.
     */
    void pause();

    /**
     * Begins a pause that other handlers may monitor without blocking. The
     * caller can check {@link #asyncPausing()} to see if the pause has
     * completed and should typically yield until it returns {@code false}.
     */
    default void asyncPause() {
    }

    /**
     * Indicates whether a pause started with {@link #asyncPause()} is still in
     * progress.
     *
     * @return {@code true} while the pauser remains in its asynchronous pause
     */
    default boolean asyncPausing() {
        return false;
    }

    /**
     * Restores the pauser to its most responsive state. Call this after the
     * thread performs work so that the next {@link #pause()} starts from the
     * minimum delay.
     */
    void reset();

    /**
     * use {@link TimingPauser#pause(long, TimeUnit)} instead
     */
    default void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException(this + " is not stateful, use a " + TimingPauser.class.getSimpleName());
    }

    /**
     * Attempts to interrupt a pause initiated on another thread. The call may
     * have no effect if the thread is not currently pausing.
     */
    void unpause();

    /**
     * Total time spent in {@link #pause()} calls since the pauser was created.
     * The value is expressed in milliseconds.
     *
     * @return cumulative pause time in milliseconds
     */
    long timePaused();

    /**
     * Number of pause cycles that have occurred. Each call to
     * {@link #pause()} or {@link #asyncPause()} increments this count when a
     * pause actually takes place.
     *
     * @return how many pauses have been recorded
     */
    long countPaused();

    /**
     * Indicates that the pauser never performs a real wait and simply spins.
     * Useful for monitoring utilities that wish to avoid blocking.
     *
     * @return {@code true} if the implementation is busy-spinning
     */
    default boolean isBusy() {
        return false;
    }

    /**
     * Emits a single warning when the pauser factory selects {@link #sleepy()}
     * or {@link #balanced()} due to limited CPU resources.
     */
    enum SleepyWarning {
        ; // none

        static {
            if (SLEEPY) {
                int procs = Runtime.getRuntime().availableProcessors();
                Jvm.perf().on(Pauser.class, "Using Pauser.sleepy() as not enough processors, have " + procs + ", needs " + MIN_PROCESSORS + "+");
            } else if (BALANCED) {
                int procs = Runtime.getRuntime().availableProcessors();
                Jvm.perf().on(Pauser.class, "Using Pauser.balanced() as not enough processors, have " + procs + ", needs " + MIN_PROCESSORS * 2 + "+");
            }
        }

        @SuppressWarnings("EmptyMethod")
        static void warnSleepy() {
            // Do nothing here as run-once code is in the static block above.
        }
    }
}
