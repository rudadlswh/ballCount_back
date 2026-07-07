package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
import java.io.IOException;
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
    private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1_000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> emittersByGameId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String publicGameId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emittersByGameId.computeIfAbsent(publicGameId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        log.info("[SseStream] registry subscriber count publicGameId={} count={}", publicGameId, subscriberCount(publicGameId));
        emitter.onCompletion(() -> remove(publicGameId, emitter));
        emitter.onTimeout(() -> remove(publicGameId, emitter));
        emitter.onError(ignored -> remove(publicGameId, emitter));
        send(publicGameId, emitter, "heartbeat", "");
        return emitter;
    }

    public void publishSnapshot(String publicGameId, GameLiveStateResponse snapshot) {
        publish(publicGameId, "snapshot", snapshot);
    }

    public void publishStatusChanged(String publicGameId, GameLiveStateResponse snapshot) {
        publish(publicGameId, "status_changed", snapshot);
    }

    public void complete(String publicGameId) {
        Set<SseEmitter> emitters = emittersByGameId.remove(publicGameId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(SseEmitter::complete);
    }

    public int subscriberCount(String publicGameId) {
        Set<SseEmitter> emitters = emittersByGameId.get(publicGameId);
        return emitters == null ? 0 : emitters.size();
    }

    @Scheduled(fixedDelay = 10_000)
    void heartbeat() {
        emittersByGameId.forEach((publicGameId, emitters) ->
                emitters.forEach(emitter -> send(publicGameId, emitter, "heartbeat", "")));
    }

    private void publish(String publicGameId, String eventName, GameLiveStateResponse snapshot) {
        Set<SseEmitter> emitters = emittersByGameId.get(publicGameId);
        log.info(
                "[SseStream] registry subscriber count publicGameId={} count={} event={}",
                publicGameId,
                emitters == null ? 0 : emitters.size(),
                eventName
        );
        if (emitters == null) {
            return;
        }
        emitters.forEach(emitter -> send(publicGameId, emitter, eventName, snapshot));
    }

    private void send(String publicGameId, SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
            if ("heartbeat".equals(eventName)) {
                log.info("[SseStream] stream heartbeat sent: publicGameId={}", publicGameId);
            } else if ("snapshot".equals(eventName)) {
                log.info("[SseStream] snapshot sent success publicGameId={}", publicGameId);
            }
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
}
