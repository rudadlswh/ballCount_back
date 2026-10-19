package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import com.kbo.crawlerapi.support.HashSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LiveActivityUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityUpdateService.class);
    private static final Duration VOLATILE_UPDATE_DEBOUNCE = Duration.ofSeconds(3);

    private final LiveActivityTokenRepository liveActivityTokenRepository;
    private final LiveActivityContentStateBuilder contentStateBuilder;
    private final ApnsPushService apnsPushService;
    private final Clock applicationClock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionTemplate transactionTemplate;

    public LiveActivityUpdateService(
            LiveActivityTokenRepository liveActivityTokenRepository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock
    ) {
        this(liveActivityTokenRepository, contentStateBuilder, apnsPushService, applicationClock, null);
    }

    @Autowired
    public LiveActivityUpdateService(
            LiveActivityTokenRepository liveActivityTokenRepository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock,
            PlatformTransactionManager transactionManager
    ) {
        this.liveActivityTokenRepository = liveActivityTokenRepository;
        this.contentStateBuilder = contentStateBuilder;
        this.apnsPushService = apnsPushService;
        this.applicationClock = applicationClock;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public LiveActivityDeliveryResult deliverUpdate(Game game) {
        String readinessSkipReason = apnsPushService.readinessSkipReason();
        if (readinessSkipReason != null) {
            ApnsPushService.ApnsDiagnostics diagnostics = apnsPushService.diagnostics();
            log.warn(
                    "[LiveActivity] APNs update preflight skipped publicGameId={} providerGameId={} databaseId={} reason={} pushEnabled={} configuredEnv={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    readinessSkipReason,
                    diagnostics.pushEnabled(),
                    diagnostics.configuredEnvironment()
            );
            return new LiveActivityDeliveryResult(0, 1, 0);
        }

        List<LiveActivityToken> matches = inTransaction(() -> liveActivityTokenRepository.findActiveMatchesForGame(
                textOrNull(game.getPublicGameId()),
                textOrNull(game.getProviderGameId()),
                game.getId().toString(),
                stableProviderIdentity(game),
                stablePublicIdentity(game)
        ));
        log.info(
                "[LiveActivity] token matched publicGameId={} providerGameId={} databaseId={} matchedCount={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getId(),
                matches.size()
        );
        if (matches.isEmpty()) {
            log.info(
                    "[LiveActivity] APNs skipped publicGameId={} providerGameId={} databaseId={} matchedCount=0 reason=no_matching_live_activity_tokens",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId()
            );
            return new LiveActivityDeliveryResult(0, 1, 0);
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (LiveActivityToken token : matches) {
            Map<String, Object> contentState = contentStateBuilder.build(game, token);
            String contentStateJson = toStableJson(contentState);
            String newContentStateHash = sha256(contentStateJson);
            String oldContentStateHash = token.getContentStateHash();
            Set<String> changedFields = changedFields(token.getContentStateJson(), contentState);
            if (Objects.equals(oldContentStateHash, newContentStateHash)) {
                skipped++;
                log.info(
                        "[LiveActivity] APNs skipped publicGameId={} providerGameId={} databaseId={} activityId={} oldContentStateHash={} newContentStateHash={} changedFields={} matchedCount={} sent=0 skipped=1 failed=0 reason=unchanged_content_state",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        token.getActivityId(),
                        oldContentStateHash,
                        newContentStateHash,
                        changedFields,
                        matches.size()
                );
                continue;
            }
            if (shouldDebounce(token, changedFields)) {
                skipped++;
                log.info(
                        "[LiveActivity] APNs skipped publicGameId={} providerGameId={} databaseId={} activityId={} changedFields={} reason=debounced_volatile_content_state debounceSeconds={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getId(),
                        token.getActivityId(),
                        changedFields,
                        VOLATILE_UPDATE_DEBOUNCE.toSeconds()
                );
                continue;
            }

            log.info(
                    "[LiveActivity] APNs update candidate publicGameId={} providerGameId={} databaseId={} activityId={} oldContentStateHash={} newContentStateHash={} changedFields={} matchedCount={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    token.getActivityId(),
                    oldContentStateHash,
                    newContentStateHash,
                    changedFields,
                    matches.size()
            );
            ApnsPushService.ApnsSendResult result = apnsPushService.sendLiveActivityUpdate(game, token, contentState);
            if (result.sent()) {
                sent++;
                markContentStateDelivered(token, newContentStateHash, contentStateJson);
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
                if (result.invalidToken()) {
                    disableToken(token);
                }
            }
            log.info(
                    "[LiveActivity] APNs token result publicGameId={} providerGameId={} databaseId={} activityId={} oldContentStateHash={} newContentStateHash={} changedFields={} matchedCount={} sent={} skipped={} failed={} reason={} retryable={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    token.getActivityId(),
                    oldContentStateHash,
                    newContentStateHash,
                    changedFields,
                    matches.size(),
                    result.sent() ? 1 : 0,
                    result.skipped() ? 1 : 0,
                    !result.sent() && !result.skipped() ? 1 : 0,
                    result.reason(),
                    result.retryableFailure()
            );
        }
        log.info(
                "[LiveActivity] APNs result publicGameId={} providerGameId={} databaseId={} matchedCount={} sent={} skipped={} failed={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getId(),
                matches.size(),
                sent,
                skipped,
                failed
        );
        return new LiveActivityDeliveryResult(sent, skipped, failed);
    }

    public LiveActivityDeliveryResult deliverEnd(Game game, String eventType) {
        List<LiveActivityToken> matches = inTransaction(() -> liveActivityTokenRepository.findActiveMatchesForGame(
                textOrNull(game.getPublicGameId()),
                textOrNull(game.getProviderGameId()),
                game.getId().toString(),
                stableProviderIdentity(game),
                stablePublicIdentity(game)
        ));

        int attempted = 0;
        int ended = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> failureReasons = new LinkedHashSet<>();
        for (LiveActivityToken token : matches) {
            attempted++;
            Map<String, Object> contentState = endContentState(game, token);
            ApnsPushService.ApnsSendResult result = apnsPushService.sendLiveActivityEnd(game, token, contentState);
            if (result.sent()) {
                ended++;
                disableToken(token);
            } else if (result.skipped()) {
                skipped++;
                failureReasons.add(result.reason());
            } else {
                failed++;
                failureReasons.add(result.reason());
                if (result.invalidToken()) {
                    disableToken(token);
                }
            }
        }

        log.info(
                "[LiveActivityEnd] APNs result publicGameId={} eventType={} matchedLiveActivityCount={} attemptedEndPushCount={} endedCount={} failedCount={} failureReasons={}",
                game.getPublicGameId(),
                eventType,
                matches.size(),
                attempted,
                ended,
                failed,
                failureReasons
        );
        return new LiveActivityDeliveryResult(ended, skipped, failed);
    }

    private Map<String, Object> endContentState(Game game, LiveActivityToken token) {
        Map<String, Object> contentState = new java.util.LinkedHashMap<>(contentStateBuilder.build(game, token));
        String gameStatus = endGameStatus(game);
        String endText = endText(game);
        contentState.put("isPreGame", false);
        contentState.put("summaryText", endText);
        contentState.put("inningText", endText);
        contentState.put("gameStatus", gameStatus);
        contentState.put("endText", endText);
        return contentState;
    }

    private String endGameStatus(Game game) {
        return switch (game.getStatus()) {
            case CANCELLED, POSTPONED -> "cancelled";
            case FINAL -> "final";
            default -> game.getStatus().getApiValue();
        };
    }

    private String endText(Game game) {
        if (game.getStatus() == GameStatus.POSTPONED) {
            return "순연";
        }
        if (game.getStatus() == GameStatus.CANCELLED) {
            if (game.getRawCancelText() != null && !game.getRawCancelText().isBlank()) {
                return game.getRawCancelText().trim();
            }
            return game.getCancelReason() == GameCancelReason.RAIN ? "우천취소" : "취소";
        }
        return "종료";
    }

    private void markContentStateDelivered(LiveActivityToken token, String contentStateHash, String contentStateJson) {
        updateToken(token, managedToken -> managedToken.markContentStateDelivered(
                contentStateHash,
                contentStateJson,
                OffsetDateTime.now(applicationClock)
        ));
    }

    private void disableToken(LiveActivityToken token) {
        updateToken(token, managedToken -> managedToken.disable(OffsetDateTime.now(applicationClock)));
    }

    private void updateToken(LiveActivityToken token, java.util.function.Consumer<LiveActivityToken> update) {
        if (transactionTemplate == null) {
            update.accept(token);
            return;
        }
        UUID tokenId = token.getId();
        inTransaction(() -> {
            liveActivityTokenRepository.findById(tokenId).ifPresent(update);
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    private String toStableJson(Map<String, Object> contentState) {
        try {
            return objectMapper.writeValueAsString(contentState);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Live Activity content state", exception);
        }
    }

    private String sha256(String value) {
        return HashSupport.sha256Hex(value);
    }

    private Set<String> changedFields(String previousContentStateJson, Map<String, Object> newContentState) {
        Map<String, Object> previousContentState = readContentState(previousContentStateJson);
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(previousContentState.keySet());
        fields.addAll(newContentState.keySet());
        Set<String> changed = new LinkedHashSet<>();
        for (String field : fields) {
            if (!Objects.equals(previousContentState.get(field), newContentState.get(field))) {
                changed.add(field);
            }
        }
        return changed;
    }

    private boolean shouldDebounce(LiveActivityToken token, Set<String> changedFields) {
        if (changedFields.isEmpty() || containsImmediateField(changedFields)) {
            return false;
        }
        String previousPayload = token.getContentStateJson();
        if (previousPayload == null || !previousPayload.contains("favoriteScoreText")) {
            return false;
        }
        if (!changedFields.stream().allMatch(this::isVolatileField)) {
            return false;
        }
        OffsetDateTime lastSeenAt = token.getLastSeenAt();
        if (lastSeenAt == null) {
            return false;
        }
        Instant nextAllowedAt = lastSeenAt.toInstant().plus(VOLATILE_UPDATE_DEBOUNCE);
        return Instant.now(applicationClock).isBefore(nextAllowedAt);
    }

    private boolean containsImmediateField(Set<String> changedFields) {
        return changedFields.stream().anyMatch(field -> switch (field) {
            case "favoriteScoreText", "opponentScoreText", "summaryText", "isPreGame", "inningText" -> true;
            default -> false;
        });
    }

    private boolean isVolatileField(String field) {
        return switch (field) {
            case "inningText", "balls", "strikes", "outs", "runnerOnFirst", "runnerOnSecond", "runnerOnThird", "currentBatterName", "currentPitcherName" -> true;
            default -> false;
        };
    }

    private Map<String, Object> readContentState(String contentStateJson) {
        if (contentStateJson == null || contentStateJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(contentStateJson, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String stableProviderIdentity(Game game) {
        String providerGameId = textOrNull(game.getProviderGameId());
        return providerGameId == null ? null : "provider:" + providerGameId;
    }

    private String stablePublicIdentity(Game game) {
        String publicGameId = textOrNull(game.getPublicGameId());
        return publicGameId == null ? null : "public:" + publicGameId.toLowerCase(java.util.Locale.ROOT);
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record LiveActivityDeliveryResult(int sentCount, int skippedCount, int failedCount) {
    }
}
