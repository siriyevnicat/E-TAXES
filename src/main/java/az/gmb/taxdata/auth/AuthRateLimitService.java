package az.gmb.taxdata.auth;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean allow(String action, String key, int max, long windowSeconds) {
        long now = Instant.now().getEpochSecond();
        String k = action + ":" + (key == null ? "unknown" : key);
        Bucket b = buckets.compute(k, (x, old) -> {
            if (old == null || now - old.windowStart >= windowSeconds) return new Bucket(now, 1);
            return new Bucket(old.windowStart, old.count + 1);
        });
        if (buckets.size() > 10_000) buckets.entrySet().removeIf(e -> now - e.getValue().windowStart > 3600);
        return b.count <= max;
    }

    private record Bucket(long windowStart, int count) {}
}
