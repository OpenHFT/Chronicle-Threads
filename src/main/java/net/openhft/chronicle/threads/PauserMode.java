package net.openhft.chronicle.threads;

import java.util.function.Supplier;

/**
 * Because {@link Pauser} is not an enum, and implementations are not Marshallable, this makes Pausers more yaml friendly
 */
public enum PauserMode implements Supplier<Pauser> {
    balanced {
        @Override
        public Pauser get() {
            return Pauser.balanced();
        }
    },
    busy {
        @Override
        public Pauser get() {
            return Pauser.busy();
        }
    },
    yielding {
        @Override
        public Pauser get() {
            return Pauser.yielding();
        }
    },
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },
}
