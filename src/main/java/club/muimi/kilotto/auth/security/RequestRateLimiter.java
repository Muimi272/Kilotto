package club.muimi.kilotto.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded token buckets; exhausting the key capacity fails closed instead of evicting active limits. */
@Component
@RequiredArgsConstructor
public class RequestRateLimiter {
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public synchronized boolean allow(String key, int perMinute) {
        long now = clock.millis();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= 10_000) {
                buckets.values().removeIf(value -> now - value.updatedAt >= 120_000);
                if (buckets.size() >= 10_000) return false;
            }
            bucket = new Bucket(perMinute, now);
            buckets.put(key, bucket);
        }
        bucket.tokens = Math.min(perMinute, bucket.tokens + Math.max(0, now - bucket.updatedAt) * perMinute / 60_000.0);
        bucket.updatedAt = now;
        if (bucket.tokens < 1) return false;
        bucket.tokens--;
        return true;
    }

    private static final class Bucket {
        double tokens;
        long updatedAt;
        Bucket(double tokens, long updatedAt) { this.tokens = tokens; this.updatedAt = updatedAt; }
    }
}
