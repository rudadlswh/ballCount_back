package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FcmPushService {

    public static final String PUSH_DISABLED = "fcm_push_disabled";
    public static final String BAD_DEVICE_TOKEN = "fcm_bad_device_token";
    public static final String SEND_FAILED = "fcm_send_failed";

    private static final Logger log = LoggerFactory.getLogger(FcmPushService.class);

    private final FcmProperties properties;
    private final FcmAccessTokenProvider accessTokenProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public FcmPushService(
            FcmProperties properties,
            FcmAccessTokenProvider accessTokenProvider,
            ObjectMapper objectMapper
    ) {
        this(properties, accessTokenProvider, objectMapper, HttpClient.newHttpClient());
    }

    FcmPushService(
            FcmProperties properties,
            FcmAccessTokenProvider accessTokenProvider,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.accessTokenProvider = accessTokenProvider;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public String readinessSkipReason() {
        return properties.isPushEnabled() && accessTokenProvider.credentialsPresent() ? null : PUSH_DISABLED;
    }

    public String configuredEnvironment() {
        String environment = properties.getEnvironment();
        return environment == null || environment.isBlank() ? "sandbox" : environment.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public FcmSendResult send(NotificationEvent event, NotificationDevice device) {
        String readiness = readinessSkipReason();
        if (readiness != null) {
            return FcmSendResult.skipped(readiness);
        }
        if (device == null || device.getDeviceToken() == null || device.getDeviceToken().isBlank()) {
            return FcmSendResult.failed(BAD_DEVICE_TOKEN, true);
        }
        try {
            return send(event, device, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return FcmSendResult.failed(SEND_FAILED, false);
        } catch (Exception exception) {
            log.warn("[FCM] send failed eventId={} deviceId={} reason={}", event.getId(), device.getId(), exception.getMessage());
            return FcmSendResult.failed(SEND_FAILED, false);
        }
    }

    private FcmSendResult send(NotificationEvent event, NotificationDevice device, boolean retried) throws Exception {
        String projectId = accessTokenProvider.resolvedProjectId();
        String endpoint = properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                ? "https://fcm.googleapis.com"
                : properties.getEndpoint().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        endpoint + "/v1/projects/" + projectId + "/messages:send"
                ))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessTokenProvider.accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(messageBody(event, device))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && !retried) {
            accessTokenProvider.invalidate();
            return send(event, device, true);
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode json = objectMapper.readTree(response.body());
            return FcmSendResult.sent(json.path("name").asText(null));
        }
        boolean invalid = response.statusCode() == 404
                || response.body().contains("UNREGISTERED")
                || response.body().contains("registration-token-not-registered");
        return FcmSendResult.failed("fcm_http_" + response.statusCode(), invalid);
    }

    Map<String, Object> messageBody(NotificationEvent event, NotificationDevice device) throws Exception {
        Map<String, Object> android = Map.of(
                "priority", "HIGH",
                "ttl", "7200s",
                "collapse_key", "game-" + event.getGame().getPublicGameId()
        );
        return Map.of("message", Map.of(
                "token", device.getDeviceToken(),
                "android", android,
                "data", toData(event)
        ));
    }

    private Map<String, String> toData(NotificationEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("eventId", event.getId().toString());
        data.put("eventType", event.getEventType());
        data.put("eventKey", event.getEventKey());
        data.put("title", event.getTitle());
        data.put("body", event.getBody());
        data.put("publicGameId", event.getGame().getPublicGameId());
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() { });
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (entry.getValue() == null || data.containsKey(entry.getKey())) {
                    continue;
                }
                data.put(entry.getKey(), stringify(entry.getValue()));
            }
        } catch (Exception exception) {
            log.warn("[FCM] payload parse failed eventId={} reason={}", event.getId(), exception.getMessage());
        }
        return data;
    }

    private String stringify(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return objectMapper.writeValueAsString(value);
    }

    public record FcmSendResult(boolean sent, boolean skipped, boolean invalidToken, String reason, String messageId) {
        public static FcmSendResult sent(String messageId) {
            return new FcmSendResult(true, false, false, null, messageId);
        }

        public static FcmSendResult skipped(String reason) {
            return new FcmSendResult(false, true, false, reason, null);
        }

        public static FcmSendResult failed(String reason, boolean invalidToken) {
            return new FcmSendResult(false, false, invalidToken, reason, null);
        }
    }
}
