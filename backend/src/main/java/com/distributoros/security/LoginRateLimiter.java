package com.distributoros.security;

import com.distributoros.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter per (ip, email). Single-replica scope; put Front Door/WAF in front for global limits. */
@Component
public class LoginRateLimiter {
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final int limit;

    public LoginRateLimiter(AppProperties props) {
        this.limit = Math.max(1, props.loginRateLimitPer5min());
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }

    public void reset(String key) { hits.remove(key); }

    @Scheduled(fixedDelay = 60_000)
    void evict() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || now - last > WINDOW_MS;
            }
        });
    }
}
