package com.sdp.ci.p11quorum;

import com.sdp.ci.common.Problems;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Dynamo-style coordinator: fan out to all N replicas in parallel (virtual threads), succeed after W write
 * acks / R read replies within the timeout, return the newest version and repair stale replicas.
 */
public class QuorumCoordinator {
    public record WriteResult(long version, int acks, int required) {}
    public record ReadResult(String value, long version, int replies, int required, List<String> repaired) {}

    private final RestClient http;
    private final QuorumProperties props;
    private final Clock clock;
    private final AtomicLong lastVersion = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public QuorumCoordinator(RestClient http, QuorumProperties props, Clock clock) {
        this.http = http;
        this.props = props;
        this.clock = clock;
    }

    public WriteResult write(String key, String value) {
        long version = lastVersion.updateAndGet(prev -> Math.max(prev + 1, clock.millis()));   // monotonic per coordinator
        Versioned v = new Versioned(value, version);
        List<Callable<Boolean>> calls = props.replicas().stream()
                .map(url -> (Callable<Boolean>) () -> { put(url, key, v); return true; }).toList();
        int acks = awaitSuccesses(calls, props.w()).size();
        if (acks < props.w()) {
            // Some replicas may still have the value: the client must retry (idempotent thanks to the version).
            throw Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "write quorum not met: " + acks + "/" + props.w() + " acks");
        }
        return new WriteResult(version, acks, props.w());
    }

    public ReadResult read(String key) {
        List<String> urls = props.replicas();
        List<Callable<Reply>> calls = urls.stream().map(url -> (Callable<Reply>) () -> new Reply(url, get(url, key))).toList();
        List<Reply> replies = awaitSuccesses(calls, props.r());
        if (replies.size() < props.r()) {
            throw Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "read quorum not met: " + replies.size() + "/" + props.r() + " replies");
        }
        Versioned newest = replies.stream().map(Reply::value).flatMap(Optional::stream)
                .max(java.util.Comparator.comparingLong(Versioned::version)).orElse(null);
        List<String> repaired = new ArrayList<>();
        if (newest != null) {
            for (Reply r : replies) {                        // read repair: push the newest value to stale repliers
                if (r.value().map(Versioned::version).orElse(-1L) < newest.version()) {
                    put(r.url(), key, newest);
                    repaired.add(r.url().substring(r.url().lastIndexOf('/') + 1));
                }
            }
        }
        return new ReadResult(newest == null ? null : newest.value(), newest == null ? 0 : newest.version(),
                replies.size(), props.r(), repaired);
    }

    /** Run all calls in parallel; return the results that succeed, stopping early once `needed` have succeeded. */
    private <T> List<T> awaitSuccesses(List<Callable<T>> calls, int needed) {
        CompletionService<T> cs = new ExecutorCompletionService<>(pool);
        List<Future<T>> futures = calls.stream().map(cs::submit).toList();
        List<T> ok = new ArrayList<>();
        long deadline = System.nanoTime() + props.timeout().toNanos();
        try {
            for (int done = 0; done < calls.size() && ok.size() < needed; done++) {
                Future<T> f = cs.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (f == null) break;                                         // timed out
                try { ok.add(f.get()); } catch (ExecutionException failedReplica) { /* counts as no reply */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok;
    }

    private record Reply(String url, Optional<Versioned> value) {}

    /**
     * Replica calls still running. A write returns after W acks, but the remaining N - W requests keep going
     * in the background and may land later, which is how lagging replicas still converge. Worth exporting as a gauge.
     */
    public int inFlight() { return inFlight.get(); }

    private void put(String url, String key, Versioned v) {
        inFlight.incrementAndGet();
        try {
            http.put().uri(url + "/kv/{key}", key).body(v).retrieve().toBodilessEntity();
        } finally { inFlight.decrementAndGet(); }
    }

    private Optional<Versioned> get(String url, String key) {
        inFlight.incrementAndGet();
        try {
            return Optional.ofNullable(http.get().uri(url + "/kv/{key}", key).retrieve().body(Versioned.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();                                          // a valid reply: "I don't have it"
        } finally { inFlight.decrementAndGet(); }
    }
}
