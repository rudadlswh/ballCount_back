package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.api.dto.TeamStandingDto;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.TeamRankReadRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeagueReadServiceTest {

    @Mock TeamRepository teamRepository;
    @Mock TeamRankReadRepository teamRankReadRepository;
    private LeagueReadService service;

    @BeforeEach
    void setUp() {
        service = new LeagueReadService(
                teamRepository,
                teamRankReadRepository,
                Clock.fixed(Instant.parse("2026-07-20T03:00:00Z"), ZoneId.of("Asia/Seoul"))
        );
    }

    @Test
    void teamsAreExposedByStableTeamCodeOrder() {
        Team ssg = new Team(UUID.randomUUID(), "ssg", "SSG 랜더스", "SSG", "SSG Landers", null);
        Team lg = new Team(UUID.randomUUID(), "lg", "LG 트윈스", "LG", "LG Twins", null);
        when(teamRepository.findAll()).thenReturn(List.of(ssg, lg));

        var response = service.getTeams();

        assertThat(response.teams()).extracting("id").containsExactly("lg", "ssg");
    }

    @Test
    void standingsExposeFreshnessFromLatestRow() {
        OffsetDateTime updatedAt = OffsetDateTime.of(2026, 7, 18, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        when(teamRankReadRepository.findBySeason(2026)).thenReturn(List.of(new TeamStandingDto(
                2026, UUID.randomUUID(), "lg", "LG 트윈스", 1, 2, 90, 55, 32, 3,
                new BigDecimal("0.632"), BigDecimal.ZERO, "W", 3, "3승", LocalDate.of(2026, 7, 18),
                updatedAt, updatedAt
        )));

        var response = service.getStandings(2026);

        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(response.isStale()).isTrue();
    }

    @Test
    void standingsRejectImpossibleSeason() {
        assertThatThrownBy(() -> service.getStandings(1800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1982-2100");
    }
}
