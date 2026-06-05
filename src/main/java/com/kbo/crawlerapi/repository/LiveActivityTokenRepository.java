package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveActivityTokenRepository extends JpaRepository<LiveActivityToken, UUID> {

    Optional<LiveActivityToken> findByActivityId(String activityId);

    Optional<LiveActivityToken> findByPlatformAndEnvironmentAndPushToken(String platform, String environment, String pushToken);

    List<LiveActivityToken> findByActiveTrue();
}
