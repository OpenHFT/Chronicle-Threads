/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;


import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandlerDispatchTest extends ThreadsTestCommon {
    private static class Probe extends VanillaEventLoop {
        boolean removeUnexpected;
        Probe() { super(null, "dispatch-probe", Pauser.balanced(), 1, true, null, ALLOWED_PRIORITIES); }
        void timer() { runTimerHandlers(); }
        void daemon() { runDaemonHandlers(); }
        void begin() { loopStartedAllHandlers(); }
        @Override protected boolean handle(EventLoop loop, EventHandler handler, Throwable failure) {
            return removeUnexpected || super.handle(loop, handler, failure);
        }
    }
    private static class Handler implements EventHandler, Closeable {
        final HandlerPriority priority; int calls, closes, finishes;
        boolean invalid, failStart, failFinish, failClose, failAction;
        Handler(HandlerPriority priority) { this.priority = priority; }
        @Override public HandlerPriority priority() { return priority; }
        @Override public boolean action() throws InvalidEventHandlerException {
            calls++; if (invalid) throw InvalidEventHandlerException.reusable();
            if (failAction) throw new IllegalStateException("action probe"); return false;
        }
        @Override public void loopStarted() { if (failStart) throw new IllegalStateException("start probe"); }
        @Override public void loopFinished() { finishes++; if (failFinish) throw new IllegalStateException("finish probe"); }
        @Override public void close() { closes++; if (failClose) throw new IllegalStateException("close probe"); }
        @Override public boolean isClosed() { return closes != 0; }
    }
    @Test void timerRemovalDoesNotSkipFollowingHandler() {
        try (Probe p = new Probe()) {
            Handler first = new Handler(HandlerPriority.TIMER), next = new Handler(HandlerPriority.TIMER); first.invalid = true;
            p.addHandler(first); p.addHandler(next); p.timer();
            assertEquals(1, first.closes); assertEquals(1, next.calls);
        }
    }
    @Test void daemonRemovalDoesNotSkipFollowingHandler() {
        try (Probe p = new Probe()) {
            Handler first = new Handler(HandlerPriority.DAEMON), next = new Handler(HandlerPriority.DAEMON); first.invalid = true;
            p.addHandler(first); p.addHandler(next); p.daemon(); assertEquals(1, next.calls);
        }
    }
    @Test void consecutiveInvalidTimersAreBothClosedInOnePass() {
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.TIMER), b = new Handler(HandlerPriority.TIMER); a.invalid = b.invalid = true;
            p.addHandler(a); p.addHandler(b); p.timer(); assertEquals(1, a.closes); assertEquals(1, b.closes);
        }
    }
    @Test void failedTimerStartClosesOnlyRejectedHandler() {
        expectException("EventHandler::loopStarted exception");
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.TIMER), b = new Handler(HandlerPriority.TIMER); a.failStart = true;
            p.addHandler(a); p.addHandler(b); p.begin(); p.timer(); assertEquals(1, a.closes); assertEquals(0, a.calls); assertEquals(1, b.calls);
        }
    }
    @Test void failedFinishStillClosesInvalidTimer() {
        expectException("finish probe");
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.TIMER); a.invalid = a.failFinish = true;
            p.addHandler(a); p.timer(); assertEquals(1, a.closes); assertEquals(1, a.finishes);
        }
    }
    @Test void failedCloseDoesNotPreventFollowingTimerDispatch() {
        expectException("Error occurred closing resources");
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.TIMER), b = new Handler(HandlerPriority.TIMER); a.invalid = a.failClose = true;
            p.addHandler(a); p.addHandler(b); p.timer(); assertEquals(1, b.calls);
        }
    }
    @Test void duplicateTimerRegistrationDoesNotDoubleClose() {
        Handler a = new Handler(HandlerPriority.TIMER);
        try (Probe p = new Probe()) { p.addHandler(a); p.addHandler(a); p.timer(); assertEquals(1, a.calls); }
        assertEquals(1, a.closes);
    }
    @Test void timerActionFailureDoesNotPreventFollowingDispatch() {
        expectException("Exception thrown by handler");
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.TIMER), b = new Handler(HandlerPriority.TIMER); a.failAction = true;
            p.addHandler(a); p.addHandler(b); p.timer(); assertEquals(1, b.calls); assertEquals(0, a.closes);
        }
    }
    @Test void daemonActionFailureDoesNotPreventFollowingDispatch() {
        expectException("Exception thrown by handler");
        try (Probe p = new Probe()) {
            Handler a = new Handler(HandlerPriority.DAEMON), b = new Handler(HandlerPriority.DAEMON); a.failAction = true;
            p.addHandler(a); p.addHandler(b); p.daemon(); assertEquals(1, b.calls);
        }
    }
    @Test void closeFailureDoesNotAbandonOtherPriorityLists() {
        expectException("Error occurred closing resources");
        Handler a = new Handler(HandlerPriority.DAEMON), b = new Handler(HandlerPriority.TIMER); a.failClose = true;
        try (Probe p = new Probe()) { p.addHandler(a); p.addHandler(b); }
        assertEquals(1, b.closes);
    }
    @Test void customRemovalPolicyDoesNotSkipFollowingTimer() {
        try (Probe p = new Probe()) {
            p.removeUnexpected = true;
            Handler a = new Handler(HandlerPriority.TIMER), b = new Handler(HandlerPriority.TIMER);
            a.failAction = true;
            p.addHandler(a);
            p.addHandler(b);
            p.timer();
            assertEquals(1, a.closes);
            assertEquals(1, b.calls);
        }
    }
}
