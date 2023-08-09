package net.openhft.chronicle.threads.internal;

public enum NanosecondDurationRenderer {
    ;

    /**
     * Render a nanosecond duration with an appropriate time unit
     *
     * @param durationInNanoseconds the duration in nanoseconds
     * @return A string describing it in seconds/milliseconds/microseconds as appropriate
     */
    public static String renderNanosecondDuration(long durationInNanoseconds) {
        if (durationInNanoseconds >= 1_000_000_000) {
            return nanosecondsToSeconds(durationInNanoseconds) + "s";
        }
        if (durationInNanoseconds >= 1_000_000) {
            return nanosecondsToMillis(durationInNanoseconds) + "ms";
        }
        return nanosecondsToMicros(durationInNanoseconds) + "us";
    }

    /**
     * Results in a double that retains only it's 1/10ths precision
     *
     * @param durationInNanoseconds The time in nanoseconds
     * @return The time in microseconds with tenths precision
     */
    @SuppressWarnings(/* we mean to do the integer division first */
            {"java:S2184", "IntegerDivisionInFloatingPointContext"})
    private static double nanosecondsToMicros(long durationInNanoseconds) {
        return (durationInNanoseconds / 100) / 10d;
    }

    /**
     * Results in a double that retains only it's 1/10ths precision
     *
     * @param durationInNanoseconds The time in nanoseconds
     * @return The time in milliseconds represented as a float with limited precision
     */
    @SuppressWarnings(/* we mean to do the integer division first */
            {"java:S2184", "IntegerDivisionInFloatingPointContext"})
    private static double nanosecondsToMillis(long durationInNanoseconds) {
        return (durationInNanoseconds / 100_000) / 10d;
    }

    /**
     * Results in a double that retains only it's 1/10ths precision
     *
     * @param durationInNanoseconds The time in nanoseconds
     * @return The time in seconds with tenths precision
     */
    @SuppressWarnings(/* we mean to do the integer division first */
            {"java:S2184", "IntegerDivisionInFloatingPointContext"})
    private static double nanosecondsToSeconds(long durationInNanoseconds) {
        return (durationInNanoseconds / 100_000_000) / 10d;
    }
}
