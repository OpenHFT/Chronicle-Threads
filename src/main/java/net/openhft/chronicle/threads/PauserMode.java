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

import java.util.function.Supplier;

/**
 * Provides factory methods for creating various types of {@link Pauser} objects.
 * This enum facilitates the creation of different pausing strategies that control thread execution based on CPU availability and desired pausing characteristics.
 *
 * <p>Because {@link Pauser} is not an enum, and implementations are not Marshallable, using this enum helps in making configurations more YAML-friendly.</p>
 *
 * <p>For detailed descriptions of the different Pauser modes and their specific properties, see:
 * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a></p>
 */
public enum PauserMode implements Supplier<Pauser> {

    /**
     * Provides a {@link Pauser} that busy-waits (spins at 100% CPU) for short durations
     * and then backs off when idle for longer periods.
     * If there are not sufficient available processors, returns {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * This strategy is ideal for balancing responsiveness with CPU consumption.
     *
     * @see Pauser#balanced()
     * @see Runtime#availableProcessors()
     */
    balanced {
        @Override
        public Pauser get() {
            return Pauser.balanced();
        }
    },

    /**
     * Returns a Supplier providing pausers this will busy wait (spin-wait at 100% CPU)
     * if there are sufficient available processors. Otherwise, returns Supplier consistent with
     * {@link #balanced } or even {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * This pauser is designed for scenarios requiring high responsiveness at the cost of higher CPU usage.
     *
     * @see Pauser#busy()
     * @see Runtime#availableProcessors()
     */
    busy {
        @Override
        public Pauser get() {
            return Pauser.busy();
        }

        @Override
        public boolean isolcpus() {
            return true;
        }

        @Override
        public boolean monitor() {
            return false;
        }
    },

    /**
     * Provides a {@link Pauser} that sleeps for one millisecond consistently, without any backing off.
     * <p>
     * Milli pausers have long latency times but require minimum CPU resources.
     *
     * @see Pauser#millis(int)
     */
    milli {
        @Override
        public Pauser get() {
            return Pauser.millis(1);
        }
    },

    /**
     * Provides a {@link Pauser} that is less aggressive than {@link #balanced}, using sleep intervals to conserve CPU resources.
     * <p>
     * Suitable for lower-priority tasks where response time is less critical.
     *
     * @see Pauser#sleepy()
     */
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },

    /**
     * Similar to {@link #busy} but provides a {@link Pauser} supporting timed waits.
     * <p>
     * This pauser combines busy-waiting with timed pauses to optimize CPU usage during variable workload conditions.
     *
     * @see Pauser#timedBusy()
     */
    timedBusy {
        @Override
        public Pauser get() {
            return Pauser.timedBusy();
        }

        @Override
        public boolean isolcpus() {
            return true;
        }
    },
    /**
     * Provides a {@link Pauser} that yields thread execution if there are sufficient processors, otherwise it falls back to {@link #balanced} or {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * It is designed to maintain responsiveness without consuming excessive CPU resources in systems with sufficient processing power.
     *
     * @see Pauser#yielding()
     */
    yielding {
        @Override
        public Pauser get() {
            return Pauser.yielding();
        }
    };

    /**
     * Indicates whether the provided {@link Pauser} is suitable for CPU isolation.
     *
     * @return {@code true} if CPU isolation is suitable, otherwise {@code false}
     */
    public boolean isolcpus() {
        return false;
    }

    /**
     * Indicates whether the provided {@link Pauser} can be monitored.
     *
     * @return {@code true} if the pauser can be monitored, otherwise {@code false}
     */
    public boolean monitor() {
        return true;
    }
}
