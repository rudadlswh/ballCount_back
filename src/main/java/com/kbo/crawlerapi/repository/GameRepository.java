package com.kbo.crawlerapi.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.Game;

public interface GameRepository extends JpaRepository<Game, UUID> {

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findAllByOrderByGameDateAscScheduledAtAscPublicGameIdAsc();

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate gameDate);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByGameDateBetweenOrderByGameDateAscScheduledAtAscPublicGameIdAsc(LocalDate startDate, LocalDate endDate);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    Optional<Game> findByPublicGameId(String publicGameId);

    Optional<Game> findByProviderAndProviderGameId(String provider, String providerGameId);

    Optional<Game> findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
            String provider,
            LocalDate gameDate,
            UUID homeTeamId,
            UUID awayTeamId
    );
}
