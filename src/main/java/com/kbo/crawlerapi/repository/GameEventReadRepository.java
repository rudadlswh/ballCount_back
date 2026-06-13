package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;

public interface GameEventReadRepository {

    List<GameEventRow> findRecentByGameId(UUID gameId, int limit);

    record GameEventRow(
            int sequenceNumber,
            Integer inning,
            String inningHalf,
            String eventType,
            String eventText
    ) {
    }
}
