/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.threads;

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides a suite of factory methods for creating various {@link Pauser} objects, each offering different strategies for managing thread execution.
 * The {@link Pauser} is designed to offer flexible pausing strategies depending on CPU availability and desired execution patterns.
 *
 * <p>This interface also defines the methods for managing pause states and conditions within an application's threading model. It includes methods to pause, unpause, reset, and other utilities that influence thread scheduling and execution behaviors.</p>
 *
 * <p>Refer to {@link PauserMode} for capturing these configurations in a serializable manner.</p>
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
     * Returns a {@link Pauser} that either yields, pauses, or does not wait at all, based on system capabilities.
     * It selects the most appropriate pauser based on CPU availability and specified minimal busyness.
     *
     * @param minBusy the minimal busyness period in microseconds before yielding or pausing
     * @return the most appropriate {@link Pauser}
     */
    static Pauser yielding(int minBusy) {
        SleepyWarning.warnSleepy();
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : new YieldingPauser(minBusy);
    }

    /**
     * A sleepy pauser which yields for a millisecond, then sleeps for 1 to 20 ms
     *
     * @return a {@link TimingPauser} implementing a sleepy strategy
     */
    static TimingPauser sleepy() {
        return new LongPauser(0, 50, 500, 20_000, TimeUnit.MICROSECONDS);
    }

    /**
     * A balanced pauser which tries to be busy for short bursts but backs off when idle.
     *
     * @return a {@link TimingPauser} implementing a balanced strategy
     */
    static TimingPauser balanced() {
        return balancedUpToMillis(20);
    }

    /**
     * A balanced pauser which tries to be busy for short bursts but backs off when idle with a limit of max back off.
     *
     * @param millis the maximum back-off period in milliseconds
     * @return a {@link TimingPauser} implementing a balanced strategy with a maximum back-off limit
     */
    static TimingPauser balancedUpToMillis(int millis) {
        return SLEEPY ? sleepy()
                : new LongPauser(MIN_BUSY, 800, 200, millis * 1000L, TimeUnit.MICROSECONDS);
    }

    /**
     * Creates a {@link MilliPauser} that waits for a fixed duration before resuming execution.
     *
     * @param millis the fixed wait time in milliseconds
     * @return a {@link MilliPauser}
     */
    static MilliPauser millis(int millis) {
        return new MilliPauser(millis);
    }

    /**
     * Creates a {@link Pauser} that pauses with a back-off strategy, starting at a minimum millisecond interval and potentially increasing to a maximum.
     *
     * @param minMillis the starting minimum pause duration in milliseconds
     * @param maxMillis the maximum pause duration in milliseconds
     * @return a {@link Pauser} with a back-off strategy
     */
    static Pauser millis(int minMillis, int maxMillis) {
        return new LongPauser(0, 0, minMillis, maxMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Provides a simple {@link Pauser} that is more process-friendly by yielding the thread execution.
     *
     * @return a yielding {@link Pauser}
     */
    static Pauser yielding() {
        return yielding(2);
    }

    /**
     * Creates a {@link Pauser} that actively keeps the thread busy and does not employ any waiting strategies.
     *
     * @return a {@link Pauser} that never waits
     */
    @NotNull
    static Pauser busy() {
        SleepyWarning.warnSleepy();
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : BusyPauser.INSTANCE;
    }

    /**
     * Creates a {@link TimingPauser} that keeps the thread busy but also incorporates timed waits.
     *
     * @return a {@link TimingPauser} that combines busy and timed wait strategies
     */
    @NotNull
    static TimingPauser timedBusy() {
        return SLEEPY ? sleepy()
                : BALANCED ? balanced()
                : new BusyTimedPauser();
    }

    /**
     * Pauses the current thread.
     * <p>
     * The actual pause time and thread scheduling impact is not specified and depends
     * on the implementing class. For some implementations, a progressive increase
     * of the pause time is employed, thread executions may or may not be yielded, whereas
     * other implementations may not pause or yield at all.
     * <p>
     * Thus, depending on the implementation this could do nothing (busy spin), yield, sleep, ...
     * <p>
     * Call this if no work was done.
     */
    void pause();

    /**
     * Pauses "asynchronously" whereby the issuing EventHandler can
     * pause without blocking other handlers in the EventLoop.
     * <p>
     * The issuing EventHandler can check if it is still pausing
     * asynchronously by invoking {@link #asyncPausing()}. Typically, this is
     * done as depicted below:
     *
     * <pre>{@code
     *     // @Override
     *     public boolean action() throws InvalidEventHandlerException {
     *       if (pauser.asyncPausing()) {
     *           // Yield, so that other EventHandlers can run
     *           return false;
     *       }
     *     }
     * }</pre>
     *
     * @see #asyncPausing()
     */
    default void asyncPause() {
    }

    /**
     * Checks if the pauser is currently in an asynchronous pause state.
     *
     * @return {@code true} if the pauser is still pausing asynchronously, {@code false} otherwise
     */
    default boolean asyncPausing() {
        return false;
    }

    /**
     * Resets the pauser's internal state back (if any) to the most aggressive setting.
     * <p>
     * Pausers that progressively increases the pause time are reset back to its lowest
     * pause time.
     * <p>
     * Call this if you just did some work.
     */
    void reset();

    /**
     * use {@link TimingPauser#pause(long, TimeUnit)} instead
     */
    default void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException(this + " is not stateful, use a " + TimingPauser.class.getSimpleName());
    }

    /**
     * Try to cancel the pausing if it is pausing.
     * <p>
     * No guarantee is made that this call will actually have an effect.
     */
    void unpause();

    /**
     * Returns the paused time so far in milliseconds.
     *
     * @return the paused time so far in milliseconds
     */
    long timePaused();

    /**
     * Returns the number of times the pauser has checked for
     * completion.
     *
     * @return Returns the number of times the pauser has checked for
     * completion
     */
    long countPaused();

    /**
     * @return true if it doesn't really pause
     */
    default boolean isBusy() {
        return false;
    }

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

        static void warnSleepy() {
            // Do nothing here as run-once code is in the static block above.
        }
    }
}
