package com.sdp.ci.p11quorum;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.MutableClock;
import com.sdp.ci.support.RedisIntegrationTest;
import com.sdp.ci.support.Transcript;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real HTTP: the app listens on 18080 and the coordinator calls replicas a/b/c over the network
 * with RestClient, so failures and timeouts behave like separate nodes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=18080")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisIntegrationTest.ClockConfig.class)
class QuorumIT {
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired QuorumCoordinator coordinator;

    @Test
    void writeAndReadQuorumsWithFailuresAndReadRepair() {
        clock.set(RedisIntegrationTest.T0);
        Transcript t = newTranscript();
        t.note("N=3 replicas (a, b, c), W=2, R=2 -> R + W > N, so every read overlaps the latest write");

        var w1 = t.put("/api/v1/kv/cart:42", "{\"value\":\"2 items\"}");
        assertThat(w1.body()).contains("\"acks\":2");

        settle();
        t.put("/internal/replicas/c/down?value=true", null);
        t.note("replica c goes down");
        var w2 = t.put("/api/v1/kv/cart:42", "{\"value\":\"3 items\"}");
        assertThat(w2.status()).isEqualTo(200);

        settle();
        t.put("/internal/replicas/c/down?value=false", null);
        settle();
        t.put("/internal/replicas/a/down?value=true", null);
        t.note("c comes back (still holding '2 items'), a goes down");
        var read = t.get("/api/v1/kv/cart:42");
        assertThat(read.body()).contains("\"value\":\"3 items\"").contains("\"repaired\":[\"c\"]");

        settle();
        t.put("/internal/replicas/b/down?value=true", null);
        t.note("a and b down: only 1 of 3 replicas can ack");
        var w3 = t.put("/api/v1/kv/cart:42", "{\"value\":\"4 items\"}");
        assertThat(w3.status()).isEqualTo(503);
        settle();
        t.put("/internal/replicas/a/down?value=false", null);
        settle();
        t.put("/internal/replicas/b/down?value=false", null);
        t.pass();
    }

    private Transcript newTranscript() { return new Transcript("QuorumSpringBoot", mvc); }

    /** Wait for the background N - W replica calls to finish before changing which replicas are up. */
    private void settle() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (coordinator.inFlight() > 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(coordinator.inFlight()).isZero();
    }
}
