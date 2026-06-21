package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.config.KboHttpClientFactory;
import com.kbo.crawlerapi.config.KboHttpProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ApnsPushService {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);
    public static final String APNS_PUSH_DISABLED = "apns_push_disabled";
    public static final String APNS_CONFIG_MISSING = "apns_config_missing";
    public static final String APNS_PRIVATE_KEY_INVALID = "apns_private_key_invalid";
    public static final String DEVICE_NOTIFICATIONS_DISABLED = "device_notifications_disabled";
    public static final String DEVICE_NOTIFICATION_SETTINGS_DISABLED = "device_notification_settings_disabled";
    public static final String ENVIRONMENT_MISMATCH = "environment_mismatch";
    public static final String UNSUPPORTED_PLATFORM = "unsupported_platform";
    public static final String NO_RELEVANT_DEVICES = "no_relevant_devices";
    public static final String APNS_PRIVATE_KEY_PATH_NOT_FOUND = "apns_private_key_path_not_found";
    public static final String APNS_PRIVATE_KEY_UNREADABLE = "apns_private_key_unreadable";
    public static final String APNS_INVALID_PROVIDER_TOKEN = "apns_invalid_provider_token";
    public static final String APNS_BAD_DEVICE_TOKEN = "apns_bad_device_token";
    public static final String APNS_BAD_TOKEN = "apns_bad_token";
    public static final String APNS_BAD_ENVIRONMENT = "apns_bad_environment";
    public static final String APNS_TOO_MANY_PROVIDER_TOKEN_UPDATES = "apns_too_many_provider_token_updates";
    private static final Duration PROVIDER_TOKEN_REFRESH_AFTER = Duration.ofMinutes(50);
    private static final Duration PROVIDER_TOKEN_MAX_AGE = Duration.ofMinutes(60);

    private final ApnsProperties properties;
    private final Clock applicationClock;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProviderToken cachedProviderToken;

    public ApnsPushService(ApnsProperties properties, Clock applicationClock) {
        this(properties, applicationClock, new KboHttpProperties());
    }

    @Autowired
    public ApnsPushService(ApnsProperties properties, Clock applicationClock, KboHttpProperties httpProperties) {
        this(
                properties,
                applicationClock,
                KboHttpClientFactory.apnsHttpClient(httpProperties),
                KboHttpClientFactory.requestTimeout(httpProperties)
        );
    }

    ApnsPushService(ApnsProperties properties, Clock applicationClock, HttpClient httpClient) {
        this(properties, applicationClock, httpClient, KboHttpClientFactory.requestTimeout(new KboHttpProperties()));
    }

    ApnsPushService(ApnsProperties properties, Clock applicationClock, HttpClient httpClient, Duration requestTimeout) {
        this.properties = properties;
        this.applicationClock = applicationClock;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(10)
                : requestTimeout;
    }

    public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
        String deviceSkipReason = deviceSkipReason(device);
        if (deviceSkipReason != null) {
            log.info(
                    "[APNs] device skipped eventId={} deviceId={} reason={} pushEnabled={} configTeamIdPresent={} configKeyIdPresent={} configBundleIdPresent={} privateKeyPathPresent={} inlinePrivateKeyPresent={} configuredEnv={} deviceEnv={}",
                    event.getId(),
                    device.getId(),
                    deviceSkipReason,
                    properties.isPushEnabled(),
                    properties.hasTeamId(),
                    properties.hasKeyId(),
                    properties.hasBundleId(),
                    properties.hasPrivateKeyPath(),
                    properties.hasPrivateKey(),
                    configuredEnvironment(),
                    device.getEnvironment()
            );
            return ApnsSendResult.skipped(deviceSkipReason);
        }

        String readinessSkipReason = readinessSkipReason();
        if (readinessSkipReason != null) {
            log.warn(
                    "[APNs] delivery skipped eventId={} deviceId={} reason={} pushEnabled={} configTeamIdPresent={} configKeyIdPresent={} configBundleIdPresent={} privateKeyPathPresent={} inlinePrivateKeyPresent={} configuredEnv={} deviceEnv={}",
                    event.getId(),
                    device.getId(),
                    readinessSkipReason,
                    properties.isPushEnabled(),
                    properties.hasTeamId(),
                    properties.hasKeyId(),
                    properties.hasBundleId(),
                    properties.hasPrivateKeyPath(),
                    properties.hasPrivateKey(),
                    configuredEnvironment(),
                    device.getEnvironment()
            );
            return ApnsSendResult.skipped(readinessSkipReason);
        }

        try {
            String token = providerToken();
            HttpRequest request = buildRequest(event, device, token);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[APNs] push sent eventId={}", event.getId());
                return ApnsSendResult.sentResult();
            }
            String reason = response.body() == null || response.body().isBlank()
                    ? "status_" + response.statusCode()
                    : response.body();
            String mappedReason = mapApnsFailureReason(reason);
            log.warn("[APNs] push failed eventId={} reason={}", event.getId(), mappedReason);
            return new ApnsSendResult(false, false, isInvalidTokenResponse(response.statusCode(), mappedReason), mappedReason);
        } catch (Exception exception) {
            log.warn("[APNs] push failed eventId={} reason={}", event.getId(), exception.getClass().getSimpleName());
            return new ApnsSendResult(false, false, false, exception.getMessage());
        }
    }

    public ApnsSendResult sendLiveActivityUpdate(Game game, LiveActivityToken liveActivityToken, Map<String, Object> contentState) {
        String tokenSkipReason = liveActivityTokenSkipReason(liveActivityToken);
        if (tokenSkipReason != null) {
            log.info(
                    "[LiveActivity] APNs skipped publicGameId={} providerGameId={} databaseId={} activityId={} status=skipped reason={} configuredEnv={} tokenEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    liveActivityToken.getActivityId(),
                    tokenSkipReason,
                    configuredEnvironment(),
                    liveActivityToken.getEnvironment()
            );
            return ApnsSendResult.skipped(tokenSkipReason);
        }

        String readinessSkipReason = readinessSkipReason();
        if (readinessSkipReason != null) {
            log.warn(
                    "[LiveActivity] APNs skipped publicGameId={} providerGameId={} databaseId={} activityId={} status=skipped reason={} configuredEnv={} tokenEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    liveActivityToken.getActivityId(),
                    readinessSkipReason,
                    configuredEnvironment(),
                    liveActivityToken.getEnvironment()
            );
            return ApnsSendResult.skipped(readinessSkipReason);
        }

        try {
            String token = providerToken();
            HttpRequest request = buildLiveActivityUpdateRequest(liveActivityToken, contentState, token);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info(
                        "[LiveActivity] APNs sent publicGameId={} providerGameId={} databaseId={} activityId={} status={} reason={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        liveActivityToken.getActivityId(),
                        response.statusCode(),
                        null
                );
                return ApnsSendResult.sentResult();
            }
            String reason = response.body() == null || response.body().isBlank()
                    ? "status_" + response.statusCode()
                    : response.body();
            String mappedReason = mapLiveActivityStartFailureReason(reason);
            log.warn(
                    "[LiveActivity] APNs failed publicGameId={} providerGameId={} databaseId={} activityId={} status={} reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    liveActivityToken.getActivityId(),
                    response.statusCode(),
                    mappedReason
            );
            return new ApnsSendResult(false, false, isInvalidTokenResponse(response.statusCode(), mappedReason), mappedReason);
        } catch (Exception exception) {
            log.warn(
                    "[LiveActivity] APNs failed publicGameId={} providerGameId={} databaseId={} activityId={} status=exception reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    liveActivityToken.getActivityId(),
                    exception.getClass().getSimpleName()
            );
            return new ApnsSendResult(false, false, false, exception.getMessage());
        }
    }

    public ApnsSendResult sendLiveActivityStart(
            Game game,
            LiveActivityPushToStartToken pushToStartToken,
            Map<String, Object> attributes,
            Map<String, Object> contentState
    ) {
        String tokenSkipReason = pushToStartTokenSkipReason(pushToStartToken);
        if (tokenSkipReason != null) {
            log.info(
                    "[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} installationId={} status=skipped reason={} configuredEnv={} tokenEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    pushToStartToken.getInstallationId(),
                    tokenSkipReason,
                    configuredEnvironment(),
                    pushToStartToken.getEnvironment()
            );
            return ApnsSendResult.skipped(tokenSkipReason);
        }

        String readinessSkipReason = readinessSkipReason();
        if (readinessSkipReason != null) {
            log.warn(
                    "[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} installationId={} status=skipped reason={} configuredEnv={} tokenEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    pushToStartToken.getInstallationId(),
                    readinessSkipReason,
                    configuredEnvironment(),
                    pushToStartToken.getEnvironment()
            );
            return ApnsSendResult.skipped(readinessSkipReason);
        }

        try {
            String token = providerToken();
            HttpRequest request = buildLiveActivityStartRequest(pushToStartToken, attributes, contentState, token);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info(
                        "[LiveActivityStart] APNs sent publicGameId={} providerGameId={} databaseId={} installationId={} status={} reason={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        pushToStartToken.getInstallationId(),
                        response.statusCode(),
                        null
                );
                return ApnsSendResult.sentResult();
            }
            String reason = response.body() == null || response.body().isBlank()
                    ? "status_" + response.statusCode()
                    : response.body();
            String mappedReason = mapApnsFailureReason(reason);
            log.warn(
                    "[LiveActivityStart] APNs failed publicGameId={} providerGameId={} databaseId={} installationId={} status={} reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    pushToStartToken.getInstallationId(),
                    response.statusCode(),
                    mappedReason
            );
            return new ApnsSendResult(false, false, isInvalidTokenResponse(response.statusCode(), mappedReason), mappedReason);
        } catch (Exception exception) {
            log.warn(
                    "[LiveActivityStart] APNs failed publicGameId={} providerGameId={} databaseId={} installationId={} status=exception reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    pushToStartToken.getInstallationId(),
                    exception.getClass().getSimpleName()
            );
            return new ApnsSendResult(false, false, false, exception.getMessage());
        }
    }

    public String readinessSkipReason() {
        if (!properties.isPushEnabled()) {
            return APNS_PUSH_DISABLED;
        }
        if (!properties.isConfigPresent()) {
            return APNS_CONFIG_MISSING;
        }
        PrivateKeyLoadResult privateKeyLoadResult = privateKeyLoadResult();
        log.info(
                "[APNs] key diagnostics pushEnabled={} configuredEnv={} configTeamIdPresent={} configKeyIdPresent={} configBundleIdPresent={} privateKeyPathPresent={} inlinePrivateKeyPresent={} privateKeyParseSuccess={} privateKeyReadSuccess={}",
                properties.isPushEnabled(),
                configuredEnvironment(),
                properties.hasTeamId(),
                properties.hasKeyId(),
                properties.hasBundleId(),
                properties.hasPrivateKeyPath(),
                properties.hasPrivateKey(),
                privateKeyLoadResult.success(),
                privateKeyLoadResult.readSuccess()
        );
        if (!privateKeyLoadResult.success()) {
            return privateKeyLoadResult.reason();
        }
        return null;
    }

    public ApnsDiagnostics diagnostics() {
        return new ApnsDiagnostics(
                properties.isPushEnabled(),
                properties.hasTeamId(),
                properties.hasKeyId(),
                properties.hasBundleId(),
                properties.hasPrivateKeyPath(),
                properties.hasPrivateKey(),
                configuredEnvironment()
        );
    }

    public boolean environmentMatches(String deviceEnvironment) {
        return configuredEnvironment().equalsIgnoreCase(normalizeEnvironment(deviceEnvironment));
    }

    public String configuredEnvironment() {
        return normalizeEnvironment(properties.getEnv());
    }

    private String deviceSkipReason(NotificationDevice device) {
        if (!"ios".equalsIgnoreCase(device.getPlatform())) {
            return UNSUPPORTED_PLATFORM;
        }
        if (!device.isNotificationsEnabled()) {
            return DEVICE_NOTIFICATIONS_DISABLED;
        }
        if (!environmentMatches(device.getEnvironment())) {
            return ENVIRONMENT_MISMATCH;
        }
        return null;
    }

    private String liveActivityTokenSkipReason(LiveActivityToken token) {
        if (!"ios".equalsIgnoreCase(token.getPlatform())) {
            return UNSUPPORTED_PLATFORM;
        }
        if (!token.isActive()) {
            return DEVICE_NOTIFICATIONS_DISABLED;
        }
        if (!environmentMatches(token.getEnvironment())) {
            return ENVIRONMENT_MISMATCH;
        }
        return null;
    }

    private String pushToStartTokenSkipReason(LiveActivityPushToStartToken token) {
        if (!"ios".equalsIgnoreCase(token.getPlatform())) {
            return UNSUPPORTED_PLATFORM;
        }
        if (!token.isActive()) {
            return DEVICE_NOTIFICATIONS_DISABLED;
        }
        if (!environmentMatches(token.getEnvironment())) {
            return APNS_BAD_ENVIRONMENT;
        }
        return null;
    }

    private PrivateKeyLoadResult privateKeyLoadResult() {
        try {
            privateKey();
            return PrivateKeyLoadResult.loaded();
        } catch (PrivateKeyLoadException exception) {
            return PrivateKeyLoadResult.failed(exception.reason(), exception.readSuccess());
        } catch (Exception exception) {
            return PrivateKeyLoadResult.failed(APNS_PRIVATE_KEY_INVALID, true);
        }
    }

    private String endpoint(NotificationDevice device) {
        String env = normalizeEnvironment(device.getEnvironment());
        String host = "production".equalsIgnoreCase(env)
                ? "https://api.push.apple.com"
                : "https://api.sandbox.push.apple.com";
        return host + "/3/device/" + device.getDeviceToken();
    }

    private String endpoint(LiveActivityToken token) {
        String env = normalizeEnvironment(token.getEnvironment());
        String host = "production".equalsIgnoreCase(env)
                ? "https://api.push.apple.com"
                : "https://api.sandbox.push.apple.com";
        return host + "/3/device/" + token.getActivityToken();
    }

    private String endpoint(LiveActivityPushToStartToken token) {
        String env = normalizeEnvironment(token.getEnvironment());
        String host = "production".equalsIgnoreCase(env)
                ? "https://api.push.apple.com"
                : "https://api.sandbox.push.apple.com";
        return host + "/3/device/" + token.getPushToStartToken();
    }

    HttpRequest buildRequest(NotificationEvent event, NotificationDevice device, String token) {
        String body = """
                {"aps":{"alert":{"title":%s,"body":%s},"sound":"default"},"data":%s}
                """.formatted(jsonString(event.getTitle()), jsonString(event.getBody()), event.getPayload());
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(device)))
                .timeout(requestTimeout)
                .header("authorization", "bearer " + token)
                .header("apns-topic", properties.getBundleId())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    HttpRequest buildLiveActivityUpdateRequest(LiveActivityToken liveActivityToken, Map<String, Object> contentState, String token) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("timestamp", Instant.now(applicationClock).getEpochSecond());
        aps.put("event", "update");
        aps.put("content-state", contentState);
        aps.put("stale-date", Instant.now(applicationClock).plusSeconds(120).getEpochSecond());
        Map<String, Object> payload = Map.of("aps", aps);
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(liveActivityToken)))
                .timeout(requestTimeout)
                .header("authorization", "bearer " + token)
                .header("apns-topic", properties.getBundleId() + ".push-type.liveactivity")
                .header("apns-push-type", "liveactivity")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();
    }

    HttpRequest buildLiveActivityStartRequest(
            LiveActivityPushToStartToken pushToStartToken,
            Map<String, Object> attributes,
            Map<String, Object> contentState,
            String token
    ) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("timestamp", Instant.now(applicationClock).getEpochSecond());
        aps.put("event", "start");
        aps.put("attributes-type", "FavoriteTeamGameActivityAttributes");
        aps.put("attributes", attributes);
        aps.put("content-state", contentState);
        aps.put("stale-date", Instant.now(applicationClock).plusSeconds(120).getEpochSecond());
        aps.put("input-push-token", 1);
        Map<String, Object> payload = Map.of("aps", aps);
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(pushToStartToken)))
                .timeout(requestTimeout)
                .header("authorization", "bearer " + token)
                .header("apns-topic", properties.getBundleId() + ".push-type.liveactivity")
                .header("apns-push-type", "liveactivity")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize APNs payload", exception);
        }
    }

    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(java.util.Locale.ROOT);
        if ("development".equals(normalized) || "debug".equals(normalized)) {
            return "sandbox";
        }
        if ("release".equals(normalized)) {
            return "production";
        }
        return normalized;
    }

    private boolean isInvalidTokenResponse(int statusCode, String reason) {
        return statusCode == 410
                || reason.contains("BadDeviceToken")
                || reason.contains("Unregistered")
                || reason.contains("DeviceTokenNotForTopic")
                || APNS_BAD_DEVICE_TOKEN.equals(reason);
    }

    String mapApnsFailureReason(String reason) {
        if (reason != null && reason.contains("InvalidProviderToken")) {
            return APNS_INVALID_PROVIDER_TOKEN;
        }
        if (reason != null && reason.contains("BadDeviceToken")) {
            return APNS_BAD_DEVICE_TOKEN;
        }
        if (reason != null && reason.contains("DeviceTokenNotForTopic")) {
            return APNS_BAD_ENVIRONMENT;
        }
        if (reason != null && reason.contains("TooManyProviderTokenUpdates")) {
            return APNS_TOO_MANY_PROVIDER_TOKEN_UPDATES;
        }
        return reason;
    }

    String mapLiveActivityStartFailureReason(String reason) {
        String mappedReason = mapApnsFailureReason(reason);
        if (APNS_BAD_DEVICE_TOKEN.equals(mappedReason)) {
            return APNS_BAD_TOKEN;
        }
        return mappedReason;
    }

    private synchronized String providerToken() throws Exception {
        Instant now = Instant.now(applicationClock);
        ProviderToken cached = cachedProviderToken;
        if (cached != null) {
            long tokenAgeSeconds = Duration.between(cached.issuedAt(), now).toSeconds();
            if (now.isBefore(cached.expiresAt()) && now.isBefore(cached.refreshAfter())) {
                log.info(
                        "[APNs] provider token cache providerTokenCacheHit=true tokenAgeSeconds={} tokenRefreshReason=cache_valid",
                        tokenAgeSeconds
                );
                return cached.token();
            }
            String refreshReason = now.isBefore(cached.expiresAt()) ? "refresh_window_elapsed" : "expired";
            log.info(
                    "[APNs] provider token cache providerTokenCacheHit=false tokenAgeSeconds={} tokenRefreshReason={}",
                    tokenAgeSeconds,
                    refreshReason
            );
        } else {
            log.info("[APNs] provider token cache providerTokenCacheHit=false tokenAgeSeconds=null tokenRefreshReason=no_cached_token");
        }

        ProviderToken refreshed = createProviderToken(now);
        cachedProviderToken = refreshed;
        return refreshed.token();
    }

    private ProviderToken createProviderToken(Instant issuedAt) throws Exception {
        String token = jwt(issuedAt);
        return new ProviderToken(
                token,
                issuedAt,
                issuedAt.plus(PROVIDER_TOKEN_REFRESH_AFTER),
                issuedAt.plus(PROVIDER_TOKEN_MAX_AGE)
        );
    }

    private String jwt(Instant issuedAt) throws Exception {
        String header = "{\"alg\":\"ES256\",\"kid\":\"%s\"}".formatted(properties.getKeyId());
        String claims = "{\"iss\":\"%s\",\"iat\":%d}".formatted(properties.getTeamId(), issuedAt.getEpochSecond());
        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8)) + "." + base64Url(claims.getBytes(StandardCharsets.UTF_8));
        byte[] signature = sign(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature);
    }

    private byte[] sign(byte[] signingInput) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey());
        signature.update(signingInput);
        return derToJose(signature.sign(), 64);
    }

    private PrivateKey privateKey() throws Exception {
        byte[] encoded = decodePemBody(loadPem());
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (!(key instanceof ECPrivateKey)) {
                throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_INVALID, true);
            }
            return key;
        } catch (PrivateKeyLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_INVALID, true, exception);
        }
    }

    private String loadPem() {
        if (properties.hasPrivateKeyPath()) {
            return loadPemFromPath();
        }
        return normalizeInlinePem(properties.getPrivateKey());
    }

    private String loadPemFromPath() {
        Path path;
        try {
            path = Path.of(properties.getPrivateKeyPath().trim());
        } catch (InvalidPathException exception) {
            throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_PATH_NOT_FOUND, false, exception);
        }
        if (!Files.exists(path)) {
            throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_PATH_NOT_FOUND, false);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException exception) {
            throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_UNREADABLE, false, exception);
        }
    }

    private String normalizeInlinePem(String privateKey) {
        String pem = privateKey == null ? "" : privateKey.trim();
        if ((pem.startsWith("\"") && pem.endsWith("\"")) || (pem.startsWith("'") && pem.endsWith("'"))) {
            pem = pem.substring(1, pem.length() - 1).trim();
        }
        return pem.replace("\\n", "\n").trim();
    }

    private byte[] decodePemBody(String pem) {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException exception) {
            throw new PrivateKeyLoadException(APNS_PRIVATE_KEY_INVALID, true, exception);
        }
    }

    private byte[] derToJose(byte[] derSignature, int outputLength) throws Exception {
        try (var input = new java.io.DataInputStream(new ByteArrayInputStream(derSignature))) {
            if (input.readUnsignedByte() != 0x30) {
                throw new IllegalArgumentException("Invalid ECDSA signature");
            }
            input.readUnsignedByte();
            byte[] r = readDerInteger(input);
            byte[] s = readDerInteger(input);
            byte[] jose = new byte[outputLength];
            copyToFixed(r, jose, 0, outputLength / 2);
            copyToFixed(s, jose, outputLength / 2, outputLength / 2);
            return jose;
        }
    }

    private byte[] readDerInteger(java.io.DataInputStream input) throws Exception {
        if (input.readUnsignedByte() != 0x02) {
            throw new IllegalArgumentException("Invalid ECDSA integer");
        }
        int length = input.readUnsignedByte();
        byte[] value = input.readNBytes(length);
        return new BigInteger(1, value).toByteArray();
    }

    private void copyToFixed(byte[] source, byte[] target, int offset, int length) {
        int sourceOffset = Math.max(0, source.length - length);
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, target, offset + length - copyLength, copyLength);
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public record ApnsSendResult(
            boolean sent,
            boolean skipped,
            boolean invalidToken,
            String reason
    ) {
        public static ApnsSendResult sentResult() {
            return new ApnsSendResult(true, false, false, null);
        }

        public static ApnsSendResult skipped(String reason) {
            return new ApnsSendResult(false, true, false, reason);
        }
    }

    public record ApnsDiagnostics(
            boolean pushEnabled,
            boolean teamIdPresent,
            boolean keyIdPresent,
            boolean bundleIdPresent,
            boolean privateKeyPathPresent,
            boolean inlinePrivateKeyPresent,
            String configuredEnvironment
    ) {
    }

    private record PrivateKeyLoadResult(
            boolean success,
            String reason,
            boolean readSuccess
    ) {
        private static PrivateKeyLoadResult loaded() {
            return new PrivateKeyLoadResult(true, null, true);
        }

        private static PrivateKeyLoadResult failed(String reason, boolean readSuccess) {
            return new PrivateKeyLoadResult(false, reason, readSuccess);
        }
    }

    private record ProviderToken(
            String token,
            Instant issuedAt,
            Instant refreshAfter,
            Instant expiresAt
    ) {
    }

    private static final class PrivateKeyLoadException extends RuntimeException {

        private final String reason;
        private final boolean readSuccess;

        private PrivateKeyLoadException(String reason, boolean readSuccess) {
            this(reason, readSuccess, null);
        }

        private PrivateKeyLoadException(String reason, boolean readSuccess, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
            this.readSuccess = readSuccess;
        }

        private String reason() {
            return reason;
        }

        private boolean readSuccess() {
            return readSuccess;
        }
    }
}
