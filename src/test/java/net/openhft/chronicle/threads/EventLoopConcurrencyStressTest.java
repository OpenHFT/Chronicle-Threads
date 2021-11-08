package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class EventLoopConcurrencyStressTest extends ThreadsTestCommon {

    private static final int NUM_EVENT_ADDERS = 2;
    private static final int TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS = 2;
    private static int originalMonitorDelay = -1;

    @BeforeAll
    static void beforeAll() {
        originalMonitorDelay = MonitorEventLoop.MONITOR_INITIAL_DELAY_MS;
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = 0;
    }

    @AfterAll
    static void afterAll() {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = originalMonitorDelay;
    }

    @TestFactory
    public Stream<DynamicTest> concurrentTestsForEachEventLoop() {
        Map<String, Supplier<AbstractLifecycleEventLoop>> eventLoopSuppliers = new HashMap<>();
        eventLoopSuppliers.put("BlockingEventLoop", () -> new BlockingEventLoop("blockingEventLoop"));
        eventLoopSuppliers.put("MediumEventLoop", () -> new MediumEventLoop(null, "mediumEventLoop", Pauser.balanced(), true, null));
        eventLoopSuppliers.put("VanillaEventLoop", () -> new VanillaEventLoop(null, "vanillaEventLoop", Pauser.balanced(), 1, true, null, VanillaEventLoop.ALLOWED_PRIORITIES));
        eventLoopSuppliers.put("MonitorEventLoop", () -> new MonitorEventLoop(null, "monitorEventLoop", Pauser.balanced()));
        return eventLoopSuppliers.entrySet().stream().flatMap(els -> Stream.of(
                DynamicTest.dynamicTest("canConcurrentlyAddHandlersAndStartEventLoop: " + els.getKey(), () -> canConcurrentlyAddHandlersAndStartEventLoop(els.getValue())),
                DynamicTest.dynamicTest("canConcurrentlyAddHandlersAndStopEventLoop: " + els.getKey(), () -> canConcurrentlyAddHandlersAndStopEventLoop(els.getValue())),
                DynamicTest.dynamicTest("canConcurrentlyAddTerminatingHandlersAndStartEventLoop: " + els.getKey(), () -> canConcurrentlyAddTerminatingHandlersAndStartEventLoop(els.getValue())),
                DynamicTest.dynamicTest("canConcurrentlyAddTerminatingHandlersAndStopEventLoop: " + els.getKey(), () -> canConcurrentlyAddTerminatingHandlersAndStartEventLoop(els.getValue()))
        ));
    }

    public void canConcurrentlyAddHandlersAndStartEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier) throws TimeoutException {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStarter eventLoopStarter = new EventLoopStarter(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStarter);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, ControllableHandler::new, 100);
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            // wait until the starter has started the event loop
            eventLoopStarter.waitUntilEventLoopStarted();
            // Stop adding new handlers
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All added handlers should eventually start
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStarted)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
            // Stop all handlers
            handlerAdders.forEach(HandlerAdder::stopAllHandlers);
            // All handlers should eventually stop
            pauser.reset();
            pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddHandlersAndStopEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier) throws TimeoutException {
        ignoreException("Handler in newHandler was not accepted before");
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            eventLoop.start();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStopper eventLoopStopper = new EventLoopStopper(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStopper);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, ControllableHandler::new, 100);
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            eventLoopStopper.waitUntilEventLoopStopped();
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddTerminatingHandlersAndStartEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier) throws TimeoutException {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStarter eventLoopStarter = new EventLoopStarter(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStarter);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(0), 100);
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            // wait until the starter has started the event loop
            eventLoopStarter.waitUntilEventLoopStarted();
            // Stop adding new handlers
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddTerminatingHandlersAndStopEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier) throws TimeoutException {
        ignoreException("Handler in newHandler was not accepted before");
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            eventLoop.start();
            while (!eventLoop.isStarted()) {
                Jvm.pause(1);
            }
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStopper eventLoopStopper = new EventLoopStopper(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStopper);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(0), 100);
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            eventLoopStopper.waitUntilEventLoopStopped();
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    static class EventLoopStarter implements Runnable {
        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Semaphore hasStartedEventLoop;

        EventLoopStarter(EventLoop eventLoop, CyclicBarrier cyclicBarrier) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            hasStartedEventLoop = new Semaphore(0);
        }

        public void run() {
            try {
                await(cyclicBarrier);
                Jvm.pause(TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS);
                eventLoop.start();
                hasStartedEventLoop.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void waitUntilEventLoopStarted() {
            try {
                hasStartedEventLoop.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class EventLoopStopper implements Runnable {
        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Semaphore hasStoppedEventLoop;

        EventLoopStopper(EventLoop eventLoop, CyclicBarrier cyclicBarrier) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            hasStoppedEventLoop = new Semaphore(0);
        }

        public void run() {
            try {
                await(cyclicBarrier);
                Jvm.pause(TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS);
                eventLoop.stop();
                hasStoppedEventLoop.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void waitUntilEventLoopStopped() {
            try {
                hasStoppedEventLoop.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class HandlerAdder implements Runnable {

        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Supplier<ControllableHandler> handlerSupplier;
        private final int maxHandlersToAdd;
        private final List<ControllableHandler> addedHandlers;
        private volatile boolean stopAddingHandlers = false;
        private final Semaphore stoppedAddingHandlers;

        HandlerAdder(EventLoop eventLoop, CyclicBarrier cyclicBarrier, Supplier<ControllableHandler> handlerSupplier, int maxHandlersToAdd) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            this.handlerSupplier = handlerSupplier;
            this.maxHandlersToAdd = maxHandlersToAdd;
            this.addedHandlers = new ArrayList<>();
            this.stoppedAddingHandlers = new Semaphore(0);
        }

        @Override
        public void run() {
            try {
                await(cyclicBarrier);
                while (!stopAddingHandlers && addedHandlers.size() < maxHandlersToAdd) {
                    ControllableHandler handler = handlerSupplier.get();
                    eventLoop.addHandler(handler);
                    addedHandlers.add(handler);
                    pauseMicros(ThreadLocalRandom.current().nextInt(100, 300));
                }
                stoppedAddingHandlers.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void stopAddingHandlers() {
            stopAddingHandlers = true;
            try {
                stoppedAddingHandlers.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void stopAllHandlers() {
            addedHandlers.forEach(ControllableHandler::stop);
        }

        public boolean allHandlersAreStarted() {
            return addedHandlers.stream().allMatch(ControllableHandler::isRunning);
        }


        public boolean allHandlersAreStopped() {
            return addedHandlers.stream().allMatch(ControllableHandler::isComplete);
        }
    }

    static class ControllableHandler implements AutoCloseable, EventHandler {

        final int endOnIteration;
        int iteration = 0;
        boolean loopFinished = false;
        boolean loopStarted = false;
        boolean closed = false;
        volatile boolean exitOnNextIteration = false;

        public ControllableHandler() {
            this(-1);
        }

        @Override
        public void close() {
            closed = true;
        }

        public ControllableHandler(int endOnIteration) {
            this.endOnIteration = endOnIteration;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (closed) {
                throw InvalidEventHandlerException.reusable();
            } else if (exitOnNextIteration) {
                throw InvalidEventHandlerException.reusable();
            } else if (endOnIteration >= 0 && iteration >= endOnIteration) {
                throw InvalidEventHandlerException.reusable();
            }
            Jvm.pause(ThreadLocalRandom.current().nextInt(10));
            iteration++;
            return iteration % 3 == 0; // need to be not busy sometime to allow for adding new handlers
        }

        @Override
        public void loopStarted() {
            this.loopStarted = true;
        }

        @Override
        public void loopFinished() {
            this.loopFinished = true;
        }

        public void stop() {
            exitOnNextIteration = true;
        }

        public boolean isRunning() {
            return loopStarted && !loopFinished;
        }

        public boolean isComplete() {
            return !loopStarted || loopFinished;
        }
    }

    private static void await(CyclicBarrier cyclicBarrier) {
        try {
            cyclicBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    private static void pauseMicros(long timeToSleepMicros) {
        long endTimeNanos = System.nanoTime() + timeToSleepMicros * 1_000;
        while (System.nanoTime() < endTimeNanos) {
            // do nothing
        }
    }
}
