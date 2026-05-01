package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApnsPushServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-09T09:31:00Z"), ZoneId.of("Asia/Seoul"));

    @TempDir
    private Path tempDir;

    @Test
    void pushDisabledSkipsWithExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        var result = service.send(event(), device("ios", "sandbox", true));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.APNS_PUSH_DISABLED);
    }

    @Test
    void missingConfigSkipsWithExplicitReason() {
        ApnsProperties properties = new ApnsProperties();
        properties.setPushEnabled(true);
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        var result = service.send(event(), device("ios", "sandbox", true));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.APNS_CONFIG_MISSING);
    }

    @Test
    void invalidPrivateKeySkipsWithExplicitReason() {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKey("not-a-private-key");
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        var result = service.send(event(), device("ios", "sandbox", true));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.APNS_PRIVATE_KEY_INVALID);
    }

    @Test
    void validP8PathLoads() throws Exception {
        ApnsProperties properties = configuredProperties();
        Path keyPath = tempDir.resolve("AuthKey_TEST.p8");
        Files.writeString(keyPath, validPem(), StandardCharsets.UTF_8);
        properties.setPrivateKeyPath(keyPath.toString());
        properties.setPrivateKey("not-used-when-path-present");
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        assertThat(service.readinessSkipReason()).isNull();
    }

    @Test
    void missingPrivateKeyPathMapsToPathNotFound() {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKeyPath(tempDir.resolve("missing.p8").toString());
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        assertThat(service.readinessSkipReason()).isEqualTo(ApnsPushService.APNS_PRIVATE_KEY_PATH_NOT_FOUND);
    }

    @Test
    void escapedNewlineInlineKeyNormalizes() throws Exception {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKey(validPem().replace("\n", "\\n"));
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        assertThat(service.readinessSkipReason()).isNull();
    }

    @Test
    void quotedInlineKeyNormalizes() throws Exception {
        ApnsProperties properties = configuredProperties();
        properties.setPrivateKey("\"" + validPem().replace("\n", "\\n") + "\"");
        ApnsPushService service = new ApnsPushService(properties, CLOCK);

        assertThat(service.readinessSkipReason()).isNull();
    }

    @Test
    void disabledDeviceSkipsWithExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        var result = service.send(event(), device("ios", "sandbox", false));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED);
    }

    @Test
    void environmentMismatchSkipsWithExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        var result = service.send(event(), device("ios", "production", true));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.ENVIRONMENT_MISMATCH);
    }

    @Test
    void unsupportedPlatformSkipsWithExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        var result = service.send(event(), device("android", "sandbox", true));

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).isEqualTo(ApnsPushService.UNSUPPORTED_PLATFORM);
    }

    @Test
    void invalidProviderTokenResponseMapsToExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        String reason = service.mapApnsFailureReason("{\"reason\":\"InvalidProviderToken\"}");

        assertThat(reason).isEqualTo(ApnsPushService.APNS_INVALID_PROVIDER_TOKEN);
    }

    @Test
    void badDeviceTokenResponseMapsToExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), CLOCK);

        String reason = service.mapApnsFailureReason("{\"reason\":\"BadDeviceToken\"}");

        assertThat(reason).isEqualTo(ApnsPushService.APNS_BAD_DEVICE_TOKEN);
    }

    private ApnsProperties configuredProperties() {
        ApnsProperties properties = new ApnsProperties();
        properties.setPushEnabled(true);
        properties.setTeamId("TEAMID1234");
        properties.setKeyId("KEYID1234");
        properties.setBundleId("com.example.kbo");
        properties.setEnv("sandbox");
        return properties;
    }

    private NotificationDevice device(String platform, String environment, boolean notificationsEnabled) {
        return new NotificationDevice(
                UUID.randomUUID(),
                platform,
                environment,
                "token-123",
                "install-1",
                "kia",
                notificationsEnabled,
                OffsetDateTime.now(CLOCK)
        );
    }

    private NotificationEvent event() {
        return new NotificationEvent(UUID.randomUUID(), game(), "SCORE_CHANGED", "event-key", "title", "body", "{}");
    }

    private String validPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private Game game() {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG Twins", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                "20260409-LG-KIA",
                "kbo",
                "20260409HTLG0",
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "Seoul",
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                0,
                1,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }
}
