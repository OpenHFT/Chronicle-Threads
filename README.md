# Chronicle Threads

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.openhft/chronicle-threads/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.openhft/chronicle-threads)

This library provide a high performance thread pool.  This thread pool is design to share blocking, monitoring and busy waiting threads.  Busy waiting tasks can be prioritised for HIGH, MEDIUM, DAEMON (low priority) as well as TIMER (fixed rate events) tasks in a single thread without creating garbage.

