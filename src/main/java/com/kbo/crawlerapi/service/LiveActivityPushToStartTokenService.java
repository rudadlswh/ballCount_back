package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import com.kbo.crawlerapi.repository.LiveActivityPushToStartTokenRepository;
import com.kbo.crawlerapi.support.TeamCatalog;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveActivityPushToStartTokenService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityPushToStartTokenService.class);
    private static final int TOKEN_PREFIX_LENGTH = 8;

    private final LiveActivityPushToStartTokenRepository repository;
    private final LiveActivityContentStateBuilder contentStateBuilder;
    private final ApnsPushService apnsPushService;
    private final Clock applicationClock;

    public LiveActivityPushToStartTokenService(
            LiveActivityPushToStartTokenRepository repository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock
    ) {
        this.repository = repository;
        this.contentStateBuilder = contentStateBuilder;
        this.apnsPushService = apnsPushService;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public PushToStartTokenRegistrationResult register(PushToStartTokenRegistrationCommand command) {
        String platform = normalizePlatform(command.platform());
        String environment = normalizeEnvironment(command.environment());
        String pushToStartToken = requireText(command.pushToStartToken(), "pushToStartToken");
        String installationId = requireText(command.installationId(), "installationId");
        String favoriteTeamId = normalizeFavoriteTeamId(command.favoriteTeamId());
        OffsetDateTime now = OffsetDateTime.now(applicationClock);

        Optional<LiveActivityPushToStartToken> tokenMatch = repository.findByPlatformAndEnvironmentAndPushToStartToken(
                platform,
                environment,
                pushToStartToken
        );
        Optional<LiveActivityPushToStartToken> installationMatch = repository.findByPlatformAndEnvironmentAndInstallationId(
                platform,
                environment,
                installationId
        );

        LiveActivityPushToStartToken token = installationMatch
                .or(() -> tokenMatch)
                .orElseGet(() -> new LiveActivityPushToStartToken(UUID.randomUUID(), now));
        tokenMatch
                .filter(existing -> !existing.getId().equals(token.getId()))
                .ifPresent(existing -> {
                    repository.delete(existing);
                    repository.flush();
                });
        token.update(
                platform,
                environment,
                pushToStartToken,
                installationId,
                favoriteTeamId,
                command.notificationsAuthorized(),
                command.liveActivitiesEnabled(),
                command.liveActivityAutoStartEnabled(),
                command.gameStartEnabled(),
                command.favoriteTeamOnlyEnabled(),
                now
        );
        repository.save(token);
        log.info(
                "[LiveActivityStart] push-to-start token registered tokenPrefix={} installationId={} favoriteTeamId={} environment={} notificationsAuthorized={} liveActivitiesEnabled={} autoStartEnabled={}",
                tokenPrefix(pushToStartToken),
                installationId,
                favoriteTeamId,
                environment,
                command.notificationsAuthorized(),
                command.liveActivitiesEnabled(),
                command.liveActivityAutoStartEnabled()
        );
        return new PushToStartTokenRegistrationResult(token.getId(), environment, tokenPrefix(pushToStartToken), token.isActive());
    }

    @Transactional
    public LiveActivityStartDeliveryResult deliverStart(Game game) {
        List<LiveActivityPushToStartToken> tokens = repository.findByActiveTrue();
        if (tokens.isEmpty()) {
            log.info("[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} reason=no_push_to_start_token", game.getPublicGameId(), game.getProviderGameId(), game.getId());
            return new LiveActivityStartDeliveryResult(0, 1, 0);
        }

        String gameKey = gameKey(game);
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (LiveActivityPushToStartToken token : tokens) {
            String skipReason = startSkipReason(game, token, gameKey);
            if (skipReason != null) {
                skipped++;
                log.info(
                        "[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} installationId={} tokenPrefix={} reason={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        token.getInstallationId(),
                        tokenPrefix(token.getPushToStartToken()),
                        skipReason
                );
                continue;
            }

            Map<String, Object> attributes = buildAttributes(game, token.getFavoriteTeamId());
            Map<String, Object> contentState = contentStateBuilder.buildForFavoriteTeam(game, token.getFavoriteTeamId(), null);
            ApnsPushService.ApnsSendResult result = apnsPushService.sendLiveActivityStart(game, token, attributes, contentState);
            if (result.sent()) {
                sent++;
                token.markStartDelivered(gameKey, OffsetDateTime.now(applicationClock));
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
                if (result.invalidToken()) {
                    token.disable(OffsetDateTime.now(applicationClock));
                }
            }
            log.info(
                    "[LiveActivityStart] APNs token result publicGameId={} providerGameId={} databaseId={} installationId={} sent={} skipped={} failed={} reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    token.getInstallationId(),
                    result.sent() ? 1 : 0,
                    result.skipped() ? 1 : 0,
                    !result.sent() && !result.skipped() ? 1 : 0,
                    result.reason()
            );
        }
        return new LiveActivityStartDeliveryResult(sent, skipped, failed);
    }

    private String startSkipReason(Game game, LiveActivityPushToStartToken token, String gameKey) {
        if (!"ios".equalsIgnoreCase(token.getPlatform())) {
            return "unsupported_os_or_capability";
        }
        if (!token.isNotificationsAuthorized()) {
            return "notifications_disabled";
        }
        if (!token.isLiveActivitiesEnabled() || !token.isLiveActivityAutoStartEnabled() || !token.isGameStartEnabled()) {
            return "live_activity_auto_start_disabled";
        }
        if (!apnsPushService.environmentMatches(token.getEnvironment())) {
            return "apns_bad_environment";
        }
        if (!involvesFavoriteTeam(game, token.getFavoriteTeamId())) {
            return "favorite_team_mismatch";
        }
        if (gameKey.equals(token.getLastStartedGameKey())) {
            return "duplicate_active_activity";
        }
        return null;
    }

    private boolean involvesFavoriteTeam(Game game, String favoriteTeamId) {
        if (favoriteTeamId == null || favoriteTeamId.isBlank()) {
            return false;
        }
        return favoriteTeamId.equalsIgnoreCase(game.getAwayTeam().getTeamCode())
                || favoriteTeamId.equalsIgnoreCase(game.getHomeTeam().getTeamCode());
    }

    private Map<String, Object> buildAttributes(Game game, String favoriteTeamId) {
        boolean favoriteIsAway = favoriteTeamId != null && favoriteTeamId.equalsIgnoreCase(game.getAwayTeam().getTeamCode());
        var favoriteTeam = favoriteIsAway ? game.getAwayTeam() : game.getHomeTeam();
        var opponentTeam = favoriteIsAway ? game.getHomeTeam() : game.getAwayTeam();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("gameID", game.getId().toString());
        attributes.put("favoriteTeamID", favoriteTeam.getTeamCode());
        attributes.put("favoriteTeamName", favoriteTeam.getName());
        attributes.put("favoriteTeamShortName", favoriteTeam.getShortName());
        attributes.put("opponentTeamID", opponentTeam.getTeamCode());
        attributes.put("opponentTeamName", opponentTeam.getName());
        attributes.put("opponentTeamShortName", opponentTeam.getShortName());
        attributes.put("venue", game.getStadium() == null ? "" : game.getStadium());
        attributes.put("isHomeGame", !favoriteIsAway);
        return attributes;
    }

    private String gameKey(Game game) {
        if (game.getPublicGameId() != null && !game.getPublicGameId().isBlank()) {
            return "public:" + game.getPublicGameId().trim().toLowerCase(Locale.ROOT);
        }
        if (game.getProviderGameId() != null && !game.getProviderGameId().isBlank()) {
            return "provider:" + game.getProviderGameId().trim();
        }
        return "database:" + game.getId();
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

    private String normalizeFavoriteTeamId(String favoriteTeamId) {
        String normalized = blankToNull(favoriteTeamId);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!TeamCatalog.isSupportedTeamCode(normalized)) {
            throw new IllegalArgumentException("favoriteTeamId is invalid");
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
        return token == null ? null : token.substring(0, Math.min(TOKEN_PREFIX_LENGTH, token.length()));
    }

    public record PushToStartTokenRegistrationCommand(
            String platform,
            String environment,
            String pushToStartToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsAuthorized,
            boolean liveActivitiesEnabled,
            boolean liveActivityAutoStartEnabled,
            boolean gameStartEnabled,
            boolean favoriteTeamOnlyEnabled
    ) {
    }

    public record PushToStartTokenRegistrationResult(
            UUID id,
            String environment,
            String tokenPrefix,
            boolean active
    ) {
    }

    public record LiveActivityStartDeliveryResult(int sentCount, int skippedCount, int failedCount) {
    }
}
