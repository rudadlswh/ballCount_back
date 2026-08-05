package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.FcmProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
