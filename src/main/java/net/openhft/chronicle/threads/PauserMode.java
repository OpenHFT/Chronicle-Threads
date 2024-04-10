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
 * This class contains factory methods for Pausers.
 * <p>
 * Because {@link Pauser} is not an enum, and implementations are not Marshallable, this makes Pausers more yaml friendly
 * <p>
 * The various Pauser modes and their properties can be seen here:
 * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>
 */
public enum PauserMode implements Supplier<Pauser> {

    /**
     * Returns a Supplier providing pausers that will busy wait (spin-wait at 100% CPU) for short
     * periods and then backs off when idle for longer periods.
     * If there are not sufficient available processors, returns {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * See {@link Pauser#balanced()}
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
     * See {@link Pauser#busy()}
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
     * Returns a Supplier providing pausers that sleeps for one millisecond with no back off.
     * <p>
     * Milli pausers have long latency times but require minimum CPU resources.
     * <p>
     * See {@link Pauser#millis(int)}
     */
    milli {
        @Override
        public Pauser get() {
            return Pauser.millis(1);
        }
    },
    /**
     * Returns a Supplier providing back off pausers that are less aggressive than {@link #balanced}.
     * <p>
     * Sleepy pausers have relatively high latency but require limited CPU resources.
     * <p>
     * See {@link Pauser#sleepy()}
     */
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },

    /**
     * Returns a Supplier similar to {@link #busy} but that provides pausers that supports
     * {@link TimingPauser}.
     * <p>
     * See {@link Pauser#timedBusy()}
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
     * Returns a Supplier providing pausers that yields execution (see {@link Thread#yield()}, if there are sufficient
     * available processors. Otherwise, returns a Supplier consistent with
     * {@link #balanced } or even {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * See {@link Pauser#yielding()}
     */
    yielding {
        @Override
        public Pauser get() {
            return Pauser.yielding();
        }
    };

    /**
     * Returns if provided Pausers requires CPU isolation.
     *
     * @return if provided Pausers requires CPU isolation
     */
    public boolean isolcpus() {
        return false;
    }

    /**
     * Returns if provided Pausers can be monitored.
     *
     * @return if provided Pausers can be monitored
     */
    public boolean monitor() {
        return true;
    }
}