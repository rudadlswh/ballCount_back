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
        Runner first = resolveBase(
                previous,
                Base.FIRST,
                current.runnerOnFirst(),
                current.firstBaseRunnerName(),
                current.firstBaseRunnerId(),
                true,
                current
        );
        Runner second = resolveBase(
                previous,
                Base.SECOND,
                current.runnerOnSecond(),
                current.secondBaseRunnerName(),
                current.secondBaseRunnerId(),
                false,
                current
        );
        Runner third = resolveBase(
                previous,
                Base.THIRD,
                current.runnerOnThird(),
                current.thirdBaseRunnerName(),
                current.thirdBaseRunnerId(),
                false,
                current
        );

        String source = resolutionSource(current, first, second, third);
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
            boolean inferFromPreviousBatter,
            ParsedGameDetail current
    ) {
        if (!occupied) {
            return Runner.empty();
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
        if (inferFromPreviousBatter && canInferFirstBaseRunner(previous, current)) {
            return new Runner(clean(previous.getCurrentBatterName()), null);
        }
        return Runner.empty();
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

    private boolean canInferFirstBaseRunner(GameSnapshot previous, ParsedGameDetail current) {
        if (!canUsePreviousSnapshot(previous) || !current.runnerOnFirst() || current.runnerOnSecond() || current.runnerOnThird()) {
            return false;
        }
        if (previous.isRunnerOnFirst() || previous.isRunnerOnSecond() || previous.isRunnerOnThird()) {
            return false;
        }
        return sameHalfInning(previous, current) && clean(previous.getCurrentBatterName()) != null;
    }

    private boolean canCarryForward(GameSnapshot previous, ParsedGameDetail current) {
        return canUsePreviousSnapshot(previous)
                && sameHalfInning(previous, current)
                && java.util.Objects.equals(previous.getOuts(), current.outs())
                && baseKey(previous).equals(baseKey(current));
    }

    private boolean canUsePreviousSnapshot(GameSnapshot previous) {
        return previous != null && previous.getGame() != null;
    }

    private boolean sameHalfInning(GameSnapshot previous, ParsedGameDetail current) {
        return java.util.Objects.equals(previous.getInning(), current.inning())
                && java.util.Objects.equals(clean(previous.getInningHalf()), clean(current.inningHalf()));
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
