package com.kbo.crawlerapi.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthzController {

    private static final Logger log = LoggerFactory.getLogger(HealthzController.class);

    @GetMapping("/healthz")
    public ResponseEntity<String> healthz(HttpServletRequest request) {
        String source = request.getHeader("X-KBO-Keep-Alive");
        String userAgent = request.getHeader("User-Agent");
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String remoteAddr = request.getRemoteAddr();

        log.info(
                "[Healthz] keep-alive ping source={} userAgent={} forwardedFor={} remoteAddr={}",
                source,
                userAgent,
                forwardedFor,
                remoteAddr
        );

        return ResponseEntity.ok("ok");
    }
}
