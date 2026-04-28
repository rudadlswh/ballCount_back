package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.NotificationDevice;

public interface NotificationDeviceRepository extends JpaRepository<NotificationDevice, UUID> {

    Optional<NotificationDevice> findByPlatformAndDeviceToken(String platform, String deviceToken);

    Optional<NotificationDevice> findByPlatformAndInstallationId(String platform, String installationId);

    List<NotificationDevice> findByPlatformAndNotificationsEnabledTrue(String platform);
}
