package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadsTest extends ThreadsTestCommon {

    @Test
    public void shouldDumpStackTracesForStuckDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.newSingleThreadExecutor(new NamedThreadFactory("non-daemon-test"));
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdown(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
    }

    @Test
    public void shouldDumpStackTracesForStuckDaemonDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.newSingleThreadExecutor(new NamedThreadFactory("daemon-test"));
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdownDaemon(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/daemon-test THREAD DID NOT SHUTDOWN ***");
    }

    @Test
    public void shouldDumpStackTracesForStuckNestedDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.unconfigurableExecutorService(
                Executors.unconfigurableExecutorService(
                        Executors.unconfigurableExecutorService(
                                Executors.newSingleThreadExecutor(new NamedThreadFactory("non-daemon-test"))
                        )
                )
        );
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdown(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
    }
}
