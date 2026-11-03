package club.muimi.kilotto;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T00:00:00Z"));
    void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
}
