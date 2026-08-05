package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FcmAccessTokenProvider {

    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final FcmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock applicationClock;

    private volatile CachedToken cachedToken;

    @Autowired
    public FcmAccessTokenProvider(FcmProperties properties, ObjectMapper objectMapper, Clock applicationClock) {
        this(properties, objectMapper, HttpClient.newHttpClient(), applicationClock);
    }

    FcmAccessTokenProvider(
            FcmProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock applicationClock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.applicationClock = applicationClock;
    }

    public synchronized String accessToken() {
        Instant now = Instant.now(applicationClock);
        if (cachedToken != null && cachedToken.expiresAt().isAfter(now.plusSeconds(300))) {
            return cachedToken.value();
        }
        ServiceAccount account = loadServiceAccount();
        try {
            String assertion = signedAssertion(account, now);
            String body = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion=" + urlEncode(assertion);
            HttpRequest request = HttpRequest.newBuilder(URI.create(account.tokenUri()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("FCM OAuth token request failed with status " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            String value = requiredText(json, "access_token");
            long expiresIn = json.path("expires_in").asLong(3600);
            cachedToken = new CachedToken(value, now.plusSeconds(expiresIn));
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FCM OAuth token request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to obtain FCM OAuth token", exception);
        }
    }

    public synchronized void invalidate() {
        cachedToken = null;
    }

    public String resolvedProjectId() {
        if (hasText(properties.getProjectId())) {
            return properties.getProjectId().trim();
        }
        return loadServiceAccount().projectId();
    }

    public boolean credentialsPresent() {
        return hasText(properties.getCredentialsJson()) || hasText(properties.getCredentialsPath());
    }

    private ServiceAccount loadServiceAccount() {
        try {
            String raw;
            if (hasText(properties.getCredentialsJson())) {
                raw = properties.getCredentialsJson();
            } else if (hasText(properties.getCredentialsPath())) {
                raw = Files.readString(Path.of(properties.getCredentialsPath().trim()));
            } else {
                throw new IllegalStateException("FCM credentials are not configured");
            }
            JsonNode json = objectMapper.readTree(raw);
            return new ServiceAccount(
                    requiredText(json, "project_id"),
                    requiredText(json, "client_email"),
                    requiredText(json, "private_key"),
                    requiredText(json, "token_uri")
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load FCM service account", exception);
        }
    }

    private String signedAssertion(ServiceAccount account, Instant now) throws Exception {
        String header = base64Url(objectMapper.writeValueAsBytes(java.util.Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(objectMapper.writeValueAsBytes(java.util.Map.of(
                "iss", account.clientEmail(),
                "scope", FCM_SCOPE,
                "aud", account.tokenUri(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond()
        )));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey(account.privateKey()));
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature.sign());
    }

    private java.security.PrivateKey privateKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private String requiredText(JsonNode json, String name) {
        String value = json.path(name).asText(null);
        if (!hasText(value)) {
            throw new IllegalStateException("FCM service account field is missing: " + name);
        }
        return value;
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ServiceAccount(String projectId, String clientEmail, String privateKey, String tokenUri) {
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
