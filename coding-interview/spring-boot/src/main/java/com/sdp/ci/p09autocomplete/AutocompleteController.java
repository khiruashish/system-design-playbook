package com.sdp.ci.p09autocomplete;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/autocomplete")
public class AutocompleteController {
    private final AutocompleteService service;

    public AutocompleteController(AutocompleteService service) { this.service = service; }

    /** Suggestions are the same for everyone, so let the CDN/browser cache them briefly. */
    @GetMapping
    public ResponseEntity<List<String>> suggest(@RequestParam @NotBlank @Size(max = 50) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .body(service.suggest(q));
    }

    @PostMapping("/searches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void searched(@RequestParam @NotBlank @Size(max = 100) String term) { service.recordSearch(term); }
}
