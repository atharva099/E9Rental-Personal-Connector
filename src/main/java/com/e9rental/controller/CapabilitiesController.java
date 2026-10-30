package com.e9rental.controller;

import com.e9rental.config.GoogleSandboxPolicy;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, read-only description of the active backend access mode. */
@RestController
public final class CapabilitiesController {
    private static final Set<String> SUPPORTED_MODES = Set.of("PERSONAL", "SANDBOX");
    private final GoogleSandboxPolicy policy;

    public CapabilitiesController(GoogleSandboxPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/capabilities")
    public ResponseEntity<Map<String, String>> capabilities() {
        String mode = policy.mode();
        if (!SUPPORTED_MODES.contains(mode)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Backend mode is unavailable"));
        }
        return ResponseEntity.ok(Map.of("mode", mode));
    }
}
