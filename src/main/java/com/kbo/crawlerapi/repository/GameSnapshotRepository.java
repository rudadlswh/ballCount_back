package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import com.kbo.crawlerapi.domain.GameSnapshot;

public interface GameSnapshotRepository extends JpaRepository<GameSnapshot, UUID> {

    Optional<GameSnapshot> findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(UUID gameId);

    long countByGame_Id(UUID gameId);

    @Query("""
            SELECT snapshot
            FROM GameSnapshot snapshot
            WHERE snapshot.game.id = :gameId
            ORDER BY COALESCE(snapshot.sourceUpdatedAt, snapshot.fetchedAt, snapshot.createdAt) ASC,
                     snapshot.fetchedAt ASC,
                     snapshot.createdAt ASC
            """)
    List<GameSnapshot> findReplaySnapshotsByGameId(@Param("gameId") UUID gameId);

    @Query("""
            SELECT snapshot
            FROM GameSnapshot snapshot
            WHERE snapshot.game.id = :gameId
            ORDER BY COALESCE(snapshot.sourceUpdatedAt, snapshot.fetchedAt, snapshot.createdAt) DESC,
                     snapshot.fetchedAt DESC,
                     snapshot.createdAt DESC
            """)
    List<GameSnapshot> findRecentReplaySnapshotsByGameId(@Param("gameId") UUID gameId, Pageable pageable);
}
