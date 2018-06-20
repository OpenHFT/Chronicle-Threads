/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
 * Created by peter.lawrey on 11/12/14.
 */
public interface Pauser {
    int MIN_PROCESSORS = Integer.getInteger("pauser.minProcessors", 8);
    boolean SLEEPY = getSleepy();

    static boolean getSleepy() {
        int procs = Runtime.getRuntime().availableProcessors();
        if (procs < MIN_PROCESSORS) {
            Jvm.warn().on(Pauser.class, "Using Pauser.sleepy() as not enough processors, have " + procs + ", needs " + MIN_PROCESSORS + "+");
            return true;
        }
        return false;
    }

    static TimingPauser sleepy() {
        return new LongPauser(0, 100, 500, 20_000, TimeUnit.MICROSECONDS);
    }

    /**
     * A balanced pauser which tries to be busy for short busrts but backs off when idle.
     *
     * @return a balanced pauser
     */
    static Pauser balanced() {
        return balancedUpToMillis(20);
    }

    /**
     * A balanced pauser which tries to be busy for short busrts but backs off when idle.
     *
     * @param millis maximum millis (unless in debug mode)
     * @return a balanced pauser
     */
    static Pauser balancedUpToMillis(int millis) {
        return SLEEPY ? sleepy() : new LongPauser(20000, 250, 50, (Jvm.isDebug() ? 200_000 : 0) + millis * 1_000, TimeUnit.MICROSECONDS);
    }

    /**
     * Wait a fixed time befoe running again unless woken
     *
     * @param millis to wait for
     * @return a waiting pauser
     */
    static MilliPauser millis(int millis) {
        return new MilliPauser(millis);
    }

    /**
     * A balanced pauser which tries to be busy for short busrts but backs off when idle.
     *
     * @param minMillis starting millis
     * @param maxMillis maximum millis
     * @return a balanced pauser
     */
    static Pauser millis(int minMillis, int maxMillis) {
        return new LongPauser(0, 0, minMillis, maxMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Yielding pauser. simpler than LongPauser but slightly more friendly to other processes
     */
    static Pauser yielding() {
        return yielding(2);
    }

    static Pauser yielding(int minBusy) {
        return SLEEPY ? sleepy() : new YieldingPauser(minBusy);
    }

    /**
     * A busy pauser which never waits
     *
     * @return a busy/non pauser
     */
    @NotNull
    static Pauser busy() {
        return SLEEPY ? sleepy() : BusyPauser.INSTANCE;
    }

    @NotNull
    static TimingPauser timedBusy() {
        return SLEEPY ? sleepy() : new BusyTimedPauser();
    }

    void reset();

    void pause();

    @Deprecated // use TimingPauser.pause instead
    default void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException(this + " is not stateful, use a TimingPauser");
    }

    void unpause();

    long timePaused();

    long countPaused();
}
