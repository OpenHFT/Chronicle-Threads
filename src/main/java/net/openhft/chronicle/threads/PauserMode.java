/*
 * Copyright 2016-2020 Chronicle Software
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
 * Because {@link Pauser} is not an enum, and implementations are not Marshallable, this makes Pausers more yaml friendly
 *
 * <a href="https://github.com/OpenHFT/Chronicle-Threads#pauser-modes">Pauser Mode features</a>
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

        @Override
        public boolean isolcpus() {
            return true;
        }

        @Override
        public boolean monitor() {
            return false;
        }
    },
    milli {
        @Override
        public Pauser get() {
            return Pauser.millis(1);
        }
    },
    sleepy {
        @Override
        public Pauser get() {
            return Pauser.sleepy();
        }
    },
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
    yielding {
        @Override
        public Pauser get() {
            return Pauser.yielding();
        }
    };

    public boolean isolcpus() {
        return false;
    }

    public boolean monitor() {
        return true;
    }
}