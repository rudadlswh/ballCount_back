package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.LineScore;

public interface LineScoreRepository extends JpaRepository<LineScore, UUID> {

    List<LineScore> findByGame_IdOrderByInningNumberAsc(UUID gameId);

    long countByGame_Id(UUID gameId);

    void deleteByGame_Id(UUID gameId);
}
