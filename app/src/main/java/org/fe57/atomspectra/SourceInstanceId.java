package org.fe57.atomspectra;

import java.util.concurrent.atomic.AtomicInteger;

/** Hands out the unique ids sources put on their reply intents. */
final class SourceInstanceId {
    private static final AtomicInteger counter = new AtomicInteger();

    private SourceInstanceId() {
    }

    static int next() {
        return counter.incrementAndGet();
    }
}
