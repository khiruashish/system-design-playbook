package com.sdp.ci.p04bloom;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/usernames")
public class UsernameController {
    public record Availability(String username, boolean available) {}

    private final UsernameService service;

    public UsernameController(UsernameService service) { this.service = service; }

    @PostMapping("/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@PathVariable String name) { service.register(name); }

    @GetMapping("/{name}/availability")
    public Availability availability(@PathVariable String name) {
        return new Availability(name, service.isAvailable(name));
    }
}
