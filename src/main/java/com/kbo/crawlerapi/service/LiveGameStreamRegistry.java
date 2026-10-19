package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class LiveGameStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveGameStreamRegistry.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 6L * 60L * 60L * 1_000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> emittersByGameId = new ConcurrentHashMap<>();

    private final AppSecurityProperties properties;
    // All membership changes and quota releases share one lock, so an empty game
    // cannot be removed while another subscriber is joining it.
    private final Map<SseEmitter, String> clientsByEmitter = new HashMap<>();
    private final Map<String, Integer> connectionsByClient = new HashMap<>();

    public LiveGameStreamRegistry() {
        this(new AppSecurityProperties());
    }

    @Autowired
    public LiveGameStreamRegistry(AppSecurityProperties properties) {
        this.properties = properties;
    }

    public SseEmitter subscribe(String publicGameId, HttpServletRequest request) {
        String clientAddress = request.getRemoteAddr();
        String normalizedPublicGameId = normalizePublicGameId(publicGameId);
        SseEmitter emitter;
        synchronized (this) {
            int clientConnections = connectionsByClient.getOrDefault(clientAddress, 0);
            if (clientsByEmitter.size() >= Math.max(1, properties.getLiveStreamMaxConnections())
                    || clientConnections >= Math.max(1, properties.getLiveStreamMaxConnectionsPerClient())) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "stream connection limit reached");
            }
            emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
            emitter.onCompletion(() -> {
                log.info("[SseStream] onCompletion publicGameId={}", normalizedPublicGameId);
                remove(normalizedPublicGameId, emitter);
            });
            emitter.onTimeout(() -> {
                log.warn("[SseStream] onTimeout publicGameId={}", normalizedPublicGameId);
                remove(normalizedPublicGameId, emitter);
            });
            emitter.onError(error -> {
                log.warn("[SseStream] onError publicGameId={} reason={}", normalizedPublicGameId,
                        error == null ? null : error.getMessage());
                remove(normalizedPublicGameId, emitter);
            });
            emittersByGameId.computeIfAbsent(normalizedPublicGameId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
            clientsByEmitter.put(emitter, clientAddress);
            connectionsByClient.put(clientAddress, clientConnections + 1);
        }
        // Spring can fail while flushing buffered events before it attaches emitter
        // callbacks. The request interceptor is installed before that first write.
        WebAsyncUtils.getAsyncManager(request).registerDeferredResultInterceptor(emitter,
                new DeferredResultProcessingInterceptor() {
                    @Override
                    public <T> void afterCompletion(NativeWebRequest webRequest, DeferredResult<T> result) {
                        remove(normalizedPublicGameId, emitter);
                    }

                    @Override
                    public <T> boolean handleError(NativeWebRequest webRequest, DeferredResult<T> result, Throwable error) {
                        remove(normalizedPublicGameId, emitter);
                        return true;
                    }

                    @Override
                    public <T> boolean handleTimeout(NativeWebRequest webRequest, DeferredResult<T> result) {
                        remove(normalizedPublicGameId, emitter);
                        return true;
                    }
                });
        log.info("[SseStream] subscribe registered publicGameId={} count={}",
                normalizedPublicGameId, subscriberCount(normalizedPublicGameId));
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
        List<SseEmitter> completed;
        synchronized (this) {
            Set<SseEmitter> emitters = emittersByGameId.get(normalizedPublicGameId);
            if (emitters == null) return;
            completed = List.copyOf(emitters);
            completed.forEach(emitter -> remove(normalizedPublicGameId, emitter));
        }
        completed.forEach(SseEmitter::complete);
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

    private synchronized void remove(String publicGameId, SseEmitter emitter) {
        if (!clientsByEmitter.containsKey(emitter)) return;
        String client = clientsByEmitter.remove(emitter);
        connectionsByClient.computeIfPresent(client, (ignored, count) -> count == 1 ? null : count - 1);
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
