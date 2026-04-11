package com.kbo.crawlerapi.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.GameSnapshot;

public interface GameSnapshotRepository extends JpaRepository<GameSnapshot, UUID> {

    Optional<GameSnapshot> findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(UUID gameId);
}
