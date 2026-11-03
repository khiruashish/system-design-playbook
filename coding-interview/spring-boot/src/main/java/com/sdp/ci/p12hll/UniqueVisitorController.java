package com.sdp.ci.p12hll;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pages/{page}")
public class UniqueVisitorController {
    public record Visit(@NotBlank String visitorId) {}
    public record UniqueVisitors(String page, LocalDate from, LocalDate to, long uniqueVisitors) {}

    private final UniqueVisitorService visitors;
    private final java.time.Clock clock;

    public UniqueVisitorController(UniqueVisitorService visitors, java.time.Clock clock) {
        this.visitors = visitors;
        this.clock = clock;
    }

    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void visit(@PathVariable String page, @RequestBody @jakarta.validation.Valid Visit v) {
        visitors.visit(page, LocalDate.now(clock), v.visitorId());
    }

    @GetMapping("/unique-visitors")
    public UniqueVisitors unique(@PathVariable String page,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new UniqueVisitors(page, from, to, visitors.unique(page, from, to));
    }
}
