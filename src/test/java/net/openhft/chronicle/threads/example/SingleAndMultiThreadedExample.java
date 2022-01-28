package net.openhft.chronicle.threads.example;

import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.MediumEventLoop;
import net.openhft.chronicle.threads.Pauser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

/**
 * An example that was used in a DZone article
 */
public class SingleAndMultiThreadedExample {

    private AtomicLong multiThreadedValue = new AtomicLong();
    private long singleThreadedValue;


    /**
     * The two examples in this code do the same thing, they both print the Fibonacci sequence, see https://en.wikipedia.org/wiki/Fibonacci
     * one is written using java threads and the other uses the Chronicle Event Loop.
     *
     * @param args
     */
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        SingleAndMultiThreadedExample example = new SingleAndMultiThreadedExample();

        // runs using java Executor - outputs 0 1 1 2 3 5 8 13 21 34
        example.multiThreadedExample();

        // using the chronicle event loop
        example.eventLoopExample();

    }

    private Void addOneHundred() {
        for (int i = 0; i < 100; i++) {
            multiThreadedValue.incrementAndGet();
        }
        return null;
    }

    private void multiThreadedExample() throws ExecutionException, InterruptedException {

        // example using Java Threads
        final ExecutorService executorService = newCachedThreadPool();
        Future<?> f1 = executorService.submit(this::addOneHundred);
        Future<?> f2 = executorService.submit(this::addOneHundred);
        Future<?> f3 = executorService.submit(this::addOneHundred);
        Future<?> f4 = executorService.submit(this::addOneHundred);
        Future<?> f5 = executorService.submit(this::addOneHundred);

        f1.get();
        f2.get();
        f3.get();
        f4.get();
        f5.get();
        System.out.println("multiThreadedValue=" + multiThreadedValue);
    }

    private void eventLoopExample() throws InterruptedException {
        final EventLoop eventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
        eventLoop.start();
        CountDownLatch finished = new CountDownLatch(1);
        eventLoop.addHandler(() -> {

            singleThreadedValue++;
            // we throw this to un-register the event loop

            if (singleThreadedValue == 500) {
                finished.countDown();
                throw new InvalidEventHandlerException("finished");
            }

            // return false if you don't want to be called back for a while
            return true;
        });

        finished.await();
        System.out.println("eventLoopExample=" + singleThreadedValue);
    }


}

