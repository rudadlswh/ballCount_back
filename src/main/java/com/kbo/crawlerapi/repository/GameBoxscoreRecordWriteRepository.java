package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;

public interface GameBoxscoreRecordWriteRepository {

    int upsertBatterRecords(List<BatterRecordWriteRow> rows);

    int upsertPitcherRecords(List<PitcherRecordWriteRow> rows);

    record BatterRecordWriteRow(
            UUID gameId,
            UUID teamId,
            int sourceOrder,
            Integer battingOrder,
            String position,
            String playerName,
            Integer atBats,
            Integer runs,
            Integer hits,
            Integer rbi,
            Integer homeRuns,
            Integer walks,
            Integer strikeouts,
            Integer stolenBases,
            String battingAverage
    ) {
    }

    record PitcherRecordWriteRow(
            UUID gameId,
            UUID teamId,
            int sourceOrder,
            Integer pitchingOrder,
            String playerName,
            String appearance,
            String decisionResult,
            Integer wins,
            Integer losses,
            Integer saves,
            String inningsPitched,
            Integer battersFaced,
            Integer pitchCount,
            Integer atBats,
            Integer hits,
            Integer homeRuns,
            Integer walksOrHitByPitch,
            Integer strikeouts,
            Integer runs,
            Integer earnedRuns,
            String era
    ) {
    }
}
