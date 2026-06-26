package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import com.kbo.crawlerapi.support.TeamCatalog;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        String activityId = requireText(command.activityId(), "activityId", MAX_ACTIVITY_ID_LENGTH);
        String activityToken = requireText(command.activityToken(), "activityToken", MAX_TOKEN_LENGTH);
        String platform = normalizePlatform(command.platform());
        String environment = normalizeEnvironment(command.environment());
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        String installationId = requireText(command.installationId(), "installationId", MAX_INSTALLATION_ID_LENGTH);
        String favoriteTeamId = normalizeFavoriteTeamId(command.favoriteTeamId());
        String publicGameId = optionalText(command.publicGameId(), "publicGameId", MAX_PUBLIC_GAME_ID_LENGTH);
        String providerGameId = optionalText(command.providerGameId(), "providerGameId", MAX_PROVIDER_GAME_ID_LENGTH);
        String databaseId = optionalText(command.databaseId(), "databaseId", MAX_DATABASE_ID_LENGTH);
        String stableDetailIdentity = optionalText(command.stableDetailIdentity(), "stableDetailIdentity", MAX_STABLE_DETAIL_IDENTITY_LENGTH);

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
                tokenFingerprint(activityToken),
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
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!"ios".equals(normalized)) {
            throw new IllegalArgumentException("platform must be ios");
        }
        return normalized;
    }

    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (!"sandbox".equals(normalized) && !"production".equals(normalized)) {
            throw new IllegalArgumentException("environment must be sandbox or production");
        }
        return normalized;
    }

    private String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    private String optionalText(String value, String name, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    private String normalizeFavoriteTeamId(String favoriteTeamId) {
        String normalized = optionalText(favoriteTeamId, "favoriteTeamId", MAX_FAVORITE_TEAM_ID_LENGTH);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!TeamCatalog.isSupportedTeamCode(normalized)) {
            throw new IllegalArgumentException("favoriteTeamId is invalid");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String tokenPrefix(String token) {
        return token.substring(0, Math.min(8, token.length()));
    }

    private String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Math.min(6, digest.length); index++) {
                builder.append(String.format("%02x", digest[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
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
