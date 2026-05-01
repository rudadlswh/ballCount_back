package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ApnsPushService {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);

    private final ApnsProperties properties;
    private final Clock applicationClock;
    private final HttpClient httpClient;

    @Autowired
    public ApnsPushService(ApnsProperties properties, Clock applicationClock) {
        this(properties, applicationClock, HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build());
    }

    ApnsPushService(ApnsProperties properties, Clock applicationClock, HttpClient httpClient) {
        this.properties = properties;
        this.applicationClock = applicationClock;
        this.httpClient = httpClient;
    }

    public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
        if (!properties.isPushEnabled()) {
            return ApnsSendResult.skipped("disabled");
        }
        if (!properties.isConfigPresent()) {
            log.warn("[APNs] config missing");
            return ApnsSendResult.skipped("config_missing");
        }

        try {
            String token = jwt();
            HttpRequest request = buildRequest(event, device, token);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[APNs] push sent eventId={}", event.getId());
                return ApnsSendResult.sentResult();
            }
            String reason = response.body() == null || response.body().isBlank()
                    ? "status_" + response.statusCode()
                    : response.body();
            log.warn("[APNs] push failed eventId={} reason={}", event.getId(), reason);
            return new ApnsSendResult(false, false, isInvalidTokenResponse(response.statusCode(), reason), reason);
        } catch (Exception exception) {
            log.warn("[APNs] push failed eventId={} reason={}", event.getId(), exception.getClass().getSimpleName());
            return new ApnsSendResult(false, false, false, exception.getMessage());
        }
    }

    private String endpoint(NotificationDevice device) {
        String env = device.getEnvironment() == null || device.getEnvironment().isBlank()
                ? properties.getEnv()
                : device.getEnvironment();
        String host = "production".equalsIgnoreCase(env)
                ? "https://api.push.apple.com"
                : "https://api.sandbox.push.apple.com";
        return host + "/3/device/" + device.getDeviceToken();
    }

    HttpRequest buildRequest(NotificationEvent event, NotificationDevice device, String token) {
        String body = """
                {"aps":{"alert":{"title":%s,"body":%s},"sound":"default"},"data":%s}
                """.formatted(jsonString(event.getTitle()), jsonString(event.getBody()), event.getPayload());
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(device)))
                .header("authorization", "bearer " + token)
                .header("apns-topic", properties.getBundleId())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private boolean isInvalidTokenResponse(int statusCode, String reason) {
        return statusCode == 410
                || reason.contains("BadDeviceToken")
                || reason.contains("Unregistered")
                || reason.contains("DeviceTokenNotForTopic");
    }

    private String jwt() throws Exception {
        String header = "{\"alg\":\"ES256\",\"kid\":\"%s\"}".formatted(properties.getKeyId());
        long issuedAt = Instant.now(applicationClock).getEpochSecond();
        String claims = "{\"iss\":\"%s\",\"iat\":%d}".formatted(properties.getTeamId(), issuedAt);
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
        String pem = properties.getPrivateKey()
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (!(key instanceof ECPrivateKey)) {
            throw new IllegalStateException("APNs private key is not an EC key");
        }
        return key;
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
}
