/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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
 *
 * Because {@link Pauser} is not an enum, and implementations are not Marshallable, this makes Pausers more yaml friendly
 *
 * Here is a list of the different <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>
 */
public enum PauserMode implements Supplier<Pauser> {

    /**
     * Returns Suppliers providing pausers performing busy waiting (spin-wait at 100% CPU) for short
     * periods and then backs off when idle for longer periods, if there are sufficient available processors.
     * Otherwise, returns Supplers consistent with {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * Balanced pausers is trying to balance latency and required CPU resources.
     * <p>
     * The various Pauser modes and their properties can be seen here:
     * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>
     *
     * @see Runtime#availableProcessors()
     *
     */
    balanced {
        @Override
        public Pauser get() {
            return Pauser.balanced();
        }
    },
    /**
     * Returns Suppliers providing pausers performing busy waiting (spin-wait at 100% CPU)
     * if there are sufficient available processors. Otherwise, returns Supplers consistent with
     * {@link #balanced } or even {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * Busy pausers have the lowest latency times but requires the most CPU resources.
     * <p>
     * The various Pauser modes and their properties can be seen here:
     * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>

     * @see Runtime#availableProcessors()
     *
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
     * Returns Suppliers providing pausers that sleeps for one millisecond when backing off.
     * <p>
     * Milli pausers have long latency times but but requires minimum CPU resources.
     * <p>
     * The various Pauser modes and their properties can be seen here:
     * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>

     * @see Runtime#availableProcessors()
     *
     */
    milli {
        @Override
        public Pauser get() {
            return Pauser.millis(1);
        }
    },
    /**
     * Returns Suppliers providing pausers hat backs off when idle.
     * <p>
     * Sleepy pausers have fair latency and requires limited CPU resources.
     * <p>
     * The various Pauser modes and their properties can be seen here:
     * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>
     *
     * @see Runtime#availableProcessors()
     *
     */
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },

    /**
     * Returns Suppliers similar to {@link #busy} but that provides pausers that supports
     * timed pauser.
     *
     * @see #busy
     *
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
     * Returns Suppliers providing pausers that is very briefly busy waiting (spin-wait at 100% CPU) and
     * then yields execution, if there are sufficient available processors. Otherwise, returns Supplers consistent with
     * {@link #balanced } or even {@link #sleepy} depending on the system property "pauser.minProcessors".
     * <p>
     * Yelding pausers have low latency times but may require significant CPU resources.
     * <p>
     * The various Pauser modes and their properties can be seen here:
     * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>

     * @see Runtime#availableProcessors()
     *
     */
    yielding {
        @Override
        public Pauser get() {
            return Pauser.yielding();
        }
    };

    /**
     * Returns if provided Pausers supports CPU isolation.
     *
     * @return if provided Pausers supports CPU isolation
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