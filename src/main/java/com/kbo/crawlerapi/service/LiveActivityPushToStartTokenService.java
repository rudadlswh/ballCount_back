package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import com.kbo.crawlerapi.repository.LiveActivityPushToStartTokenRepository;
import com.kbo.crawlerapi.support.RegistrationInputNormalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LiveActivityPushToStartTokenService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityPushToStartTokenService.class);
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_INSTALLATION_ID_LENGTH = 100;
    private static final int MAX_FAVORITE_TEAM_ID_LENGTH = 30;

    private final LiveActivityPushToStartTokenRepository repository;
    private final LiveActivityContentStateBuilder contentStateBuilder;
    private final ApnsPushService apnsPushService;
    private final Clock applicationClock;
    private final TransactionTemplate transactionTemplate;

    public LiveActivityPushToStartTokenService(
            LiveActivityPushToStartTokenRepository repository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock
    ) {
        this(repository, contentStateBuilder, apnsPushService, applicationClock, null);
    }

    @Autowired
    public LiveActivityPushToStartTokenService(
            LiveActivityPushToStartTokenRepository repository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.contentStateBuilder = contentStateBuilder;
        this.apnsPushService = apnsPushService;
        this.applicationClock = applicationClock;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Transactional
    public PushToStartTokenRegistrationResult register(PushToStartTokenRegistrationCommand command) {
        String platform = RegistrationInputNormalizer.normalizeIosPlatform(command.platform());
        String environment = RegistrationInputNormalizer.normalizeClientEnvironment(command.environment());
        String pushToStartToken = RegistrationInputNormalizer.requireText(command.pushToStartToken(), "pushToStartToken", MAX_TOKEN_LENGTH);
        String installationId = RegistrationInputNormalizer.requireText(command.installationId(), "installationId", MAX_INSTALLATION_ID_LENGTH);
        String favoriteTeamId = RegistrationInputNormalizer.normalizeFavoriteTeamId(command.favoriteTeamId(), MAX_FAVORITE_TEAM_ID_LENGTH);
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
                "[LiveActivityStart] push-to-start token registered tokenHash={} environment={} notificationsAuthorized={} liveActivitiesEnabled={} autoStartEnabled={}",
                RegistrationInputNormalizer.tokenFingerprint(pushToStartToken),
                environment,
                command.notificationsAuthorized(),
                command.liveActivitiesEnabled(),
                command.liveActivityAutoStartEnabled()
        );
        return new PushToStartTokenRegistrationResult(token.getId(), environment, RegistrationInputNormalizer.tokenPrefix(pushToStartToken), token.isActive());
    }

    public LiveActivityStartDeliveryResult deliverStart(Game game) {
        String readinessSkipReason = apnsPushService.readinessSkipReason();
        if (readinessSkipReason != null) {
            ApnsPushService.ApnsDiagnostics diagnostics = apnsPushService.diagnostics();
            log.warn(
                    "[LiveActivityStart] APNs start preflight skipped publicGameId={} providerGameId={} databaseId={} reason={} pushEnabled={} configuredEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    readinessSkipReason,
                    diagnostics.pushEnabled(),
                    diagnostics.configuredEnvironment()
            );
            return new LiveActivityStartDeliveryResult(0, 1, 0);
        }

        List<LiveActivityPushToStartToken> tokens = inTransaction(repository::findByActiveTrue);
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
                        "[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} reason={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        skipReason
                );
                continue;
            }

            Map<String, Object> attributes = buildAttributes(game, token.getFavoriteTeamId());
            Map<String, Object> contentState = contentStateBuilder.buildForFavoriteTeam(game, token.getFavoriteTeamId(), null);
            ApnsPushService.ApnsSendResult result = apnsPushService.sendLiveActivityStart(game, token, attributes, contentState);
            if (result.sent()) {
                sent++;
                markStartDelivered(token, gameKey);
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
                if (result.invalidToken()) {
                    disableToken(token);
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

    private void markStartDelivered(LiveActivityPushToStartToken token, String gameKey) {
        updateToken(token, managedToken -> managedToken.markStartDelivered(gameKey, OffsetDateTime.now(applicationClock)));
    }

    private void disableToken(LiveActivityPushToStartToken token) {
        updateToken(token, managedToken -> managedToken.disable(OffsetDateTime.now(applicationClock)));
    }

    private void updateToken(LiveActivityPushToStartToken token, java.util.function.Consumer<LiveActivityPushToStartToken> update) {
        if (transactionTemplate == null) {
            update.accept(token);
            return;
        }
        UUID tokenId = token.getId();
        inTransaction(() -> {
            repository.findById(tokenId).ifPresent(update);
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
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
