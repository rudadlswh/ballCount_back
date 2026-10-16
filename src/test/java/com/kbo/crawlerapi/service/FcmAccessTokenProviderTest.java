package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FcmAccessTokenProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T06:00:00Z"), ZoneOffset.UTC);
    private static String credentials;

    @BeforeAll
    static void createTestCredentials() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        credentials = MAPPER.writeValueAsString(Map.of(
                "project_id", "test-project",
                "client_email", "test@example.invalid",
                "private_key", key,
                "token_uri", "https://oauth.example.invalid/token"
        ));
    }

    @Test
    void tokenRequestHasConfiguredTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response()).when(client).send(any(), any(HttpResponse.BodyHandler.class));

        assertThat(provider(client).accessToken()).isEqualTo("test-access-token");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().timeout()).contains(Duration.ofMillis(250));
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().uri().toString()).isEqualTo("https://oauth.example.invalid/token");
    }

    @Test
    void successfulTokenIsReusedUntilInvalidated() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response()).when(client).send(any(), any(HttpResponse.BodyHandler.class));
        FcmAccessTokenProvider provider = provider(client);

        assertThat(provider.accessToken()).isEqualTo("test-access-token");
        assertThat(provider.accessToken()).isEqualTo("test-access-token");
        verify(client).send(any(), any(HttpResponse.BodyHandler.class));

        provider.invalidate();
        assertThat(provider.accessToken()).isEqualTo("test-access-token");
        verify(client, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void timeoutDoesNotCacheFailureAndNextRequestCanRecover() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timed out"))
                .thenReturn(response());
        FcmAccessTokenProvider provider = provider(client);

        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(HttpTimeoutException.class);
        assertThat(provider.accessToken()).isEqualTo("test-access-token");
        verify(client, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void interruptionIsPreservedForShutdown() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("shutdown"));
        try {
            assertThatThrownBy(() -> provider(client).accessToken()).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private FcmAccessTokenProvider provider(HttpClient client) {
        FcmProperties properties = new FcmProperties();
        properties.setCredentialsJson(credentials);
        return new FcmAccessTokenProvider(properties, MAPPER, client, CLOCK, Duration.ofMillis(250));
    }

    private HttpResponse<String> response() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"test-access-token\",\"expires_in\":3600}");
        return response;
    }
}
