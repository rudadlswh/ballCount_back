package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class BaseRunnerNameResolver {

    private static final Logger log = LoggerFactory.getLogger(BaseRunnerNameResolver.class);

    ResolvedBaseRunners resolve(GameSnapshot previous, ParsedGameDetail current) {
        return resolve(previous, current, PinchRunnerOverrides.empty());
    }

    ResolvedBaseRunners resolve(GameSnapshot previous, ParsedGameDetail current, PinchRunnerOverrides pinchRunnerOverrides) {
        PinchRunnerOverrides overrides = pinchRunnerOverrides == null ? PinchRunnerOverrides.empty() : pinchRunnerOverrides;
        Runner first = resolveBase(
                previous,
                Base.FIRST,
                current.runnerOnFirst(),
                current.firstBaseRunnerName(),
                current.firstBaseRunnerId(),
                overrides.first(),
                true,
                current
        );
        Runner second = resolveBase(
                previous,
                Base.SECOND,
                current.runnerOnSecond(),
                current.secondBaseRunnerName(),
                current.secondBaseRunnerId(),
                overrides.second(),
                false,
                current
        );
        Runner third = resolveBase(
                previous,
                Base.THIRD,
                current.runnerOnThird(),
                current.thirdBaseRunnerName(),
                current.thirdBaseRunnerId(),
                overrides.third(),
                false,
                current
        );

        String source = overrides.hasAny() ? "liveTextSubstitution" : resolutionSource(current, first, second, third);
        if (previous != null && source.equals("occupancyOnly")) {
            log.debug(
                    "[BaseRunners] carryForward skipped reason=missingOfficialRunnerNames gameId={} previousBases={} currentBases={}",
                    current.providerGameId(),
                    baseKey(previous),
                    baseKey(current)
            );
        }
        log.debug(
                "[BaseRunners] resolved atomic first={} second={} third={} source={}",
                displayName(first.name()),
                displayName(second.name()),
                displayName(third.name()),
                source
        );
        return new ResolvedBaseRunners(
                first.name(),
                second.name(),
                third.name(),
                first.id(),
                second.id(),
                third.id(),
                source
        );
    }

    private String resolutionSource(ParsedGameDetail current, Runner first, Runner second, Runner third) {
        boolean hasRunnerValue = first.name() != null
                || second.name() != null
                || third.name() != null
                || first.id() != null
                || second.id() != null
                || third.id() != null;
        if (hasRunnerValue) {
            return hasBaseBattingOrder(current) ? "payload/order" : "payload";
        }
        if (baseCount(current) > 0) {
            return "occupancyOnly";
        }
        return "none";
    }

    private boolean hasBaseBattingOrder(ParsedGameDetail current) {
        return current.firstBaseBattingOrder() != null && current.firstBaseBattingOrder() > 0
                || current.secondBaseBattingOrder() != null && current.secondBaseBattingOrder() > 0
                || current.thirdBaseBattingOrder() != null && current.thirdBaseBattingOrder() > 0;
    }

    private Runner resolveBase(
            GameSnapshot previous,
            Base base,
            boolean occupied,
            String officialName,
            String officialId,
            RunnerOverride pinchRunnerOverride,
            boolean inferFromPreviousBatter,
            ParsedGameDetail current
    ) {
        if (!occupied) {
            return Runner.empty();
        }
        if (pinchRunnerOverride != null && clean(pinchRunnerOverride.runnerName()) != null) {
            return new Runner(clean(pinchRunnerOverride.runnerName()), null);
        }
        Runner batterReachedFirst = batterReachedFirstFromPreviousBatter(previous, current, base);
        if (batterReachedFirst.name() != null || batterReachedFirst.id() != null) {
            return batterReachedFirst;
        }
        String name = clean(officialName);
        String id = clean(officialId);
        if (name != null || id != null) {
            return new Runner(name, id);
        }
        Runner carried = carryForward(previous, current, base);
        if (carried.name() != null || carried.id() != null) {
            return carried;
        }
        Runner inferred = inferFromCompletedPlay(previous, current, base, inferFromPreviousBatter);
        if (inferred.name() != null || inferred.id() != null) {
            return inferred;
        }
        return Runner.empty();
    }

    private Runner batterReachedFirstFromPreviousBatter(GameSnapshot previous, ParsedGameDetail current, Base base) {
        if (base != Base.FIRST
                || !canUsePreviousSnapshot(previous)
                || !sameHalfInning(previous, current)
                || previous.isRunnerOnFirst()
                || !current.runnerOnFirst()
                || !java.util.Objects.equals(previous.getOuts(), current.outs())
                || !countReset(previous, current)) {
            return Runner.empty();
        }
        String previousBatter = clean(previous.getCurrentBatterName());
        String nextBatter = clean(current.currentBatterName());
        if (previousBatter == null || nextBatter == null || previousBatter.equals(nextBatter)) {
            return Runner.empty();
        }
        log.debug(
                "[BaseRunners] batterReachedFirst override reason=previousBatterOnBase previousBatter={} nextBatter={} firstBefore={} firstAfter={} outsBefore={} outsAfter={}",
                previousBatter,
                nextBatter,
                previous.isRunnerOnFirst(),
                current.runnerOnFirst(),
                previous.getOuts(),
                current.outs()
        );
        return new Runner(previousBatter, null);
    }

    private Runner carryForward(GameSnapshot previous, ParsedGameDetail current, Base base) {
        if (!canCarryForward(previous, current) || !previousOccupied(previous, base)) {
            return Runner.empty();
        }
        return switch (base) {
            case FIRST -> new Runner(clean(previous.getFirstBaseRunnerName()), clean(previous.getFirstBaseRunnerId()));
            case SECOND -> new Runner(clean(previous.getSecondBaseRunnerName()), clean(previous.getSecondBaseRunnerId()));
            case THIRD -> new Runner(clean(previous.getThirdBaseRunnerName()), clean(previous.getThirdBaseRunnerId()));
        };
    }

    private Runner inferFromCompletedPlay(
            GameSnapshot previous,
            ParsedGameDetail current,
            Base base,
            boolean inferBatterToFirst
    ) {
        String result = clean(current.lastCompletedPlayResult());
        if (result == null) {
            return Runner.empty();
        }
        String batterName = clean(current.lastCompletedBatterName());
        return switch (base) {
            case FIRST -> inferBatterToFirst && batterName != null && isFirstBaseResult(result)
                    ? new Runner(batterName, null)
                    : Runner.empty();
            case SECOND -> {
                if (batterName != null && isSecondBaseResult(result)) {
                    yield new Runner(batterName, null);
                }
                yield canInferRunnerMovement(previous, current, result) && previous.isRunnerOnFirst()
                        ? new Runner(clean(previous.getFirstBaseRunnerName()), clean(previous.getFirstBaseRunnerId()))
                        : Runner.empty();
            }
            case THIRD -> {
                if (batterName != null && isThirdBaseResult(result)) {
                    yield new Runner(batterName, null);
                }
                if (canInferRunnerMovement(previous, current, result) && previous.isRunnerOnSecond()) {
                    yield new Runner(clean(previous.getSecondBaseRunnerName()), clean(previous.getSecondBaseRunnerId()));
                }
                yield Runner.empty();
            }
        };
    }

    private boolean canInferRunnerMovement(GameSnapshot previous, ParsedGameDetail current, String result) {
        return canUsePreviousSnapshot(previous)
                && sameHalfInning(previous, current)
                && java.util.Objects.equals(previous.getOuts(), current.outs())
                && java.util.Objects.equals(previous.getAwayScore(), current.awayScore())
                && java.util.Objects.equals(previous.getHomeScore(), current.homeScore())
                && isRunnerAdvanceResult(result);
    }

    private boolean isFirstBaseResult(String result) {
        if (result.contains("2루타") || result.contains("3루타") || result.contains("홈런")) {
            return false;
        }
        return result.contains("안타")
                || result.contains("볼넷")
                || result.contains("사구")
                || result.contains("몸에 맞")
                || result.contains("실책")
                || result.contains("야수선택")
                || result.contains("출루");
    }

    private boolean isSecondBaseResult(String result) {
        return result.contains("2루타");
    }

    private boolean isThirdBaseResult(String result) {
        return result.contains("3루타");
    }

    private boolean isRunnerAdvanceResult(String result) {
        return result.contains("도루")
                || result.contains("폭투")
                || result.contains("포일")
                || result.contains("진루")
                || result.contains("보크");
    }

    private boolean canCarryForward(GameSnapshot previous, ParsedGameDetail current) {
        return canUsePreviousSnapshot(previous)
                && sameHalfInning(previous, current)
                && sameBatter(previous, current)
                && java.util.Objects.equals(previous.getOuts(), current.outs())
                && java.util.Objects.equals(previous.getAwayScore(), current.awayScore())
                && java.util.Objects.equals(previous.getHomeScore(), current.homeScore())
                && !countReset(previous, current)
                && java.util.Objects.equals(clean(previous.getRawHash()), clean(current.rawHash()))
                && !hasBaseBattingOrder(current)
                && !hasBattedBallOrOutResult(current)
                && baseKey(previous).equals(baseKey(current));
    }

    private boolean canUsePreviousSnapshot(GameSnapshot previous) {
        return previous != null;
    }

    private boolean sameHalfInning(GameSnapshot previous, ParsedGameDetail current) {
        return java.util.Objects.equals(previous.getInning(), current.inning())
                && java.util.Objects.equals(clean(previous.getInningHalf()), clean(current.inningHalf()));
    }

    private boolean sameBatter(GameSnapshot previous, ParsedGameDetail current) {
        return java.util.Objects.equals(clean(previous.getCurrentBatterName()), clean(current.currentBatterName()));
    }

    private boolean countReset(GameSnapshot previous, ParsedGameDetail current) {
        return java.util.Objects.equals(current.balls(), 0)
                && java.util.Objects.equals(current.strikes(), 0)
                && ((previous.getBalls() != null && previous.getBalls() > 0)
                || (previous.getStrikes() != null && previous.getStrikes() > 0));
    }

    private boolean hasBattedBallOrOutResult(ParsedGameDetail current) {
        String result = clean(current.lastCompletedPlayResult());
        if (result == null) {
            return false;
        }
        return result.contains("땅볼")
                || result.contains("뜬공")
                || result.contains("플라이")
                || result.contains("직선타")
                || result.contains("병살")
                || result.contains("야수선택")
                || result.contains("아웃")
                || result.contains("삼진")
                || result.contains("희생")
                || result.contains("번트");
    }

    private boolean previousOccupied(GameSnapshot previous, Base base) {
        return switch (base) {
            case FIRST -> previous.isRunnerOnFirst();
            case SECOND -> previous.isRunnerOnSecond();
            case THIRD -> previous.isRunnerOnThird();
        };
    }

    private int baseCount(ParsedGameDetail detail) {
        return (detail.runnerOnFirst() ? 1 : 0)
                + (detail.runnerOnSecond() ? 1 : 0)
                + (detail.runnerOnThird() ? 1 : 0);
    }

    private String baseKey(GameSnapshot snapshot) {
        return "%s%s%s".formatted(snapshot.isRunnerOnFirst() ? "1" : "-", snapshot.isRunnerOnSecond() ? "2" : "-", snapshot.isRunnerOnThird() ? "3" : "-");
    }

    private String baseKey(ParsedGameDetail detail) {
        return "%s%s%s".formatted(detail.runnerOnFirst() ? "1" : "-", detail.runnerOnSecond() ? "2" : "-", detail.runnerOnThird() ? "3" : "-");
    }

    private String displayName(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "<nil>" : cleaned;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record ResolvedBaseRunners(
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName,
            String firstBaseRunnerId,
            String secondBaseRunnerId,
            String thirdBaseRunnerId,
            String source
    ) {
    }

    record PinchRunnerOverrides(
            RunnerOverride first,
            RunnerOverride second,
            RunnerOverride third
    ) {
        static PinchRunnerOverrides empty() {
            return new PinchRunnerOverrides(null, null, null);
        }

        boolean hasAny() {
            return first != null || second != null || third != null;
        }

        String signature() {
            return "%s|%s|%s".formatted(
                    first == null ? "-" : first.signature(),
                    second == null ? "-" : second.signature(),
                    third == null ? "-" : third.signature()
            );
        }
    }

    record RunnerOverride(String replacedRunnerName, String runnerName) {
        String signature() {
            return "%s>%s".formatted(replacedRunnerName, runnerName);
        }
    }

    private record Runner(String name, String id) {
        static Runner empty() {
            return new Runner(null, null);
        }
    }

    private enum Base {
        FIRST,
        SECOND,
        THIRD
    }
}
