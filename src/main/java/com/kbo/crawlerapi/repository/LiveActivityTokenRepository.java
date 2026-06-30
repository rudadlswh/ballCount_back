package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LiveActivityTokenRepository extends JpaRepository<LiveActivityToken, UUID> {

    @Query("""
            select token
            from LiveActivityToken token
            where token.environment = :environment
              and token.activityToken = :activityToken
              and (:publicGameId is null or token.publicGameId = :publicGameId)
              and (:providerGameId is null or token.providerGameId = :providerGameId)
              and (:databaseId is null or token.databaseId = :databaseId)
              and (:stableDetailIdentity is null or token.stableDetailIdentity = :stableDetailIdentity)
            order by token.updatedAt desc
            """)
    List<LiveActivityToken> findRegistrationMatches(
            @Param("environment") String environment,
            @Param("activityToken") String activityToken,
            @Param("publicGameId") String publicGameId,
            @Param("providerGameId") String providerGameId,
            @Param("databaseId") String databaseId,
            @Param("stableDetailIdentity") String stableDetailIdentity
    );

    List<LiveActivityToken> findByActiveTrue();

    @Query("""
            select token
            from LiveActivityToken token
            where token.active = true
              and (
                (:publicGameId is not null and lower(token.publicGameId) = lower(:publicGameId))
                or (:providerGameId is not null and lower(token.providerGameId) = lower(:providerGameId))
                or (:databaseId is not null and lower(token.databaseId) = lower(:databaseId))
                or (:stableProviderIdentity is not null and lower(token.stableDetailIdentity) = lower(:stableProviderIdentity))
                or (:stablePublicIdentity is not null and lower(token.stableDetailIdentity) = lower(:stablePublicIdentity))
              )
            """)
    List<LiveActivityToken> findActiveMatchesForGame(
            @Param("publicGameId") String publicGameId,
            @Param("providerGameId") String providerGameId,
            @Param("databaseId") String databaseId,
            @Param("stableProviderIdentity") String stableProviderIdentity,
            @Param("stablePublicIdentity") String stablePublicIdentity
    );

    List<LiveActivityToken> findByActiveTrueAndEnvironmentAndInstallationIdAndActivityId(
            String environment,
            String installationId,
            String activityId
    );

    List<LiveActivityToken> findByActiveTrueAndEnvironmentAndInstallationId(
            String environment,
            String installationId
    );
}
