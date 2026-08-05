package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceRegistrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-09T09:31:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private NotificationDeviceRepository notificationDeviceRepository;

    @Test
    void registersApnsTokenWithPlatformEnvironmentFavoriteTeamAndEnabledState() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("production"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("production"), eq("install-1")))
                .thenReturn(Optional.empty());

        var result = service.register("iOS", "production", " token-123 ", "install-1", "lg", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.getPlatform()).isEqualTo("ios");
        assertThat(device.getEnvironment()).isEqualTo("production");
        assertThat(device.getDeviceToken()).isEqualTo("token-123");
        assertThat(device.getFavoriteTeamId()).isEqualTo("lg");
        assertThat(device.isNotificationsEnabled()).isTrue();
        assertThat(result.environment()).isEqualTo("production");
        assertThat(result.maskedDeviceToken()).isEqualTo("****");
    }

    @Test
    void registersDetailedNotificationSettingsWhenProvided() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register(
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                "lg",
                true,
                new DeviceRegistrationService.DeviceNotificationSettings(false, true, false, true, true, true, true, true)
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.isGameStartEnabled()).isFalse();
        assertThat(device.isScoreChangeEnabled()).isTrue();
        assertThat(device.isLeadChangeEnabled()).isFalse();
        assertThat(device.isGameEndEnabled()).isTrue();
        assertThat(device.isOnBaseEnabled()).isTrue();
        assertThat(device.isInningChangeEnabled()).isTrue();
        assertThat(device.isFavoriteTeamOnlyEnabled()).isTrue();
        assertThat(device.isMuteWhenLosingEnabled()).isTrue();
    }

    @Test
    void missingDetailedNotificationSettingsUseServerDefaults() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register("ios", "sandbox", "token-123", "install-1", "lg", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.isGameStartEnabled()).isTrue();
        assertThat(device.isScoreChangeEnabled()).isTrue();
        assertThat(device.isLeadChangeEnabled()).isTrue();
        assertThat(device.isGameEndEnabled()).isTrue();
        assertThat(device.isOnBaseEnabled()).isFalse();
        assertThat(device.isInningChangeEnabled()).isFalse();
        assertThat(device.isFavoriteTeamOnlyEnabled()).isFalse();
        assertThat(device.isMuteWhenLosingEnabled()).isFalse();
    }

    @Test
    void registersRainDelayQuietHoursAndAuthorizationStatus() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("android"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("android"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register(
                "android", "sandbox", "token-123", "install-1", "lg", true,
                new DeviceRegistrationService.DeviceNotificationSettings(
                        true, true, true, true, false, false, false, false,
                        false, true, 22, 8, "authorized"
                )
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.isRainDelayEnabled()).isFalse();
        assertThat(device.isQuietHoursEnabled()).isTrue();
        assertThat(device.getQuietHoursStartHour()).isEqualTo(22);
        assertThat(device.getQuietHoursEndHour()).isEqualTo(8);
        assertThat(device.getNotificationAuthorizationStatus()).isEqualTo("authorized");
    }

    @Test
    void favoriteTeamRegistrationKeepsFavoriteTeamOnlyDisabledWhenClientSendsFalse() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register(
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                "lotte",
                true,
                new DeviceRegistrationService.DeviceNotificationSettings(true, true, true, true, true, true, false, false)
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().isFavoriteTeamOnlyEnabled()).isFalse();
    }

    @Test
    void missingFavoriteTeamDisablesFavoriteTeamOnlyEvenWhenClientSendsTrue() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register(
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                null,
                true,
                new DeviceRegistrationService.DeviceNotificationSettings(true, true, true, true, true, true, true, false)
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().isFavoriteTeamOnlyEnabled()).isFalse();
    }

    @Test
    void rejectsUnsupportedEnvironmentAlias() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);

        assertThatThrownBy(() -> service.register("ios", "development", "token-123", "install-1", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("environment must be sandbox or production");
    }

    @Test
    void registersAndroidPlatform() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("android"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("android"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        var result = service.register("android", "sandbox", "token-123", "install-1", null, false);

        assertThat(result.platform()).isEqualTo("android");
        verify(notificationDeviceRepository).save(any(NotificationDevice.class));
    }

    @Test
    void registersExplicitlyMonitoredAndroidGame() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("android"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("android"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register(
                "android", "sandbox", "token-123", "install-1", "lg", true,
                DeviceRegistrationService.DeviceNotificationSettings.defaults(),
                "20260721-LG-SSG"
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getMonitoredGameId()).isEqualTo("20260721-LG-SSG");
    }

    @Test
    void rejectsUnsupportedPlatform() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);

        assertThatThrownBy(() -> service.register("web", "sandbox", "token-123", "install-1", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform must be ios or android");
    }

    @Test
    void rejectsMissingInstallationId() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);

        assertThatThrownBy(() -> service.register("ios", "sandbox", "token-123", " ", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("installationId is required");
    }

    @Test
    void rejectsInvalidFavoriteTeamId() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);

        assertThatThrownBy(() -> service.register("ios", "sandbox", "token-123", "install-1", "invalid-team", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("favoriteTeamId is invalid");
    }

    @Test
    void rejectsOverlongDeviceToken() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);

        assertThatThrownBy(() -> service.register("ios", "sandbox", "a".repeat(513), "install-1", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deviceToken is too long");
    }

    @Test
    void duplicateInstallationRegistrationUpdatesExistingTokenRow() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                "old-token",
                "install-1",
                "lg",
                true,
                OffsetDateTime.parse("2026-04-09T09:00:00+09:00")
        );
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("new-token")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register("ios", "sandbox", "new-token", "install-1", "kia", false);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.getDeviceToken()).isEqualTo("new-token");
        assertThat(device.getInstallationId()).isEqualTo("install-1");
        assertThat(device.getFavoriteTeamId()).isEqualTo("kia");
        assertThat(device.isNotificationsEnabled()).isFalse();
        assertThat(device.getLastSeenAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    }

    @Test
    void registeringSameInstallationWithNewTokenUpdatesDeviceToken() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = device("ios", "production", "800facf43e22-old", "install-1", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("production"), eq("80e2a3c36237-new")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("production"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register("ios", "production", "80e2a3c36237-new", "install-1", "ssg", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getDeviceToken()).isEqualTo("80e2a3c36237-new");
    }

    @Test
    void sameInstallationIdInDifferentEnvironmentCreatesSeparateRegistration() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("production"), eq("prod-token")))
                .thenReturn(Optional.empty());
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("production"), eq("install-1")))
                .thenReturn(Optional.empty());

        service.register("ios", "production", "prod-token", "install-1", "ssg", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getEnvironment()).isEqualTo("production");
        assertThat(deviceCaptor.getValue().getDeviceToken()).isEqualTo("prod-token");
        verify(notificationDeviceRepository, never())
                .findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1"));
    }

    @Test
    void registeringSameInstallationWithNewFavoriteTeamUpdatesFavoriteTeamId() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = device("ios", "sandbox", "token-123", "install-1", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.of(existing));
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register("ios", "sandbox", "token-123", "install-1", "kia", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getFavoriteTeamId()).isEqualTo("kia");
    }

    @Test
    void registeringSameTokenRefreshesLastSeenAt() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = device("ios", "sandbox", "token-123", "install-1", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.of(existing));
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register("ios", "sandbox", "token-123", "install-1", "ssg", true);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getLastSeenAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    }

    @Test
    void disabledNotificationsUpdateNotificationsEnabledFalse() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = device("ios", "sandbox", "token-123", "install-1", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.of(existing));
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register("ios", "sandbox", "token-123", "install-1", "ssg", false);

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().isNotificationsEnabled()).isFalse();
    }

    @Test
    void updateRequestModifiesDetailedNotificationSettings() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = device("ios", "sandbox", "token-123", "install-1", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.of(existing));
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(eq("ios"), eq("sandbox"), eq("install-1")))
                .thenReturn(Optional.of(existing));

        service.register(
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                "ssg",
                true,
                new DeviceRegistrationService.DeviceNotificationSettings(true, false, false, true, true, false, true, false)
        );

        ArgumentCaptor<NotificationDevice> deviceCaptor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(notificationDeviceRepository).save(deviceCaptor.capture());
        NotificationDevice device = deviceCaptor.getValue();
        assertThat(device.isGameStartEnabled()).isTrue();
        assertThat(device.isScoreChangeEnabled()).isFalse();
        assertThat(device.isLeadChangeEnabled()).isFalse();
        assertThat(device.isGameEndEnabled()).isTrue();
        assertThat(device.isOnBaseEnabled()).isTrue();
        assertThat(device.isInningChangeEnabled()).isFalse();
        assertThat(device.isFavoriteTeamOnlyEnabled()).isTrue();
        assertThat(device.isMuteWhenLosingEnabled()).isFalse();
    }

    private NotificationDevice device(String platform, String environment, String token, String installationId, String favoriteTeamId, boolean notificationsEnabled) {
        return new NotificationDevice(
                UUID.randomUUID(),
                platform,
                environment,
                token,
                installationId,
                favoriteTeamId,
                notificationsEnabled,
                OffsetDateTime.parse("2026-04-09T09:00:00+09:00")
        );
    }
}
