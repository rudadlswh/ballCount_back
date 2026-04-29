package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapResponse;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.TeamRepository;

@ExtendWith(MockitoExtension.class)
class CompatibilityBootstrapServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-10T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private TeamRepository teamRepository;

    private CompatibilityBootstrapService compatibilityBootstrapService;

    @BeforeEach
    void setUp() {
        compatibilityBootstrapService = new CompatibilityBootstrapService(
                gameRepository,
                gameSnapshotRepository,
                teamRepository,
                FIXED_CLOCK
        );
    }

    @Test
    void getBootstrapIncludesAllAvailableGamesAcrossMonths() {
        Team lg = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team kia = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );

        Game aprilGame = new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260410-KIA-LG",
                "kbo",
                "20260410HTLG0",
                LocalDate.of(2026, 4, 10),
                OffsetDateTime.of(2026, 4, 10, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.LIVE,
                lg,
                kia,
                4,
                3,
                "8회초",
                false,
                false,
                null,
                null,
                null
        );
        Game mayGame = new Game(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "20260501-KIA-LG",
                "kbo",
                "20260501HTLG0",
                LocalDate.of(2026, 5, 1),
                OffsetDateTime.of(2026, 5, 1, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                lg,
                kia,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null
        );

        GameSnapshot liveSnapshot = new GameSnapshot(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                aprilGame,
                8,
                "top",
                "8회초",
                1,
                2,
                1,
                true,
                false,
                true,
                null,
                null,
                4,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                "hash",
                null,
                OffsetDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findAllByOrderByGameDateAscScheduledAtAscPublicGameIdAsc())
                .thenReturn(List.of(aprilGame, mayGame));
        when(teamRepository.findAll()).thenReturn(List.of(lg, kia));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(aprilGame.getId())))
                .thenReturn(Optional.of(liveSnapshot));

        CompatibilityBootstrapResponse response = compatibilityBootstrapService.getBootstrap();

        assertThat(response.games()).hasSize(2);
        assertThat(response.games().get(0).scheduledStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(response.games().get(1).scheduledStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(response.games().get(0).bases()).isNotNull();
        assertThat(response.games().get(1).bases()).isNull();
        assertThat(response.teams()).hasSize(2);

        verify(gameSnapshotRepository).findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(aprilGame.getId()));
        verify(gameSnapshotRepository, never()).findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(mayGame.getId()));
    }
}
