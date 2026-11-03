package com.sdp.ci.p02ratelimit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A stand-in for any expensive API that needs protecting. */
@RestController
public class QuoteController {
    public record Quote(String symbol, double price) {}

    @GetMapping("/api/v1/quotes/{symbol}")
    public Quote quote(@PathVariable String symbol) {
        return new Quote(symbol, 1234.5);
    }
}
