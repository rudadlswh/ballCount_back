package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.kbo.crawlerapi.config.AppSecurityProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LiveGameStreamRegistryTest {
    @Test
    void globalAndClientLimitsApplyAcrossGamesAndCompletionReleasesSlots() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLiveStreamMaxConnections(2);
        properties.setLiveStreamMaxConnectionsPerClient(1);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry(properties);
        subscribe(registry, "game-one", "one");
        assertLimited(() -> subscribe(registry, "game-two", "one"));
        subscribe(registry, "game-two", "two");
        assertLimited(() -> subscribe(registry, "game-three", "three"));
        registry.complete("game-one");
        registry.complete("game-one");
        subscribe(registry, "game-three", "one");
        assertThat(registry.subscriberCount("game-one")).isZero();
        assertThat(registry.subscriberCount("game-three")).isEqualTo(1);
        assertLimited(() -> subscribe(registry, "game-four", "four"));
    }

    @Test
    void concurrentSubscriptionsRespectAdmissionLimit() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLiveStreamMaxConnections(5);
        properties.setLiveStreamMaxConnectionsPerClient(5);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry(properties);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = executor.invokeAll(IntStream.range(0, 100).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                try { subscribe(registry, "game", "client"); return true; }
                catch (ResponseStatusException exception) { return false; }
            }).toList());
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
            assertThat(registry.subscriberCount("game")).isEqualTo(5);
            registry.complete("game");
            assertThat(registry.subscriberCount("game")).isZero();
            subscribe(registry, "game", "client");
        } finally { executor.shutdownNow(); }
    }

    enum Termination { COMPLETION, TIMEOUT, ERROR }

    @ParameterizedTest
    @EnumSource(Termination.class)
    void asyncTerminationReleasesCapacityExactlyOnce(Termination termination) throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLiveStreamMaxConnections(1);
        properties.setLiveStreamMaxConnectionsPerClient(1);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry(properties);
        var mvc = MockMvcBuilders.standaloneSetup(new StreamEndpoint(registry)).build();
        var result = mvc.perform(get("/stream")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getContentAsString()).contains("event:heartbeat");
        assertThat(registry.subscriberCount("game")).isEqualTo(1);
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        AsyncEvent event = new AsyncEvent(context, result.getRequest(), result.getResponse(), new java.io.IOException("disconnect"));
        for (var listener : context.getListeners()) {
            switch (termination) {
                case COMPLETION -> listener.onComplete(event);
                case TIMEOUT -> listener.onTimeout(event);
                case ERROR -> listener.onError(event);
            }
        }
        assertThat(registry.subscriberCount("game")).isZero();
        subscribe(registry, "game", "127.0.0.1");
        // A late completion from the previous request must not release the new slot.
        for (var listener : context.getListeners()) listener.onComplete(event);
        assertThat(registry.subscriberCount("game")).isEqualTo(1);
        assertLimited(() -> subscribe(registry, "other", "other-client"));
    }

    @Test
    void failedInitialWriteReleasesSlotAtRequestCompletionWithoutWaitingForHeartbeat() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLiveStreamMaxConnections(1);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry(properties);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/stream");
        request.setAsyncSupported(true);
        var response = new org.springframework.mock.web.MockHttpServletResponse() {
            @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                return new jakarta.servlet.ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(jakarta.servlet.WriteListener listener) { }
                    @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("first write failed"); }
                };
            }
        };
        var asyncRequest = new org.springframework.web.context.request.async.StandardServletAsyncWebRequest(request, response);
        org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncRequest);
        SseEmitter emitter = new StreamEndpoint(registry).stream(request);
        var handler = new org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler(
                java.util.List.of(new org.springframework.http.converter.StringHttpMessageConverter()));
        var returnType = new org.springframework.core.MethodParameter(
                StreamEndpoint.class.getDeclaredMethod("stream", HttpServletRequest.class), -1);
        assertThatThrownBy(() -> handler.handleReturnValue(emitter, returnType,
                new org.springframework.web.method.support.ModelAndViewContainer(),
                new org.springframework.web.context.request.ServletWebRequest(request, response)))
                .isInstanceOf(java.io.IOException.class);
        asyncRequest.onComplete(new AsyncEvent(request.getAsyncContext(), request, response));
        assertThat(registry.subscriberCount("game")).isZero();
        subscribe(registry, "game", "127.0.0.1");
        assertThat(registry.subscriberCount("game")).isEqualTo(1);
    }

    private SseEmitter subscribe(LiveGameStreamRegistry registry, String game, String client) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr(client);
        return registry.subscribe(game, request);
    }

    private void assertLimited(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @RestController
    static class StreamEndpoint {
        private final LiveGameStreamRegistry registry;
        StreamEndpoint(LiveGameStreamRegistry registry) { this.registry = registry; }
        @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(HttpServletRequest request) { return registry.subscribe("game", request); }
    }
}
