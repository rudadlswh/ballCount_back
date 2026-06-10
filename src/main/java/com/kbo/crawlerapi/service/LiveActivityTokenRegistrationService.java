package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
        String activityId = blankToNull(command.activityId());
        String activityToken = requireText(command.activityToken(), "activityToken");
        String platform = normalizePlatform(command.platform());
        String environment = normalizeEnvironment(command.environment());
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        String publicGameId = blankToNull(command.publicGameId());
        String providerGameId = blankToNull(command.providerGameId());
        String databaseId = blankToNull(command.databaseId());
        String stableDetailIdentity = blankToNull(command.stableDetailIdentity());

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
                        blankToNull(command.installationId()),
                        blankToNull(command.favoriteTeamId()),
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
                blankToNull(command.installationId()),
                blankToNull(command.favoriteTeamId()),
                publicGameId,
                providerGameId,
                databaseId,
                stableDetailIdentity,
                now
        );
        List<LiveActivityToken> deactivatedTokens = deactivateSupersededTokens(
                token,
                environment,
                blankToNull(command.installationId()),
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
                "[LiveActivity] token registration {} activityId={} tokenPrefix={} publicGameId={} providerGameId={} databaseId={} stableDetailIdentity={} environment={} deactivatedSupersededCount={}",
                created ? "created" : "updated",
                activityId,
                tokenPrefix(activityToken),
                token.getPublicGameId(),
                token.getProviderGameId(),
                token.getDatabaseId(),
                token.getStableDetailIdentity(),
                environment,
                deactivatedTokens.size()
        );
        return new LiveActivityTokenRegistrationResult(token.getId(), activityId, environment, tokenPrefix(activityToken), token.isActive());
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
            boolean sameInstallationActivity = activityId != null && normalizedEquals(activityId, candidate.getActivityId());
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
        return normalizedEquals(candidate.getPublicGameId(), publicGameId)
                || normalizedEquals(candidate.getProviderGameId(), providerGameId)
                || normalizedEquals(candidate.getDatabaseId(), databaseId)
                || normalizedEquals(candidate.getStableDetailIdentity(), stableDetailIdentity);
    }

    private boolean normalizedEquals(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        return normalizedLeft != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
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
