package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import com.kbo.crawlerapi.support.RegistrationInputNormalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveActivityTokenRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityTokenRegistrationService.class);
    private static final int MAX_ACTIVITY_ID_LENGTH = 120;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_INSTALLATION_ID_LENGTH = 100;
    private static final int MAX_FAVORITE_TEAM_ID_LENGTH = 30;
    private static final int MAX_PUBLIC_GAME_ID_LENGTH = 80;
    private static final int MAX_PROVIDER_GAME_ID_LENGTH = 100;
    private static final int MAX_DATABASE_ID_LENGTH = 80;
    private static final int MAX_STABLE_DETAIL_IDENTITY_LENGTH = 180;

    private final LiveActivityTokenRepository liveActivityTokenRepository;
    private final Clock applicationClock;

    public LiveActivityTokenRegistrationService(LiveActivityTokenRepository liveActivityTokenRepository, Clock applicationClock) {
        this.liveActivityTokenRepository = liveActivityTokenRepository;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public LiveActivityTokenRegistrationResult register(LiveActivityTokenRegistrationCommand command) {
        String activityId = RegistrationInputNormalizer.requireText(command.activityId(), "activityId", MAX_ACTIVITY_ID_LENGTH);
        String activityToken = RegistrationInputNormalizer.requireText(command.activityToken(), "activityToken", MAX_TOKEN_LENGTH);
        String platform = RegistrationInputNormalizer.normalizeIosPlatform(command.platform());
        String environment = RegistrationInputNormalizer.normalizeClientEnvironment(command.environment());
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        String installationId = RegistrationInputNormalizer.requireText(command.installationId(), "installationId", MAX_INSTALLATION_ID_LENGTH);
        String favoriteTeamId = RegistrationInputNormalizer.normalizeFavoriteTeamId(command.favoriteTeamId(), MAX_FAVORITE_TEAM_ID_LENGTH);
        String publicGameId = RegistrationInputNormalizer.optionalText(command.publicGameId(), "publicGameId", MAX_PUBLIC_GAME_ID_LENGTH);
        String providerGameId = RegistrationInputNormalizer.optionalText(command.providerGameId(), "providerGameId", MAX_PROVIDER_GAME_ID_LENGTH);
        String databaseId = RegistrationInputNormalizer.optionalText(command.databaseId(), "databaseId", MAX_DATABASE_ID_LENGTH);
        String stableDetailIdentity = RegistrationInputNormalizer.optionalText(command.stableDetailIdentity(), "stableDetailIdentity", MAX_STABLE_DETAIL_IDENTITY_LENGTH);

        Optional<LiveActivityToken> registrationMatch = liveActivityTokenRepository
                .findByPlatformAndEnvironmentAndActivityToken(platform, environment, activityToken);
        boolean created = registrationMatch.isEmpty();
        LiveActivityToken token = registrationMatch
                .orElseGet(() -> new LiveActivityToken(
                        UUID.randomUUID(),
                        activityId,
                        platform,
                        environment,
                        activityToken,
                        installationId,
                        favoriteTeamId,
                        publicGameId,
                        providerGameId,
                        databaseId,
                        stableDetailIdentity,
                        now
                ));
        token.update(
                activityId,
                platform,
                environment,
                activityToken,
                installationId,
                favoriteTeamId,
                publicGameId,
                providerGameId,
                databaseId,
                stableDetailIdentity,
                now
        );
        liveActivityTokenRepository.save(token);
        log.info(
                "[LiveActivity] token registration {} tokenHash={} environment={}",
                created ? "created" : "updated",
                RegistrationInputNormalizer.tokenFingerprint(activityToken),
                environment
        );
        return new LiveActivityTokenRegistrationResult(token.getId(), activityId, environment, RegistrationInputNormalizer.tokenPrefix(activityToken), token.isActive());
    }

    public record LiveActivityTokenRegistrationCommand(
            String activityId,
            String platform,
            String environment,
            String activityToken,
            String installationId,
            String favoriteTeamId,
            String publicGameId,
            String providerGameId,
            String databaseId,
            String stableDetailIdentity
    ) {
    }

    public record LiveActivityTokenRegistrationResult(
            UUID id,
            String activityId,
            String environment,
            String tokenPrefix,
            boolean active
    ) {
    }
}
