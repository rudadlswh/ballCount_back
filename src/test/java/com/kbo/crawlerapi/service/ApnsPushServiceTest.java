package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.LiveActivityPushToStartToken;
import com.kbo.crawlerapi.domain.LiveActivityToken;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ApnsPushServiceTest {

    @Test
    void buildsVisibleAlertPushRequest() throws Exception {
        ApnsProperties properties = new ApnsProperties();
        properties.setBundleId("com.chogm.kboScore");
        ApnsPushService service = new ApnsPushService(properties, Clock.systemUTC());
        NotificationEvent event = event();
        NotificationDevice device = device();

        HttpRequest request = service.buildRequest(event, device, "jwt-token");

        assertThat(request.uri().toString()).isEqualTo("https://api.sandbox.push.apple.com/3/device/token-123");
        assertThat(request.headers().firstValue("apns-topic")).contains("com.chogm.kboScore");
        assertThat(request.headers().firstValue("apns-push-type")).contains("alert");
        assertThat(request.headers().firstValue("apns-priority")).contains("10");
        assertThat(request.headers().firstValue("authorization")).contains("bearer jwt-token");
        assertThat(body(request)).contains(
                "\"aps\":{\"alert\":{\"title\":\"KBO Score test\",\"body\":\"APNs manual test\"},\"sound\":\"default\"}",
                "\"data\":{\"routeHint\":\"notifications\"}"
        );
    }

    @Test
    void buildsLiveActivityUpdateRequestWithActivityHeadersAndContentState() throws Exception {
        ApnsProperties properties = new ApnsProperties();
        properties.setBundleId("com.chogm.kboScore");
        ApnsPushService service = new ApnsPushService(properties, Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneId.of("UTC")));
        LiveActivityToken token = liveActivityToken();

        HttpRequest request = service.buildLiveActivityUpdateRequest(
                token,
                Map.of(
                        "isPreGame", false,
                        "favoriteScoreText", "1",
                        "opponentScoreText", "1",
                        "inningText", "1회 초"
                ),
                "jwt-token"
        );

        assertThat(request.uri().toString()).isEqualTo("https://api.sandbox.push.apple.com/3/device/live-token-123");
        assertThat(request.headers().firstValue("apns-topic")).contains("com.chogm.kboScore.push-type.liveactivity");
        assertThat(request.headers().firstValue("apns-push-type")).contains("liveactivity");
        assertThat(request.headers().firstValue("authorization")).contains("bearer jwt-token");
        assertThat(body(request)).contains(
                "\"aps\":{\"timestamp\":1780653600,\"event\":\"update\",\"content-state\":",
                "\"favoriteScoreText\":\"1\"",
                "\"opponentScoreText\":\"1\"",
                "\"stale-date\":1780653720"
        );
    }

    @Test
    void buildsLiveActivityStartRequestWithAttributesAndContentState() throws Exception {
        ApnsProperties properties = new ApnsProperties();
        properties.setBundleId("com.chogm.kboScore");
        ApnsPushService service = new ApnsPushService(properties, Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneId.of("UTC")));
        LiveActivityPushToStartToken token = pushToStartToken();

        HttpRequest request = service.buildLiveActivityStartRequest(
                token,
                Map.of(
                        "gameID", "game-1",
                        "favoriteTeamID", "lg",
                        "opponentTeamID", "doosan",
                        "venue", "잠실",
                        "isHomeGame", true
                ),
                Map.of(
                        "isPreGame", false,
                        "favoriteScoreText", "3",
                        "opponentScoreText", "2",
                        "inningText", "5회 초"
                ),
                "jwt-token"
        );

        assertThat(request.uri().toString()).isEqualTo("https://api.sandbox.push.apple.com/3/device/start-token-123");
        assertThat(request.headers().firstValue("apns-topic")).contains("com.chogm.kboScore.push-type.liveactivity");
        assertThat(request.headers().firstValue("apns-push-type")).contains("liveactivity");
        assertThat(body(request)).contains(
                "\"event\":\"start\"",
                "\"attributes-type\":\"FavoriteTeamGameActivityAttributes\"",
                "\"favoriteTeamID\":\"lg\"",
                "\"content-state\":",
                "\"favoriteScoreText\":\"3\"",
                "\"input-push-token\":1"
        );
    }

    @Test
    void firstSendCreatesProviderToken() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
        RecordingHttpClient httpClient = new RecordingHttpClient(200, "");
        ApnsPushService service = new ApnsPushService(configuredProperties(), clock, httpClient);

        ApnsPushService.ApnsSendResult result = service.send(event(), device());

        assertThat(result.sent()).isTrue();
        assertThat(httpClient.requests).hasSize(1);
        assertThat(authorization(httpClient.requests.get(0))).startsWith("bearer ");
    }

    @Test
    void secondSendWithinCacheWindowReusesProviderToken() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
        RecordingHttpClient httpClient = new RecordingHttpClient(200, "");
        ApnsPushService service = new ApnsPushService(configuredProperties(), clock, httpClient);

        service.send(event(), device());
        String firstToken = authorization(httpClient.requests.get(0));
        clock.advance(Duration.ofMinutes(10));
        service.send(event(), device());

        assertThat(authorization(httpClient.requests.get(1))).isEqualTo(firstToken);
    }

    @Test
    void tokenRefreshesAfterRefreshWindow() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
        RecordingHttpClient httpClient = new RecordingHttpClient(200, "");
        ApnsPushService service = new ApnsPushService(configuredProperties(), clock, httpClient);

        service.send(event(), device());
        String firstToken = authorization(httpClient.requests.get(0));
        clock.advance(Duration.ofMinutes(51));
        service.send(event(), device());

        assertThat(authorization(httpClient.requests.get(1))).isNotEqualTo(firstToken);
    }

    @Test
    void tokenOlderThanSixtyMinutesIsNotReused() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
        RecordingHttpClient httpClient = new RecordingHttpClient(200, "");
        ApnsPushService service = new ApnsPushService(configuredProperties(), clock, httpClient);

        service.send(event(), device());
        String firstToken = authorization(httpClient.requests.get(0));
        clock.advance(Duration.ofMinutes(61));
        service.send(event(), device());

        assertThat(authorization(httpClient.requests.get(1))).isNotEqualTo(firstToken);
    }

    @Test
    void tooManyProviderTokenUpdatesMapsToExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), Clock.systemUTC());

        String reason = service.mapApnsFailureReason("{\"reason\":\"TooManyProviderTokenUpdates\"}");

        assertThat(reason).isEqualTo(ApnsPushService.APNS_TOO_MANY_PROVIDER_TOKEN_UPDATES);
    }

    @Test
    void invalidProviderTokenResponseMapsToExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), Clock.systemUTC());

        String reason = service.mapApnsFailureReason("{\"reason\":\"InvalidProviderToken\"}");

        assertThat(reason).isEqualTo(ApnsPushService.APNS_INVALID_PROVIDER_TOKEN);
    }

    @Test
    void badDeviceTokenResponseMapsToExplicitReason() {
        ApnsPushService service = new ApnsPushService(new ApnsProperties(), Clock.systemUTC());

        String reason = service.mapApnsFailureReason("{\"reason\":\"BadDeviceToken\"}");

        assertThat(reason).isEqualTo(ApnsPushService.APNS_BAD_DEVICE_TOKEN);
    }

    private ApnsProperties configuredProperties() throws Exception {
        ApnsProperties properties = new ApnsProperties();
        properties.setPushEnabled(true);
        properties.setTeamId("TEAMID1234");
        properties.setKeyId("KEYID1234");
        properties.setBundleId("com.chogm.kboScore");
        properties.setPrivateKey(validPem());
        properties.setEnv("sandbox");
        return properties;
    }

    private String validPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom(new byte[] {1, 2, 3, 4}));
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private NotificationEvent event() {
        return new NotificationEvent(
                UUID.randomUUID(),
                null,
                "TEST",
                "test-event",
                "KBO Score test",
                "APNs manual test",
                "{\"routeHint\":\"notifications\"}"
        );
    }

    private NotificationDevice device() {
        return new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                "lg",
                true,
                OffsetDateTime.parse("2026-05-01T09:00:00+09:00")
        );
    }

    private LiveActivityToken liveActivityToken() {
        return new LiveActivityToken(
                UUID.randomUUID(),
                "activity-1",
                "ios",
                "sandbox",
                "live-token-123",
                "install-1",
                "lg",
                "20260605-LOT-HAN",
                "20260605HHLT0",
                UUID.randomUUID().toString(),
                "provider:20260605HHLT0",
                OffsetDateTime.parse("2026-06-05T19:00:00+09:00")
        );
    }

    private LiveActivityPushToStartToken pushToStartToken() {
        LiveActivityPushToStartToken token = new LiveActivityPushToStartToken(
                UUID.randomUUID(),
                OffsetDateTime.parse("2026-06-05T19:00:00+09:00")
        );
        token.update(
                "ios",
                "sandbox",
                "start-token-123",
                "install-1",
                "lg",
                true,
                true,
                true,
                true,
                false,
                OffsetDateTime.parse("2026-06-05T19:00:00+09:00")
        );
        return token;
    }

    private String authorization(HttpRequest request) {
        return request.headers().firstValue("authorization").orElseThrow();
    }

    private String body(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        BodySubscriber subscriber = new BodySubscriber();
        publisher.subscribe(subscriber);
        subscriber.await();
        return subscriber.body();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final int statusCode;
        private final String body;
        private final List<HttpRequest> requests = new ArrayList<>();

        private RecordingHttpClient(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            @SuppressWarnings("unchecked")
            T typedBody = (T) body;
            return new StubHttpResponse<>(request, statusCode, typedBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }
    }

    private record StubHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            T body
    ) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final StringBuilder body = new StringBuilder();
        private final CountDownLatch completed = new CountDownLatch(1);
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            body.append(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void onError(Throwable throwable) {
            completed.countDown();
        }

        @Override
        public void onComplete() {
            subscription.cancel();
            completed.countDown();
        }

        private String body() {
            return body.toString();
        }

        private void await() throws InterruptedException {
            completed.await(1, TimeUnit.SECONDS);
        }
    }
}
