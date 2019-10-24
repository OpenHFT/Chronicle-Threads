# Chronicle Threads

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.openhft/chronicle-threads/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.openhft/chronicle-threads)

## Thread pool

This library provide a high performance thread pool.  This thread pool is design to share blocking, monitoring and busy waiting threads.  Busy waiting tasks can be prioritised for HIGH, MEDIUM, DAEMON (low priority) as well as TIMER (fixed rate events) tasks in a single thread without creating garbage.

See `net.openhft.chronicle.core.threads.EventLoop` and `net.openhft.chronicle.threads.EventGroup`

## Pauser

Chronicle Threads provides a number of implementations of the `net.openhft.chronicle.threads.Pauser` interface.
The canonical way to make use of `Pauser` is below:

```java
    while (running) {}
        // pollForWork returns true if it does something, false if it does nothing
        if (pollForWork())
            pauser.reset();
        else
            pauser.pause();
    }
```

The various different implementations of `Pauser` allow for varied pausing strategies - see javadoc 
