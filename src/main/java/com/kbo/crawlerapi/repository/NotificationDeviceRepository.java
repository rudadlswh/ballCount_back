package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.kbo.crawlerapi.domain.NotificationDevice;

public interface NotificationDeviceRepository extends JpaRepository<NotificationDevice, UUID> {

    Optional<NotificationDevice> findByPlatformAndEnvironmentAndDeviceToken(String platform, String environment, String deviceToken);

    Optional<NotificationDevice> findByPlatformAndEnvironmentAndInstallationId(String platform, String environment, String installationId);

    List<NotificationDevice> findByInstallationId(String installationId);

    Optional<NotificationDevice> findTopByInstallationIdAndEnvironmentOrderByUpdatedAtDesc(String installationId, String environment);

    List<NotificationDevice> findByPlatformAndNotificationsEnabledTrue(String platform);

    @Query("""
            SELECT device
            FROM NotificationDevice device
            WHERE lower(device.platform) = lower(:platform)
              AND lower(device.environment) = lower(:environment)
              AND (
                  device.favoriteTeamOnlyEnabled = false
                  OR (
                      device.favoriteTeamId IS NOT NULL
                      AND lower(device.favoriteTeamId) IN :favoriteTeamIds
                  )
              )
            """)
    List<NotificationDevice> findDeliveryTargets(
            @Param("platform") String platform,
            @Param("environment") String environment,
            @Param("favoriteTeamIds") List<String> favoriteTeamIds
    );

    List<NotificationDevice> findByFavoriteTeamIdIn(List<String> favoriteTeamIds);

    List<NotificationDevice> findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(String platform, String favoriteTeamId);
}
