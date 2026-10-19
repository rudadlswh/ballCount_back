package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.GameReadService;
import com.kbo.crawlerapi.service.LiveGameStreamRegistry;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameLiveStreamControllerTest {

    @Test
    void subscribesProviderGameIdWithResolvedPublicGameId() {
        GameRepository gameRepository = mock(GameRepository.class);
        EmptySnapshotGameReadService gameReadService = new EmptySnapshotGameReadService();
        LiveGameStreamRegistry streamRegistry = new LiveGameStreamRegistry();
        GameLiveStreamController controller = new GameLiveStreamController(gameRepository, gameReadService, streamRegistry);
        Game game = game();

        when(gameRepository.findByPublicGameId("20260707SKOB0")).thenReturn(Optional.empty());
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260707SKOB0")).thenReturn(Optional.of(game));

        controller.streamGame("20260707SKOB0", new MockHttpServletRequest());

        assertThat(streamRegistry.subscriberCount("20260707-DOO-SSG")).isEqualTo(1);
        assertThat(streamRegistry.subscriberCount("20260707SKOB0")).isZero();
    }

    @Test
    void trimsAndNormalizesPublicGameIdSubscriptionKey() {
        GameRepository gameRepository = mock(GameRepository.class);
        EmptySnapshotGameReadService gameReadService = new EmptySnapshotGameReadService();
        LiveGameStreamRegistry streamRegistry = new LiveGameStreamRegistry();
        GameLiveStreamController controller = new GameLiveStreamController(gameRepository, gameReadService, streamRegistry);

        when(gameRepository.findByPublicGameId("20260707-DOO-SSG")).thenReturn(Optional.of(game()));
        controller.streamGame(" 20260707-doo-ssg ", new MockHttpServletRequest());
        assertThat(streamRegistry.subscriberCount("20260707-DOO-SSG")).isEqualTo(1);
    }

    @Test
    void unknownGameDoesNotAllocateAConnection() {
        GameRepository repository = mock(GameRepository.class);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry();
        GameLiveStreamController controller = new GameLiveStreamController(repository, new EmptySnapshotGameReadService(), registry);
        assertThatThrownBy(() -> controller.streamGame("unknown", new MockHttpServletRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(registry.subscriberCount("unknown")).isZero();
    }

    @Test
    void httpRejectsUnknownGamesAndExcessSubscriptionsBeforeStartingAsync() throws Exception {
        GameRepository repository = mock(GameRepository.class);
        when(repository.findByPublicGameId("20260707-DOO-SSG")).thenReturn(Optional.of(game()));
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setLiveStreamMaxConnections(1);
        LiveGameStreamRegistry registry = new LiveGameStreamRegistry(properties);
        var mvc = MockMvcBuilders.standaloneSetup(new GameLiveStreamController(repository,
                new EmptySnapshotGameReadService(), registry)).setControllerAdvice(new ApiExceptionHandler()).build();
        var unknown = mvc.perform(get("/api/v1/games/unknown/stream")).andExpect(status().isNotFound()).andReturn();
        assertThat(unknown.getRequest().isAsyncStarted()).isFalse();
        var accepted = mvc.perform(get("/api/v1/games/20260707-DOO-SSG/stream")).andExpect(status().isOk()).andReturn();
        assertThat(accepted.getRequest().isAsyncStarted()).isTrue();
        var limited = mvc.perform(get("/api/v1/games/20260707-DOO-SSG/stream"))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(limited.getRequest().isAsyncStarted()).isFalse();
        assertThat(registry.subscriberCount("20260707-DOO-SSG")).isEqualTo(1);
        registry.complete("20260707-DOO-SSG");
    }

    private static final class EmptySnapshotGameReadService extends GameReadService {

        private EmptySnapshotGameReadService() {
            super(null, null, null, null, null);
        }

        @Override
        public Optional<GameLiveStateResponse> getLatestSnapshotLiveState(String gameId) {
            return Optional.empty();
        }
    }

    private Game game() {
        Team homeTeam = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                null
        );
        Team awayTeam = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "doosan",
                "Doosan Bears",
                "두산",
                "Doosan Bears",
                null
        );
        return new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260707-DOO-SSG",
                "kbo",
                "20260707SKOB0",
                LocalDate.of(2026, 7, 7),
                OffsetDateTime.of(2026, 7, 7, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "문학",
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                0,
                0,
                "1회초",
                false,
                false,
                null,
                null,
                null
        );
    }
}
