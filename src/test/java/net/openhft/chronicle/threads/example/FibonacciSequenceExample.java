package net.openhft.chronicle.threads.example;

import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.MediumEventLoop;
import net.openhft.chronicle.threads.Pauser;

import java.util.EnumSet;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class FibonacciSequenceExample {

    public static final EnumSet<HandlerPriority> MEDIUM = EnumSet.of(HandlerPriority.MEDIUM);
    private int count = 10;
    private int n1 = 0;
    private int n2 = 1;
    private int n3;
    private int i = 1;

    /**
     * The two examples in this code do the same thing, they both print the Fibonacci sequence, see https://en.wikipedia.org/wiki/Fibonacci
     * one is written using java threads and the other uses the Chronicle Event Loop.
     *
     * @param args
     */
    public static void main(String[] args) {
        FibonacciSequenceExample example = new FibonacciSequenceExample();

        // runs using java Executor - outputs 0 1 1 2 3 5 8 13 21 34
        example.javaExample();

        // using the chronicle event loop
        example.eventLoopExample();

    }

    private void eventLoopExample() {
        final EventLoop eventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
        eventLoop.start();
        eventLoop.addHandler(() -> {
            if (i++ == 1) {
                System.out.print(n1 + " " + n2);
                return true;
            }

            n3 = n1 + n2;
            System.out.print(" " + n3);
            n1 = n2;
            n2 = n3;

            // we throw this to un-register the event loop
            if (i == count) throw new InvalidEventHandlerException("finished");

            // return false if you don't want to be called back for a while
            return true;
        });
    }

    private void javaExample() {
        // example using Java Threads
        newSingleThreadExecutor().submit(() -> {
            int n1 = 0, n2 = 1, n3, i, count = 10;

            //printing 0 and 1
            System.out.print(n1 + " " + n2);

            //loop starts from 2 because 0 and 1 are already printed
            for (i = 2; i < count; ++i) {
                n3 = n1 + n2;
                System.out.print(" " + n3);
                n1 = n2;
                n2 = n3;
            }
        });
    }
}

