package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.NotificationDevice;

public interface NotificationDeviceRepository extends JpaRepository<NotificationDevice, UUID> {

    Optional<NotificationDevice> findByInstallationId(String installationId);

    Optional<NotificationDevice> findByPlatformAndEnvironmentAndDeviceToken(String platform, String environment, String deviceToken);

    Optional<NotificationDevice> findByPlatformAndEnvironmentAndInstallationId(String platform, String environment, String installationId);

    List<NotificationDevice> findByPlatformAndNotificationsEnabledTrue(String platform);
}
