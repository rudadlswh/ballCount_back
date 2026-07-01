package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import com.kbo.crawlerapi.support.HashSupport;
import java.time.Clock;
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
                    "[LiveActivity] APNs token result publicGameId={} providerGameId={} databaseId={} activityId={} oldContentStateHash={} newContentStateHash={} changedFields={} matchedCount={} sent={} skipped={} failed={} reason={}",
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
                    result.reason()
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
