package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveActivityTokenRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityTokenRegistrationService.class);

    private final LiveActivityTokenRepository liveActivityTokenRepository;
    private final Clock applicationClock;

    public LiveActivityTokenRegistrationService(LiveActivityTokenRepository liveActivityTokenRepository, Clock applicationClock) {
        this.liveActivityTokenRepository = liveActivityTokenRepository;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public LiveActivityTokenRegistrationResult register(LiveActivityTokenRegistrationCommand command) {
        String activityId = requireText(command.activityId(), "activityId");
        String pushToken = requireText(command.pushToken(), "pushToken");
        String platform = normalizePlatform(command.platform());
        String environment = normalizeEnvironment(command.environment());
        OffsetDateTime now = OffsetDateTime.now(applicationClock);

        Optional<LiveActivityToken> activityMatch = liveActivityTokenRepository.findByActivityId(activityId);
        Optional<LiveActivityToken> tokenMatch = liveActivityTokenRepository.findByPlatformAndEnvironmentAndPushToken(platform, environment, pushToken);
        boolean created = activityMatch.isEmpty() && tokenMatch.isEmpty();
        LiveActivityToken token = activityMatch
                .or(() -> tokenMatch)
                .orElseGet(() -> new LiveActivityToken(
                        UUID.randomUUID(),
                        activityId,
                        platform,
                        environment,
                        pushToken,
                        blankToNull(command.installationId()),
                        blankToNull(command.favoriteTeamId()),
                        blankToNull(command.publicGameId()),
                        blankToNull(command.providerGameId()),
                        blankToNull(command.databaseId()),
                        blankToNull(command.stableDetailIdentity()),
                        now
                ));
        tokenMatch
                .filter(matched -> !matched.getId().equals(token.getId()))
                .ifPresent(matched -> {
                    liveActivityTokenRepository.delete(matched);
                    liveActivityTokenRepository.flush();
        });
        token.update(
                activityId,
                platform,
                environment,
                pushToken,
                blankToNull(command.installationId()),
                blankToNull(command.favoriteTeamId()),
                blankToNull(command.publicGameId()),
                blankToNull(command.providerGameId()),
                blankToNull(command.databaseId()),
                blankToNull(command.stableDetailIdentity()),
                now
        );
        liveActivityTokenRepository.save(token);
        log.info(
                "[LiveActivity] token registration {} activityId={} tokenPrefix={} publicGameId={} providerGameId={} databaseId={} stableDetailIdentity={} environment={}",
                created ? "created" : "updated",
                activityId,
                tokenPrefix(pushToken),
                token.getPublicGameId(),
                token.getProviderGameId(),
                token.getDatabaseId(),
                token.getStableDetailIdentity(),
                environment
        );
        return new LiveActivityTokenRegistrationResult(token.getId(), activityId, environment, tokenPrefix(pushToken), token.isActive());
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "ios";
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if ("development".equals(normalized) || "debug".equals(normalized)) {
            return "sandbox";
        }
        if ("release".equals(normalized)) {
            return "production";
        }
        if (!"sandbox".equals(normalized) && !"production".equals(normalized)) {
            throw new IllegalArgumentException("environment must be sandbox or production");
        }
        return normalized;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String tokenPrefix(String token) {
        return token.substring(0, Math.min(12, token.length()));
    }

    public record LiveActivityTokenRegistrationCommand(
            String activityId,
            String platform,
            String environment,
            String pushToken,
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
