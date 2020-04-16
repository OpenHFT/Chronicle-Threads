package net.openhft.chronicle.threads.internal;

public enum EventLoopUtil {;
    private static final int DEFAULT_ACCEPT_HANDLER_MOD_COUNT = 128;
    public static final int ACCEPT_HANDLER_MOD_COUNT = Integer.getInteger("eventloop.accept.mod", DEFAULT_ACCEPT_HANDLER_MOD_COUNT);
    public static final boolean IS_ACCEPT_HANDLER_MOD_COUNT = ACCEPT_HANDLER_MOD_COUNT > 0;
}


