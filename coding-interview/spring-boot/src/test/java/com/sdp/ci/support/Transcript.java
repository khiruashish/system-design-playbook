package com.sdp.ci.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Records a readable request/response log while a test runs and writes it to target/transcripts/NAME.txt.
 * The docs include these files (via outputs/), and CI fails if they change unexpectedly, like run-all.sh --check.
 */
public final class Transcript {
    private final String name;
    private final MockMvc mvc;
    private final List<String> lines = new ArrayList<>();

    public Transcript(String name, MockMvc mvc) { this.name = name; this.mvc = mvc; }

    public record Reply(int status, String body, MockHttpServletResponse raw) {
        public String header(String name) { return raw.getHeader(name); }
    }

    // URI.create: the string is used as-is (already percent-encoded), not as a URI template that would be encoded again
    public Reply get(String uri, String... headers) { return call("GET", MockMvcRequestBuilders.get(URI.create(uri)), uri, null, headers); }
    public Reply post(String uri, String json) { return call("POST", MockMvcRequestBuilders.post(URI.create(uri)), uri, json); }
    public Reply put(String uri, String json) { return call("PUT", MockMvcRequestBuilders.put(URI.create(uri)), uri, json); }

    /** Record without printing (e.g. bulk loading); keeps the transcript short. */
    public Reply quietly(MockHttpServletRequestBuilder req) {
        try {
            var r = mvc.perform(req).andReturn().getResponse();
            return new Reply(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8), r);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Transcript note(String text) { lines.add(text); return this; }

    public void pass() {
        lines.add("PASS");
        try {
            Path dir = Path.of("target", "transcripts");
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private Reply call(String method, MockHttpServletRequestBuilder req, String uri, String json, String... headers) {
        if (json != null) req.contentType("application/json").content(json);
        StringBuilder shown = new StringBuilder("> ").append(method).append(' ').append(uri);
        for (int i = 0; i + 1 < headers.length; i += 2) { req.header(headers[i], headers[i + 1]); shown.append("  [").append(headers[i]).append(": ").append(headers[i + 1]).append(']'); }
        if (json != null) shown.append("  ").append(json);
        lines.add(shown.toString());
        Reply r = quietly(req);
        StringBuilder back = new StringBuilder("< ").append(r.status());
        for (String h : List.of(HttpHeaders.LOCATION, "RateLimit-Remaining", HttpHeaders.RETRY_AFTER, HttpHeaders.CACHE_CONTROL)) {
            if (r.raw().getHeader(h) != null) back.append("  ").append(h).append(": ").append(r.raw().getHeader(h));
        }
        if (!r.body().isEmpty()) back.append("  ").append(r.body());
        lines.add(back.toString());
        return r;
    }
}
