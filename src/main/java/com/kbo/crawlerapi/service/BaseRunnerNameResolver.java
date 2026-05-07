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
                current.runnerOnFirst(),
                current.firstBaseRunnerName(),
                current.firstBaseRunnerId(),
                previous == null ? false : previous.isRunnerOnFirst(),
                previous == null ? null : previous.getFirstBaseRunnerName(),
                previous == null ? null : previous.getFirstBaseRunnerId()
        );
        Runner second = resolveBase(
                current.runnerOnSecond(),
                current.secondBaseRunnerName(),
                current.secondBaseRunnerId(),
                previous == null ? false : previous.isRunnerOnSecond(),
                previous == null ? null : previous.getSecondBaseRunnerName(),
                previous == null ? null : previous.getSecondBaseRunnerId()
        );
        Runner third = resolveBase(
                current.runnerOnThird(),
                current.thirdBaseRunnerName(),
                current.thirdBaseRunnerId(),
                previous == null ? false : previous.isRunnerOnThird(),
                previous == null ? null : previous.getThirdBaseRunnerName(),
                previous == null ? null : previous.getThirdBaseRunnerId()
        );

        boolean carried = hasCarriedForward(previous, current, first, second, third);
        if (carried) {
            log.info(
                    "[BaseRunners] carriedForward first={} second={} third={}",
                    displayName(first.name()),
                    displayName(second.name()),
                    displayName(third.name())
            );
        }

        Inference inference = infer(previous, current, first, second, third);
        if (inference.ambiguous()) {
            log.info("[BaseRunners] inference skipped reason=ambiguous");
        }
        if (inference.reason() != null) {
            log.info(
                    "[BaseRunners] inferred first={} second={} third={} reason={}",
                    displayName(inference.first().name()),
                    displayName(inference.second().name()),
                    displayName(inference.third().name()),
                    inference.reason()
            );
        }

        return new ResolvedBaseRunners(
                inference.first().name(),
                inference.second().name(),
                inference.third().name(),
                inference.first().id(),
                inference.second().id(),
                inference.third().id()
        );
    }

    private Runner resolveBase(
            boolean occupied,
            String officialName,
            String officialId,
            boolean previouslyOccupied,
            String previousName,
            String previousId
    ) {
        if (!occupied) {
            return Runner.empty();
        }
        String name = clean(officialName);
        String id = clean(officialId);
        if (name != null || id != null) {
            return new Runner(name, id);
        }
        if (previouslyOccupied) {
            return new Runner(clean(previousName), clean(previousId));
        }
        return Runner.empty();
    }

    private Inference infer(GameSnapshot previous, ParsedGameDetail current, Runner first, Runner second, Runner third) {
        if (previous == null) {
            return new Inference(first, second, third, false, null);
        }

        Runner resolvedFirst = first;
        Runner resolvedSecond = second;
        Runner resolvedThird = third;
        String reason = null;
        boolean ambiguous = false;

        if (current.runnerOnFirst() && clean(current.firstBaseRunnerName()) == null && !previous.isRunnerOnFirst()) {
            String onBaseBatter = onBaseBatter(previous, current);
            if (onBaseBatter != null && otherOccupiedBasesNamed(current, second, third)) {
                resolvedFirst = new Runner(onBaseBatter, null);
                reason = appendReason(reason, "onBaseBatterToFirst");
            }
        }

        boolean hasOfficialNames = clean(current.firstBaseRunnerName()) != null
                || clean(current.secondBaseRunnerName()) != null
                || clean(current.thirdBaseRunnerName()) != null;
        if (!hasOfficialNames) {
            Advancement advancement = simpleAdvancement(previous, current);
            if (advancement == Advancement.FIRST_TO_SECOND) {
                resolvedSecond = new Runner(clean(previous.getFirstBaseRunnerName()), clean(previous.getFirstBaseRunnerId()));
                reason = appendReason(reason, "advanceFirstToSecond");
            } else if (advancement == Advancement.SECOND_TO_THIRD) {
                resolvedThird = new Runner(clean(previous.getSecondBaseRunnerName()), clean(previous.getSecondBaseRunnerId()));
                reason = appendReason(reason, "advanceSecondToThird");
            } else if (advancement == Advancement.AMBIGUOUS) {
                ambiguous = true;
            }
        }

        return new Inference(resolvedFirst, resolvedSecond, resolvedThird, ambiguous, reason);
    }

    private String onBaseBatter(GameSnapshot previous, ParsedGameDetail current) {
        if (baseCount(current) <= baseCount(previous)) {
            return null;
        }
        if (nullSafe(current.outs()) > nullSafe(previous.getOuts())) {
            return null;
        }
        return clean(previous.getCurrentBatterName());
    }

    private boolean otherOccupiedBasesNamed(ParsedGameDetail current, Runner second, Runner third) {
        return (!current.runnerOnSecond() || second.name() != null)
                && (!current.runnerOnThird() || third.name() != null);
    }

    private Advancement simpleAdvancement(GameSnapshot previous, ParsedGameDetail current) {
        int previousCount = baseCount(previous);
        int currentCount = baseCount(current);
        if (previousCount != 1 || currentCount != 1) {
            return movementChanged(previous, current) ? Advancement.AMBIGUOUS : Advancement.NONE;
        }
        if (previous.isRunnerOnFirst() && !previous.isRunnerOnSecond() && !previous.isRunnerOnThird()
                && !current.runnerOnFirst() && current.runnerOnSecond() && !current.runnerOnThird()) {
            return clean(previous.getFirstBaseRunnerName()) == null ? Advancement.NONE : Advancement.FIRST_TO_SECOND;
        }
        if (!previous.isRunnerOnFirst() && previous.isRunnerOnSecond() && !previous.isRunnerOnThird()
                && !current.runnerOnFirst() && !current.runnerOnSecond() && current.runnerOnThird()) {
            return clean(previous.getSecondBaseRunnerName()) == null ? Advancement.NONE : Advancement.SECOND_TO_THIRD;
        }
        return movementChanged(previous, current) ? Advancement.AMBIGUOUS : Advancement.NONE;
    }

    private boolean movementChanged(GameSnapshot previous, ParsedGameDetail current) {
        return previous.isRunnerOnFirst() != current.runnerOnFirst()
                || previous.isRunnerOnSecond() != current.runnerOnSecond()
                || previous.isRunnerOnThird() != current.runnerOnThird();
    }

    private boolean hasCarriedForward(
            GameSnapshot previous,
            ParsedGameDetail current,
            Runner first,
            Runner second,
            Runner third
    ) {
        if (previous == null) {
            return false;
        }
        return carried(current.runnerOnFirst(), current.firstBaseRunnerName(), previous.isRunnerOnFirst(), previous.getFirstBaseRunnerName(), first.name())
                || carried(current.runnerOnSecond(), current.secondBaseRunnerName(), previous.isRunnerOnSecond(), previous.getSecondBaseRunnerName(), second.name())
                || carried(current.runnerOnThird(), current.thirdBaseRunnerName(), previous.isRunnerOnThird(), previous.getThirdBaseRunnerName(), third.name());
    }

    private boolean carried(boolean occupied, String officialName, boolean previouslyOccupied, String previousName, String resolvedName) {
        return occupied
                && clean(officialName) == null
                && previouslyOccupied
                && clean(previousName) != null
                && clean(previousName).equals(clean(resolvedName));
    }

    private int baseCount(GameSnapshot snapshot) {
        return (snapshot.isRunnerOnFirst() ? 1 : 0)
                + (snapshot.isRunnerOnSecond() ? 1 : 0)
                + (snapshot.isRunnerOnThird() ? 1 : 0);
    }

    private int baseCount(ParsedGameDetail detail) {
        return (detail.runnerOnFirst() ? 1 : 0)
                + (detail.runnerOnSecond() ? 1 : 0)
                + (detail.runnerOnThird() ? 1 : 0);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String appendReason(String existing, String reason) {
        return existing == null ? reason : existing + "," + reason;
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
            String thirdBaseRunnerId
    ) {
    }

    private record Runner(String name, String id) {
        static Runner empty() {
            return new Runner(null, null);
        }
    }

    private record Inference(Runner first, Runner second, Runner third, boolean ambiguous, String reason) {
    }

    private enum Advancement {
        NONE,
        FIRST_TO_SECOND,
        SECOND_TO_THIRD,
        AMBIGUOUS
    }
}
