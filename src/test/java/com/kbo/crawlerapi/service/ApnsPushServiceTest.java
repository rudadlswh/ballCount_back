package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ApnsPushServiceTest {

    @Test
    void buildsVisibleAlertPushRequest() throws Exception {
        ApnsProperties properties = new ApnsProperties();
        properties.setBundleId("com.chogm.kboScore");
        ApnsPushService service = new ApnsPushService(properties, Clock.systemUTC());
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID(),
                null,
                "TEST",
                "test-event",
                "KBO 테스트",
                "APNs 표시 확인",
                "{\"routeHint\":\"notifications\"}"
        );
        NotificationDevice device = new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                "token-123",
                "install-1",
                "lg",
                true,
                OffsetDateTime.parse("2026-05-01T09:00:00+09:00")
        );

        HttpRequest request = service.buildRequest(event, device, "jwt-token");

        assertThat(request.uri().toString()).isEqualTo("https://api.sandbox.push.apple.com/3/device/token-123");
        assertThat(request.headers().firstValue("apns-topic")).contains("com.chogm.kboScore");
        assertThat(request.headers().firstValue("apns-push-type")).contains("alert");
        assertThat(request.headers().firstValue("apns-priority")).contains("10");
        assertThat(request.headers().firstValue("authorization")).contains("bearer jwt-token");
        assertThat(body(request)).contains(
                "\"aps\":{\"alert\":{\"title\":\"KBO 테스트\",\"body\":\"APNs 표시 확인\"},\"sound\":\"default\"}",
                "\"data\":{\"routeHint\":\"notifications\"}"
        );
    }

    private String body(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        BodySubscriber subscriber = new BodySubscriber();
        publisher.subscribe(subscriber);
        subscriber.await();
        return subscriber.body();
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
            body.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
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
