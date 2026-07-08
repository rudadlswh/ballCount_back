package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class LiveGameStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveGameStreamRegistry.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 6L * 60L * 60L * 1_000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> emittersByGameId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String publicGameId) {
        String normalizedPublicGameId = normalizePublicGameId(publicGameId);
        log.info(
                "[SseStream] subscribe start publicGameId={} normalizedPublicGameId={}",
                publicGameId,
                normalizedPublicGameId
        );
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emittersByGameId.computeIfAbsent(normalizedPublicGameId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        log.info(
                "[SseStream] subscribe registered publicGameId={} count={}",
                normalizedPublicGameId,
                subscriberCount(normalizedPublicGameId)
        );
        emitter.onCompletion(() -> {
            log.info("[SseStream] onCompletion publicGameId={}", normalizedPublicGameId);
            remove(normalizedPublicGameId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("[SseStream] onTimeout publicGameId={}", normalizedPublicGameId);
            remove(normalizedPublicGameId, emitter);
        });
        emitter.onError(error -> {
            log.warn(
                    "[SseStream] onError publicGameId={} reason={}",
                    normalizedPublicGameId,
                    error == null ? null : error.getMessage()
            );
            remove(normalizedPublicGameId, emitter);
        });
        send(normalizedPublicGameId, emitter, "heartbeat", "");
        return emitter;
    }

    public void sendLatestSnapshotOnSubscribe(String publicGameId, SseEmitter emitter, GameLiveStateResponse snapshot) {
        String normalizedPublicGameId = normalizePublicGameId(publicGameId);
        if (send(normalizedPublicGameId, emitter, "snapshot", snapshot)) {
            log.info("[SseStream] latest snapshot sent on subscribe publicGameId={}", normalizedPublicGameId);
        }
    }

    public void publishSnapshot(String publicGameId, GameLiveStateResponse snapshot) {
        publish(publicGameId, "snapshot", snapshot);
    }

    public void publishStatusChanged(String publicGameId, GameLiveStateResponse snapshot) {
        publish(publicGameId, "status_changed", snapshot);
    }

    public void complete(String publicGameId) {
        String normalizedPublicGameId = normalizePublicGameId(publicGameId);
        Set<SseEmitter> emitters = emittersByGameId.remove(normalizedPublicGameId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(SseEmitter::complete);
    }

    public int subscriberCount(String publicGameId) {
        Set<SseEmitter> emitters = emittersByGameId.get(normalizePublicGameId(publicGameId));
        return emitters == null ? 0 : emitters.size();
    }

    @Scheduled(fixedDelay = 10_000)
    void heartbeat() {
        emittersByGameId.forEach((publicGameId, emitters) ->
                emitters.forEach(emitter -> send(publicGameId, emitter, "heartbeat", "")));
    }

    private void publish(String publicGameId, String eventName, GameLiveStateResponse snapshot) {
        String normalizedPublicGameId = normalizePublicGameId(publicGameId);
        Set<SseEmitter> emitters = emittersByGameId.get(normalizedPublicGameId);
        log.info(
                "[SseStream] registry subscriber count publicGameId={} count={} event={}",
                normalizedPublicGameId,
                emitters == null ? 0 : emitters.size(),
                eventName
        );
        if (emitters == null || emitters.isEmpty()) {
            log.warn(
                    "[SseStream] publish skipped publicGameId={} normalizedPublicGameId={} subscribers=0 registryKeys={} event={}",
                    publicGameId,
                    normalizedPublicGameId,
                    emittersByGameId.keySet(),
                    eventName
            );
            return;
        }
        emitters.forEach(emitter -> send(normalizedPublicGameId, emitter, eventName, snapshot));
    }

    private boolean send(String publicGameId, SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
            if ("heartbeat".equals(eventName)) {
                log.info("[SseStream] stream heartbeat sent: publicGameId={}", publicGameId);
            } else if ("snapshot".equals(eventName)) {
                log.info("[SseStream] snapshot sent success publicGameId={}", publicGameId);
            }
            return true;
        } catch (IOException | IllegalStateException exception) {
            if ("snapshot".equals(eventName)) {
                log.warn("[SseStream] snapshot sent failure publicGameId={} reason={}", publicGameId, exception.getMessage());
            } else {
                log.warn(
                        "[SseStream] stream send failure publicGameId={} event={} reason={}",
                        publicGameId,
                        eventName,
                        exception.getMessage()
                );
            }
            remove(publicGameId, emitter);
            try {
                emitter.completeWithError(exception);
            } catch (IllegalStateException ignored) {
                log.debug("[SseStream] emitter already completed publicGameId={} event={}", publicGameId, eventName);
            }
            return false;
        }
    }

    private void remove(String publicGameId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByGameId.get(publicGameId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByGameId.remove(publicGameId, emitters);
        }
    }

    public static String normalizePublicGameId(String publicGameId) {
        return publicGameId == null ? null : publicGameId.trim().toUpperCase(Locale.ROOT);
    }
}
