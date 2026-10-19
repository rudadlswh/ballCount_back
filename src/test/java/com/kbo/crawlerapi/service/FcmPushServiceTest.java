package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import java.time.Clock;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class FcmPushServiceTest {

    @Test
    void messageIsHighPriorityDataOnlyForReliableClientHistoryAndDeepLinking() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FcmProperties properties = new FcmProperties();
        FcmAccessTokenProvider tokenProvider = new FcmAccessTokenProvider(properties, mapper, Clock.systemUTC());
        FcmPushService service = new FcmPushService(properties, tokenProvider, mapper);
        Game game = game();
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID(), game, "SCORE_CHANGED", "score:1", "득점", "LG 2 : 1 SSG", "{\"inning\":\"5\"}"
        );

        Map<String, Object> body = service.messageBody(event, new NotificationDevice(
                UUID.randomUUID(), "android", "sandbox", "token", "install", "lg", true, OffsetDateTime.now()
        ));

        Map<?, ?> message = (Map<?, ?>) body.get("message");
        Map<?, ?> android = (Map<?, ?>) message.get("android");
        Map<?, ?> data = (Map<?, ?>) message.get("data");
        assertThat(android.get("priority")).isEqualTo("HIGH");
        assertThat(android.containsKey("notification")).isFalse();
        assertThat(data.get("publicGameId")).isEqualTo("20260720-LG-SSG");
        assertThat(data.get("eventType")).isEqualTo("SCORE_CHANGED");
        assertThat(data.get("title")).isEqualTo("득점");
    }

    @Test
    void readinessRequiresExplicitEnableAndCredentials() {
        FcmProperties properties = new FcmProperties();
        FcmPushService service = new FcmPushService(
                properties,
                new FcmAccessTokenProvider(properties, new ObjectMapper(), Clock.systemUTC()),
                new ObjectMapper()
        );
        assertThat(service.readinessSkipReason()).isEqualTo(FcmPushService.PUSH_DISABLED);
    }

    @Test
    void onlyStructuredUnregisteredErrorInvalidatesDeviceToken() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(404, """
                {"error":{"status":"NOT_FOUND","details":[
                  {"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}
                ]}}
                """)).when(client).send(any(), any(HttpResponse.BodyHandler.class));

        FcmPushService.FcmSendResult result = service(client).send(event(), device());

        assertThat(result.sent()).isFalse();
        assertThat(result.invalidToken()).isTrue();
        assertThat(result.reason()).isEqualTo("fcm_http_404");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Project not found\"}}",
            "{\"error\":{\"message\":\"UNREGISTERED\"}}",
            "{\"error\":{\"details\":[{\"@type\":\"other\",\"errorCode\":\"UNREGISTERED\"}]}}",
            "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}",
            "<html>Not Found</html>", "null", "", "{"
    })
    void genericNotFoundAndMalformedResponsesKeepDeviceRegistered(String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(404, body)).when(client).send(any(), any(HttpResponse.BodyHandler.class));

        FcmPushService.FcmSendResult result = service(client).send(event(), device());

        assertThat(result.invalidToken()).isFalse();
        assertThat(result.reason()).isEqualTo("fcm_http_404");
    }

    @Test
    void sendsWithConfiguredRequestTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200, "{\"name\":\"projects/test/messages/one\"}"))
                .when(client).send(any(), any(HttpResponse.BodyHandler.class));

        FcmPushService.FcmSendResult result = service(client).send(event(), device());

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().timeout()).contains(Duration.ofMillis(250));
        assertThat(result.sent()).isTrue();
        assertThat(result.messageId()).isEqualTo("projects/test/messages/one");
    }

    @Test
    void timeoutDoesNotInvalidateDeviceOrRetryImmediately() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("timed out"));

        FcmPushService.FcmSendResult result = service(client).send(event(), device());

        assertThat(result.reason()).isEqualTo(FcmPushService.SEND_FAILED);
        assertThat(result.invalidToken()).isFalse();
        verify(client).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void unauthorizedResponseRefreshesTokenAtMostOnce() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(401, "{}")).when(client).send(any(), any(HttpResponse.BodyHandler.class));
        FcmAccessTokenProvider tokens = tokens();
        FcmPushService service = new FcmPushService(enabledProperties(), tokens, new ObjectMapper(), client);

        FcmPushService.FcmSendResult result = service.send(event(), device());

        assertThat(result.reason()).isEqualTo("fcm_http_401");
        assertThat(result.invalidToken()).isFalse();
        verify(tokens).invalidate();
        verify(tokens, times(2)).accessToken();
        verify(client, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    private FcmPushService service(HttpClient client) {
        return new FcmPushService(enabledProperties(), tokens(), new ObjectMapper(), client, Duration.ofMillis(250));
    }

    private FcmProperties enabledProperties() {
        FcmProperties properties = new FcmProperties();
        properties.setPushEnabled(true);
        return properties;
    }

    private FcmAccessTokenProvider tokens() {
        FcmAccessTokenProvider tokens = mock(FcmAccessTokenProvider.class);
        when(tokens.credentialsPresent()).thenReturn(true);
        when(tokens.resolvedProjectId()).thenReturn("test-project");
        when(tokens.accessToken()).thenReturn("test-access-token");
        return tokens;
    }

    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private NotificationEvent event() {
        return new NotificationEvent(UUID.randomUUID(), game(), "SCORE_CHANGED", "score:1", "득점", "LG 2 : 1 SSG", "{}");
    }

    private NotificationDevice device() {
        return new NotificationDevice(UUID.randomUUID(), "android", "sandbox", "test-device-token", "install", "lg", true, OffsetDateTime.now());
    }

    private Game game() {
        Team home = new Team(UUID.randomUUID(), "ssg", "SSG 랜더스", "SSG", "SSG Landers", null);
        Team away = new Team(UUID.randomUUID(), "lg", "LG 트윈스", "LG", "LG Twins", null);
        return new Game(
                UUID.randomUUID(), "20260720-LG-SSG", "kbo", "20260720SKLG0", LocalDate.of(2026, 7, 20),
                OffsetDateTime.parse("2026-07-20T18:30:00+09:00"), "문학", GameStatus.LIVE,
                home, away, 1, 2, "5회 초", false, false, null, null, null
        );
    }
}
