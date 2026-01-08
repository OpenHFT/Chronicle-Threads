# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test

```bash
# Full verification (preferred)
mkdir -p logs
mvn verify -l logs/mvn-verify.log

# Run a single test class
mvn -Dtest=ClassName test -l logs/mvn-test.log

# Run a specific test method
mvn -Dtest=ClassName#methodName test -l logs/mvn-test.log

# Review build output for issues
rg -n '^\[(WARNING|ERROR)\]|SLF4J\(W\)|\bWARNING:|\bwarning:' logs/mvn-verify.log
```

Do not commit the `logs/` directory.

## Constraints

- **Java 8 baseline**: Avoid newer language features (no var, no records, no switch expressions).
- **ISO-8859-1 encoding**: Source files must stay ISO-8859-1 (code points 0-255). Prefer ASCII; avoid smart quotes and non-breaking spaces.
- **Binary compatibility**: The build enforces 100% binary compatibility with version 2.27ea0.
- **Public API stability**: Preserve public APIs unless explicitly requested.
- **Warnings as defects**: Treat compiler and runtime warnings as defects; keep logs clean.
- **Hot path performance**: Avoid extra allocations or synchronization on hot paths.
- **Non-blocking handlers**: Event handlers should avoid blocking and keep work chunks small; use pausers rather than sleeping.

## Architecture

Chronicle Threads provides high-performance event loop implementations for low-latency systems.

### Core Components

**EventGroup** (`src/main/java/.../EventGroup.java`): Main entry point that coordinates child event loops. Created via `EventGroupBuilder`. Routes handlers by priority:
- `MONITOR` -> MonitorEventLoop (observes latency, collects metrics)
- `HIGH/MEDIUM/TIMER/DAEMON` -> CoreEventLoop (fast path, latency-sensitive)
- `BLOCKING` -> BlockingEventLoop (dedicated threads for I/O waits)
- `REPLICATION/REPLICATION_TIMER` -> VanillaEventLoop (lazy, for replication)
- `CONCURRENT` -> Pool of VanillaEventLoops (lazy, round-robin assignment)

**Pausers** (`Pauser`, `TimingPauser`, `LongPauser`, etc.): Idle strategies controlling CPU vs latency trade-off:
- `busy()`/`timedBusy()`: Spin continuously, lowest latency, consumes full core
- `yielding()`: Brief busy then yield, low latency
- `balanced()`: Spin, yield, then sleep up to 20ms
- `sleepy()`: Minimal CPU, highest latency
- `millis(n)`: Fixed sleep interval

Pausers auto-downgrade to `balanced()` or `sleepy()` when the machine has fewer than 8 or 4 processors respectively.

**EventHandler**: Implement `action()` returning `true` if work was done. Throw `InvalidEventHandlerException.reusable()` to self-remove from the loop.

### Handler Execution Model

- Each event loop is single-threaded for handler execution (lock-free by design)
- Handlers execute serially within their loop
- Return value signals work done (affects pauser back-off)
- Keep `action()` execution time bounded; long work goes to BLOCKING priority

### Key System Properties

- `disableLoopBlockMonitor=true`: Disable performance monitoring
- `MONITOR_INTERVAL_MS`: Monitor sampling interval (default: 100)
- `eventGroup.conc.threads` or `CONC_THREADS`: Concurrent thread pool size (default: availableProcessors/4)
- `pauser.minProcessors`: Threshold for pauser downgrade (default: 4)

## Documentation

- Update `.adoc` files when behaviour changes
- Javadoc must add behavioural contracts, edge cases, thread safety, units, or performance notes
- Reference docs: `src/main/adoc/decision-log.adoc`, `src/main/docs/thread-*.adoc`
