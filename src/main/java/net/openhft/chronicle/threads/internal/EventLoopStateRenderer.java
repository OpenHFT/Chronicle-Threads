/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.threads.AbstractLifecycleEventLoop;
import net.openhft.chronicle.threads.CoreEventLoop;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * This is a utility to render a verbose summary of the state of an {@link EventLoop}. Useful for debugging.
 */
public enum EventLoopStateRenderer {
    INSTANCE;

    public String render(String name, @Nullable EventLoop eventLoop) {
        if (eventLoop == null) {
            return name + " event loop is null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" event loop state\n");
        builder.append("#toString(): ").append(eventLoop).append('\n');
        builder.append("Closed: ").append(eventLoop.isClosed()).append('\n');
        builder.append("Closing: ").append(eventLoop.isClosing()).append('\n');
        addLifecycleDetails(builder, eventLoop);
        addCoreEventLoopDetails(builder, eventLoop);
        return builder.toString();
    }

    private void addCoreEventLoopDetails(StringBuilder builder, EventLoop eventLoop) {
        if (eventLoop instanceof CoreEventLoop) {
            Thread t = ((CoreEventLoop) eventLoop).thread();
            if (t != null) {
                builder.append("Thread state: ").append(t.getState()).append('\n');
                final StackTraceElement[] stackTrace = t.getStackTrace();
                if (stackTrace.length > 0) {
                    builder.append("Stack trace:");
                    Jvm.trimStackTrace(builder, stackTrace);
                }
            } else {
                builder.append("Thread is null\n");
            }
        }
    }

    private void addLifecycleDetails(StringBuilder builder, EventLoop eventLoop) {
        if (eventLoop instanceof AbstractLifecycleEventLoop) {
            try {
                // CSReflectiveFieldLookup REVIEW final Field lifecycle = Jvm.getField(eventLoop.getClass(), "lifecycle") because this reflective or runtime-loading boundary in EventLoopStateRenderer#addLifecycleDetails still needs either an allowlisted wrapper or an explicit reviewed runtime-loading contract.
                final Field lifecycle = Jvm.getField(eventLoop.getClass(), "lifecycle");
                builder.append("Lifecycle: ").append(lifecycle.get(eventLoop)).append('\n');
                // CSWarnAndContinue REVIEW catch (IllegalAccessException e) because the local fallback still begins with logging or printing a diagnostic and then continues execution, and needs either fail-closed handling or an explicit reviewed degraded-mode contract.
            } catch (IllegalAccessException e) {
                Jvm.warn().on(EventLoopStateRenderer.class, "Error getting the lifecycle for " + eventLoop.getClass().getName());
            }
        }
    }
}
