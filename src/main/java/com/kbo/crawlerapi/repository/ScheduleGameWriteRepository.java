package com.kbo.crawlerapi.repository;

import java.time.OffsetDateTime;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;

public interface ScheduleGameWriteRepository {

    GameWriteResult upsertScheduleGame(
            ParsedScheduleGame parsedGame,
            Team awayTeam,
            Team homeTeam,
            String publicGameId,
            OffsetDateTime sourceUpdatedAt
    );

    record GameWriteResult(boolean created, boolean updated) {
    }
}
