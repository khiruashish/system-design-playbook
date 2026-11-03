package com.sdp.ci.p01shortener;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShortenerController {
    public record ShortenRequest(
            @NotBlank @Pattern(regexp = "https?://\\S+", message = "must be an http(s) URL") String url,
            @Pattern(regexp = "[A-Za-z0-9_-]{4,30}", message = "4-30 letters, digits, _ or -") String alias) {}

    public record ShortenResponse(String code, String shortUrl, String url) {}

    private final ShortenerService service;
    private final ShortenerProperties props;

    public ShortenerController(ShortenerService service, ShortenerProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        String code = service.shorten(req.url(), req.alias());
        String shortUrl = props.baseUrl() + code;
        return ResponseEntity.created(URI.create(shortUrl)).body(new ShortenResponse(code, shortUrl, req.url()));
    }

    /** 302 (not 301) so browsers keep coming back and every click can be counted. */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(service.resolve(code))).build();
    }
}
