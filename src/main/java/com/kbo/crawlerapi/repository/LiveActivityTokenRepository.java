package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveActivityTokenRepository extends JpaRepository<LiveActivityToken, UUID> {

    Optional<LiveActivityToken> findByPlatformAndEnvironmentAndActivityToken(
            String platform, String environment, String activityToken);

    List<LiveActivityToken> findByActiveTrue();

}
