package com.sdp.ci.p10merkle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/replicas")
public class AntiEntropyController {
    public record Root(String replica, String root) {}

    private final AntiEntropyService service;

    public AntiEntropyController(AntiEntropyService service) { this.service = service; }

    @GetMapping("/{replica}/merkle-root")
    public Root root(@PathVariable String replica) { return new Root(replica, service.rootHash(replica)); }

    @PostMapping("/anti-entropy")
    public AntiEntropyService.Report sync(@RequestParam String a, @RequestParam String b) { return service.sync(a, b); }

    /** Background repair, the way Cassandra's repair or Dynamo's anti-entropy runs: off by default here. */
    @Component
    @ConditionalOnProperty(name = "ci.anti-entropy.enabled", havingValue = "true")
    static class Job {
        private final AntiEntropyService service;
        Job(AntiEntropyService service) { this.service = service; }

        @Scheduled(fixedDelayString = "${ci.anti-entropy.every:5m}")
        void run() { service.sync("a", "b"); }
    }
}
