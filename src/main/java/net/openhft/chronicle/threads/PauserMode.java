//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//
package net.openhft.chronicle.threads;

import java.util.function.Supplier;

/**
 * Enumerates the built-in pausing strategies provided by {@link Pauser}.
 *
 * <p>{@code Pauser} implementations are not {@code enum}s and cannot easily be
 * referred to from configuration files.  {@code PauserMode} gives each common
 * strategy a serialisable name so that YAML and similar configuration formats
 * can specify the desired pauser.</p>
 *
 * <p>The README contains a table under the "PauserMode" section that summarises
 * the latency and CPU characteristics for each mode.</p>
 */
public enum PauserMode implements Supplier<Pauser> {

    /**
     * Busy waits for a short time before yielding and eventually sleeping.
     * Latency is moderate but CPU use is reduced compared to {@link #busy}.
     * Typical choice for event loops dealing with bursty traffic.
     * Can be monitored and does not need CPU isolation.
     */
    balanced {
        @Override
        public Pauser get() {
            return Pauser.balanced();
        }
    },

    /**
     * Continuously busy spins to minimise jitter and give the lowest latency.
     * Best used when a dedicated core is available.
     * Not monitorable and prefers CPU isolation.
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
     * Always sleeps for roughly one millisecond and never busy waits.
     * Latency can be around one millisecond but CPU usage is very low.
     * Useful for low priority polling where jitter is acceptable.
     */
    milli {
        @Override
        public Pauser get() {
            return Pauser.millis(1);
        }
    },

    /**
     * Less aggressive than {@link #balanced}; mainly sleeps to conserve CPU.
     * Offers high jitter and therefore suits background or diagnostic work.
     */
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },

    /**
     * Behaves like {@link #busy} but also supports timeout based pauses.
     * Maintains minimal jitter while allowing a time limit to be enforced.
     * Not monitorable and prefers CPU isolation.
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

        @Override
        public boolean monitor() {
            return false;
        }
    },
    /**
     * Briefly busy spins then yields the CPU.
     * Latency is low and the pauser can be shared between threads.
     * Suitable when threads share CPUs but responsiveness is still important.
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
