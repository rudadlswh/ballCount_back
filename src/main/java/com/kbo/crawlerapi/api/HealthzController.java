package com.kbo.crawlerapi.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public ResponseEntity<String> healthz() {
        return ResponseEntity.ok("ok");
    }
}
