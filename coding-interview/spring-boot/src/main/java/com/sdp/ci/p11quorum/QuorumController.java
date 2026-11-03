package com.sdp.ci.p11quorum;

import com.sdp.ci.common.Problems;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kv")
public class QuorumController {
    public record PutRequest(@NotBlank String value) {}

    private final QuorumCoordinator coordinator;

    public QuorumController(QuorumCoordinator coordinator) { this.coordinator = coordinator; }

    @PutMapping("/{key}")
    public QuorumCoordinator.WriteResult put(@PathVariable String key, @RequestBody @jakarta.validation.Valid PutRequest req) {
        return coordinator.write(key, req.value());
    }

    @GetMapping("/{key}")
    public QuorumCoordinator.ReadResult get(@PathVariable String key) {
        var result = coordinator.read(key);
        if (result.value() == null) throw Problems.of(HttpStatus.NOT_FOUND, "no value for " + key);
        return result;
    }
}
