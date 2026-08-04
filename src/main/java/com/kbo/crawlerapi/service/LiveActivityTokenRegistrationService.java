package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import com.kbo.crawlerapi.support.RegistrationInputNormalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

        Optional<LiveActivityToken> registrationMatch = liveActivityTokenRepository.findRegistrationMatches(
                        environment,
                        activityToken,
                        publicGameId,
                        providerGameId,
                        databaseId,
                        stableDetailIdentity
                )
                .stream()
                .findFirst();
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
        List<LiveActivityToken> deactivatedTokens = deactivateSupersededTokens(
                token,
                environment,
                installationId,
                activityId,
                publicGameId,
                providerGameId,
                databaseId,
                stableDetailIdentity,
                now
        );
        liveActivityTokenRepository.save(token);
        if (!deactivatedTokens.isEmpty()) {
            liveActivityTokenRepository.saveAll(deactivatedTokens);
        }
        log.info(
                "[LiveActivity] token registration {} tokenHash={} environment={} deactivatedSupersededCount={}",
                created ? "created" : "updated",
                RegistrationInputNormalizer.tokenFingerprint(activityToken),
                environment,
                deactivatedTokens.size()
        );
        return new LiveActivityTokenRegistrationResult(token.getId(), activityId, environment, RegistrationInputNormalizer.tokenPrefix(activityToken), token.isActive());
    }

    private List<LiveActivityToken> deactivateSupersededTokens(
            LiveActivityToken current,
            String environment,
            String installationId,
            String activityId,
            String publicGameId,
            String providerGameId,
            String databaseId,
            String stableDetailIdentity,
            OffsetDateTime now
    ) {
        if (installationId == null) {
            return List.of();
        }
        List<LiveActivityToken> candidates = new ArrayList<>();
        if (activityId != null) {
            addAllIfPresent(candidates, liveActivityTokenRepository.findByActiveTrueAndEnvironmentAndInstallationIdAndActivityId(
                    environment,
                    installationId,
                    activityId
            ));
        }
        addAllIfPresent(candidates, liveActivityTokenRepository.findByActiveTrueAndEnvironmentAndInstallationId(environment, installationId));

        List<LiveActivityToken> deactivated = new ArrayList<>();
        for (LiveActivityToken candidate : candidates) {
            if (current.getId().equals(candidate.getId()) || !candidate.isActive()) {
                continue;
            }
            boolean sameInstallationActivity = activityId != null && RegistrationInputNormalizer.normalizedEquals(activityId, candidate.getActivityId());
            boolean sameGame = registrationGameMatches(candidate, publicGameId, providerGameId, databaseId, stableDetailIdentity);
            if (sameInstallationActivity || sameGame) {
                candidate.disable(now);
                deactivated.add(candidate);
            }
        }
        return deactivated;
    }

    private void addAllIfPresent(List<LiveActivityToken> target, List<LiveActivityToken> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private boolean registrationGameMatches(
            LiveActivityToken candidate,
            String publicGameId,
            String providerGameId,
            String databaseId,
            String stableDetailIdentity
    ) {
        return RegistrationInputNormalizer.normalizedEquals(candidate.getPublicGameId(), publicGameId)
                || RegistrationInputNormalizer.normalizedEquals(candidate.getProviderGameId(), providerGameId)
                || RegistrationInputNormalizer.normalizedEquals(candidate.getDatabaseId(), databaseId)
                || RegistrationInputNormalizer.normalizedEquals(candidate.getStableDetailIdentity(), stableDetailIdentity);
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
