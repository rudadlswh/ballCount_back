package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveActivityPushToStartTokenRepository extends JpaRepository<LiveActivityPushToStartToken, UUID> {

    Optional<LiveActivityPushToStartToken> findByPlatformAndEnvironmentAndPushToStartToken(
            String platform,
            String environment,
            String pushToStartToken
    );

    Optional<LiveActivityPushToStartToken> findByPlatformAndEnvironmentAndInstallationId(
            String platform,
            String environment,
            String installationId
    );

    List<LiveActivityPushToStartToken> findByActiveTrue();
}
