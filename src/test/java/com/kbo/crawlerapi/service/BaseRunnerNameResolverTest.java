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
    void doesNotInferNewFirstBaseRunnerFromPreviousBatterOnOnBaseState() {
        GameSnapshot previous = snapshot("손성빈", 0, false, false, false, null, null, null);
        ParsedGameDetail current = parsed(0, true, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void doesNotCarryForwardRunnerNameWhenBaseRemainsOccupiedAndOfficialNameMissing() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, true, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void clearsRunnerNameWhenBaseBecomesEmpty() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, false, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.firstBaseRunnerId()).isNull();
        assertThat(result.secondBaseRunnerId()).isNull();
        assertThat(result.thirdBaseRunnerId()).isNull();
    }

    @Test
    void doesNotAdvanceFirstBaseRunnerToSecondWithoutOfficialRunnerFields() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, false, true, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void doesNotPushPreviousRunnerOrBatterOnBaseTransitionWithoutOfficialRunnerFields() {
        GameSnapshot previous = snapshot("손성빈", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, true, true, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void ambiguousMultiRunnerMovementDoesNotAssignIncorrectNames() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, true, "1루주자", null, "3루주자");
        ParsedGameDetail current = parsed(0, false, true, true, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void firstAndSecondOccupiedWithOnlyFirstKnownDoesNotInventSecondName() {
        ParsedGameDetail current = parsed(0, true, true, false, "최준우", null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(null, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("최준우");
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void doesNotInferOnlyNewFirstBaseRunnerWhenOtherOccupiedBaseIsUnnamed() {
        GameSnapshot previous = snapshot("최지훈", 0, false, false, true, null, null, null);
        ParsedGameDetail current = parsed(0, true, false, true, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void officialRunnerNamesWinOverInference() {
        GameSnapshot previous = snapshot("전민재", 0, false, false, false, null, null, null);
        ParsedGameDetail current = parsed(0, true, false, false, "공식주자", null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("공식주자");
        assertThat(result.source()).contains("payload");
    }

    @Test
    void occupiedFirstAndThirdWithOnlyFirstKnownDoesNotCopyNameToThird() {
        ParsedGameDetail current = parsed(0, true, false, true, "송찬의", null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(null, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("송찬의");
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void sameBaseCarryForwardDoesNotPopulateDifferentOccupiedBase() {
        GameSnapshot previous = snapshot("다음타자", 0, true, false, false, "전민재", null, null);
        ParsedGameDetail current = parsed(0, false, false, true, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
    }

    @Test
    void inningHalfChangeClearsCarriedRunnerNames() {
        GameSnapshot previous = snapshot("이전타자", 2, true, false, false, "1루주자", null, null);
        ParsedGameDetail current = parsed(0, true, false, false, null, null, null, 1, "bottom");

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("occupancyOnly");
    }

    @Test
    void allBasesEmptyClearsCarriedRunnerNames() {
        GameSnapshot previous = snapshot("이전타자", 2, true, true, true, "1루주자", "2루주자", "3루주자");
        ParsedGameDetail current = parsed(0, false, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
        assertThat(result.source()).isEqualTo("none");
    }

    @Test
    void resolvesAllBasesFromSameCurrentSnapshotWithoutUsingPreviousNames() {
        GameSnapshot previous = snapshot("강승호", 0, false, true, true, null, "박찬호", null);
        ParsedGameDetail current = parsed(
                0,
                true,
                true,
                true,
                "강승호",
                "류승민",
                "박찬호",
                6,
                "bottom",
                "김민석"
        );

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isEqualTo("강승호");
        assertThat(result.secondBaseRunnerName()).isEqualTo("류승민");
        assertThat(result.thirdBaseRunnerName()).isEqualTo("박찬호");
        assertThat(current.currentBatterName()).isEqualTo("김민석");
        assertThat(result.source()).contains("payload");
    }

    @Test
    void sideChangeOutsClearCarriedRunnerNames() {
        GameSnapshot previous = snapshot("이전타자", 2, true, false, false, "1루주자", null, null);
        ParsedGameDetail current = parsed(3, true, false, false, null, null, null);

        BaseRunnerNameResolver.ResolvedBaseRunners result = resolver.resolve(previous, current);

        assertThat(result.firstBaseRunnerName()).isNull();
        assertThat(result.secondBaseRunnerName()).isNull();
        assertThat(result.thirdBaseRunnerName()).isNull();
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
        return parsed(outs, first, second, third, firstName, secondName, thirdName, 1, "top");
    }

    private ParsedGameDetail parsed(
            Integer outs,
            boolean first,
            boolean second,
            boolean third,
            String firstName,
            String secondName,
            String thirdName,
            Integer inning,
            String inningHalf
    ) {
        return parsed(outs, first, second, third, firstName, secondName, thirdName, inning, inningHalf, "다음타자");
    }

    private ParsedGameDetail parsed(
            Integer outs,
            boolean first,
            boolean second,
            boolean third,
            String firstName,
            String secondName,
            String thirdName,
            Integer inning,
            String inningHalf,
            String currentBatterName
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
                inning,
                inningHalf,
                inning == null || inningHalf == null ? null : "%s %d".formatted("top".equals(inningHalf) ? "Top" : "Bottom", inning),
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
                currentBatterName,
                null,
                null,
                true,
                null,
                null,
                UUID.randomUUID().toString()
        );
    }
}
