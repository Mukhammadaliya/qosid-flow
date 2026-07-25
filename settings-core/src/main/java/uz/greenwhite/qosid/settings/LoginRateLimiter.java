package uz.greenwhite.qosid.settings;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory login rate limiter (one instance). 5 failures / 60s window per key. Plain POJO — wired as a @Bean by each JVM. */
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 60_000;

    private record Attempt(int count, long windowStart) {}

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String key) {
        Attempt a = attempts.get(key);
        if (a == null) return;
        long now = Instant.now().toEpochMilli();
        if (now - a.windowStart() < WINDOW_MS && a.count() >= MAX_FAILURES) {
            throw new TooManyAttemptsException("Too many login attempts, try again later");
        }
    }

    public void recordFailure(String key) {
        long now = Instant.now().toEpochMilli();
        attempts.entrySet().removeIf(e -> now - e.getValue().windowStart() >= WINDOW_MS);
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStart() >= WINDOW_MS) return new Attempt(1, now);
            return new Attempt(a.count() + 1, a.windowStart());
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String m) { super(m); }
    }
}
