package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.repository.LiveActivityTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveActivityUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityUpdateService.class);

    private final LiveActivityTokenRepository liveActivityTokenRepository;
    private final LiveActivityContentStateBuilder contentStateBuilder;
    private final ApnsPushService apnsPushService;
    private final Clock applicationClock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LiveActivityUpdateService(
            LiveActivityTokenRepository liveActivityTokenRepository,
            LiveActivityContentStateBuilder contentStateBuilder,
            ApnsPushService apnsPushService,
            Clock applicationClock
    ) {
        this.liveActivityTokenRepository = liveActivityTokenRepository;
        this.contentStateBuilder = contentStateBuilder;
        this.apnsPushService = apnsPushService;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public LiveActivityDeliveryResult deliverUpdate(Game game) {
        List<LiveActivityToken> matches = liveActivityTokenRepository.findByActiveTrue()
                .stream()
                .filter(token -> contentStateBuilder.matches(game, token))
                .toList();
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
                token.markContentStateDelivered(newContentStateHash, contentStateJson, OffsetDateTime.now(applicationClock));
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
                if (result.invalidToken()) {
                    token.disable(OffsetDateTime.now(applicationClock));
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

    private String toStableJson(Map<String, Object> contentState) {
        try {
            return objectMapper.writeValueAsString(contentState);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Live Activity content state", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
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

    public record LiveActivityDeliveryResult(int sentCount, int skippedCount, int failedCount) {
    }
}
