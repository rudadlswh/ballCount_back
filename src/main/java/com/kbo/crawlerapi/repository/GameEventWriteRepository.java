package com.kbo.crawlerapi.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface GameEventWriteRepository {

    int upsertEvents(List<GameEventWriteRow> rows);

    record GameEventWriteRow(
            UUID gameId,
            String providerEventId,
            int sequenceNumber,
            Integer inning,
            String inningHalf,
            String eventType,
            String eventText,
            OffsetDateTime sourceUpdatedAt
    ) {
    }
}
