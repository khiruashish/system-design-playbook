package com.sdp.ci.p07trending;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trending")
public class TrendingController {
    private final TrendingService trending;

    public TrendingController(TrendingService trending) { this.trending = trending; }

    /** In production this is fed by a Kafka consumer of view events rather than called per request. */
    @PostMapping("/{item}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void view(@PathVariable String item) { trending.record(item); }

    @GetMapping
    public List<TrendingService.Trend> top(@RequestParam(defaultValue = "5") @Min(1) @Max(TrendingService.TOP_K) int k) {
        return trending.top(k);
    }
}
