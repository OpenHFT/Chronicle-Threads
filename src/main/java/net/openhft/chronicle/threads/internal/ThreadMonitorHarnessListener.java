package net.openhft.chronicle.threads.internal;

public interface ThreadMonitorHarnessListener {

    void blocked();

    ThreadMonitorHarnessListener NO_OP = new ThreadMonitorHarnessListener() {

        @Override
        public void blocked() {
            // Intentional no-op
        }

    };

}
