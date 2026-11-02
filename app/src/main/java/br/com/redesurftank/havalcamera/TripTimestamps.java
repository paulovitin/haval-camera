package br.com.redesurftank.havalcamera;

/** Pure validation for timestamps received across the app boundary. */
final class TripTimestamps {
    private static final long FUTURE_TOLERANCE_MS = 300_000;

    static long requireStart(boolean present, long value, long nowMs) {
        if (!present) throw new IllegalArgumentException("Missing start timestamp");
        if (value < 0 || value > nowMs + FUTURE_TOLERANCE_MS)
            throw new IllegalArgumentException("Invalid start timestamp");
        return value;
    }

    static long requireEnd(boolean present, long value, long startMs, long nowMs) {
        if (!present) throw new IllegalArgumentException("Missing end timestamp");
        if (value < startMs || value > nowMs + FUTURE_TOLERANCE_MS)
            throw new IllegalArgumentException("Invalid end timestamp");
        return value;
    }

    private TripTimestamps() { }
}
