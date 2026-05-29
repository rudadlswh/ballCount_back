package com.kbo.crawlerapi.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;

public interface GameRepository extends JpaRepository<Game, UUID> {

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findAllByOrderByGameDateAscScheduledAtAscPublicGameIdAsc();

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate gameDate);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByGameDateBetweenOrderByGameDateAscScheduledAtAscPublicGameIdAsc(LocalDate startDate, LocalDate endDate);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByGameDateBetweenAndStatusOrderByGameDateAscScheduledAtAscPublicGameIdAsc(
            LocalDate startDate,
            LocalDate endDate,
            GameStatus status
    );

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    Optional<Game> findByPublicGameId(String publicGameId);

    Optional<Game> findByProviderAndProviderGameId(String provider, String providerGameId);

    Optional<Game> findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
            String provider,
            LocalDate gameDate,
            UUID homeTeamId,
            UUID awayTeamId
    );

    @Query(value = """
            SELECT g.public_game_id
            FROM kbo_crawler_api.games g
            WHERE g.game_date = :gameDate
              AND g.status = 'final'
              AND (
                  NOT EXISTS (
                      SELECT 1
                      FROM kbo_crawler_api.game_batter_records br
                      WHERE br.game_id = g.id
                  )
                  OR NOT EXISTS (
                      SELECT 1
                      FROM kbo_crawler_api.game_pitcher_records pr
                      WHERE pr.game_id = g.id
                  )
              )
            ORDER BY g.scheduled_at ASC NULLS LAST, g.public_game_id ASC
            """, nativeQuery = true)
    List<String> findFinalPublicGameIdsMissingBoxscoreRecordsByDate(@Param("gameDate") LocalDate gameDate);
}
