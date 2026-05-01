package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    void developmentEnvironmentAliasesToSandbox() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        when(notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(eq("ios"), eq("sandbox"), eq("token-123")))
                .thenReturn(Optional.empty());

        service.register("ios", "development", "token-123", null, null, false);

        verify(notificationDeviceRepository).save(any(NotificationDevice.class));
    }

    @Test
    void duplicateInstallationRegistrationUpdatesExistingTokenRow() {
        DeviceRegistrationService service = new DeviceRegistrationService(notificationDeviceRepository, CLOCK);
        NotificationDevice existing = new NotificationDevice(
                java.util.UUID.randomUUID(),
                "ios",
                "sandbox",
                "old-token",
                "install-1",
                "lg",
                true,
                java.time.OffsetDateTime.parse("2026-04-09T09:00:00+09:00")
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
    }
}
