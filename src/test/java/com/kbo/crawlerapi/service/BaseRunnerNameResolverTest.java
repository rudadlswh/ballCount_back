package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaseRunnerNameResolverTest {

    private final BaseRunnerNameResolver resolver = new BaseRunnerNameResolver();

    @Test
    void infersNewFirstBaseRunnerFromPreviousBatterOnOnBaseState() {
        GameSnapshot previous = snapshot("손성빈", 0, false, false, false, null, null, null);
        ParsedGameDetail current = parsed(0, true, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("손성빈");
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void carriesForwardRunnerNameWhenBaseRemainsOccupiedAndOfficialNameMissing() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, true, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("전민재");
    }

    @Test
    void clearsRunnerNameWhenBaseBecomesEmpty() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, false, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void advancesFirstBaseRunnerToSecondOnSimpleReliableMovement() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, false, true, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isEqualTo("전민재");
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void ambiguousMultiRunnerMovementDoesNotAssignIncorrectNames() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, true, "1루주자", null, "3루주자");
        ParsedGameDetail current = parsed(0, false, true, true, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isEqualTo("3루주자");
    }

    @Test
    void officialRunnerNamesWinOverInference() {
        GameSnapshot previous = snapshot("전민재", 0, false, false, false, null, null, null);
        ParsedGameDetail current = parsed(0, true, false, false, "공식주자", null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("공식주자");
    }

    private GameSnapshot snapshot(
            String currentBatterName,
            Integer outs,
            boolean first,
            boolean second,
            boolean third,
            String firstName,
            String secondName,
            String thirdName
    ) {
        return new GameSnapshot(
                UUID.randomUUID(),
                null,
                1,
                "top",
                "Top 1",
                0,
                0,
                outs,
                first,
                second,
                third,
                firstName,
                secondName,
                thirdName,
                firstName == null ? null : "first-id",
                secondName == null ? null : "second-id",
                thirdName == null ? null : "third-id",
                "투수",
                currentBatterName,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now()
        );
    }

    private ParsedGameDetail parsed(
            Integer outs,
            boolean first,
            boolean second,
            boolean third,
            String firstName,
            String secondName,
            String thirdName
    ) {
        return new ParsedGameDetail(
                "20260506LTSS0",
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                0,
                0,
                1,
                "top",
                "Top 1",
                0,
                0,
                outs,
                first,
                second,
                third,
                firstName,
                secondName,
                thirdName,
                null,
                null,
                null,
                "투수",
                "다음타자",
                null,
                null,
                true,
                null,
                null,
                UUID.randomUUID().toString()
        );
    }
}
