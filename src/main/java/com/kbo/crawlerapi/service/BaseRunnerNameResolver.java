package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class BaseRunnerNameResolver {

    private static final Logger log = LoggerFactory.getLogger(BaseRunnerNameResolver.class);

    ResolvedBaseRunners resolve(GameSnapshot previous, ParsedGameDetail current) {
        String resetReason = resetReason(previous, current);
        GameSnapshot reusablePrevious = resetReason == null ? previous : null;
        if (previous != null && resetReason != null) {
            log.debug(
                    "[BaseRunners] carryForward reset gameId={} reason={} previous inning={}/{} outs={} bases={} current inning={}/{} outs={} bases={}",
                    current.providerGameId(),
                    resetReason,
                    previous.getInning(),
                    previous.getInningHalf(),
                    previous.getOuts(),
                    baseKey(previous),
                    current.inning(),
                    current.inningHalf(),
                    current.outs(),
                    baseKey(current)
            );
        }

        Runner first = resolveBase(
                current.runnerOnFirst(),
                current.firstBaseRunnerName(),
                current.firstBaseRunnerId(),
                reusablePrevious == null ? false : reusablePrevious.isRunnerOnFirst(),
                reusablePrevious == null ? null : reusablePrevious.getFirstBaseRunnerName(),
                reusablePrevious == null ? null : reusablePrevious.getFirstBaseRunnerId()
        );
        Runner second = resolveBase(
                current.runnerOnSecond(),
                current.secondBaseRunnerName(),
                current.secondBaseRunnerId(),
                reusablePrevious == null ? false : reusablePrevious.isRunnerOnSecond(),
                reusablePrevious == null ? null : reusablePrevious.getSecondBaseRunnerName(),
                reusablePrevious == null ? null : reusablePrevious.getSecondBaseRunnerId()
        );
        Runner third = resolveBase(
                current.runnerOnThird(),
                current.thirdBaseRunnerName(),
                current.thirdBaseRunnerId(),
                reusablePrevious == null ? false : reusablePrevious.isRunnerOnThird(),
                reusablePrevious == null ? null : reusablePrevious.getThirdBaseRunnerName(),
                reusablePrevious == null ? null : reusablePrevious.getThirdBaseRunnerId()
        );

        boolean carried = hasCarriedForward(reusablePrevious, current, first, second, third);
        if (carried) {
            log.debug(
                    "[BaseRunners] carriedForward first={} second={} third={}",
                    displayName(first.name()),
                    displayName(second.name()),
                    displayName(third.name())
            );
        }

        Inference inference = infer(reusablePrevious, current, first, second, third);
        if (inference.ambiguous()) {
            log.debug("[BaseRunners] inference skipped reason=ambiguous");
        }
        if (inference.reason() != null) {
            log.debug(
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
                inference.third().id(),
                resolutionSource(current, carried, inference)
        );
    }

    private String resolutionSource(ParsedGameDetail current, boolean carried, Inference inference) {
        List<String> sources = new ArrayList<>();
        if (clean(current.firstBaseRunnerName()) != null
                || clean(current.secondBaseRunnerName()) != null
                || clean(current.thirdBaseRunnerName()) != null
                || clean(current.firstBaseRunnerId()) != null
                || clean(current.secondBaseRunnerId()) != null
                || clean(current.thirdBaseRunnerId()) != null) {
            sources.add(hasBaseBattingOrder(current) ? "payload/order" : "payload");
        }
        if (carried) {
            sources.add("cache");
        }
        if (inference.reason() != null) {
            sources.add("event:" + inference.reason());
        }
        if (inference.ambiguous()) {
            sources.add("event:ambiguous");
        }
        return sources.isEmpty() ? "none" : String.join("+", sources);
    }

    private boolean hasBaseBattingOrder(ParsedGameDetail current) {
        return current.firstBaseBattingOrder() != null && current.firstBaseBattingOrder() > 0
                || current.secondBaseBattingOrder() != null && current.secondBaseBattingOrder() > 0
                || current.thirdBaseBattingOrder() != null && current.thirdBaseBattingOrder() > 0;
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
        boolean handledClearFirstToSecond = false;

        if (clearFirstToSecondWithBatterToFirst(previous, current)) {
            handledClearFirstToSecond = true;
            if (clean(current.firstBaseRunnerName()) == null && clean(current.firstBaseRunnerId()) == null) {
                resolvedFirst = Runner.empty();
                String onBaseBatter = onBaseBatter(previous, current);
                if (onBaseBatter != null) {
                    resolvedFirst = new Runner(onBaseBatter, null);
                    reason = appendReason(reason, "onBaseBatterToFirst");
                }
            }
            if (clean(current.secondBaseRunnerName()) == null && clean(current.secondBaseRunnerId()) == null) {
                resolvedSecond = new Runner(clean(previous.getFirstBaseRunnerName()), clean(previous.getFirstBaseRunnerId()));
                reason = appendReason(reason, "advanceFirstToSecond");
            }
        }

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
        if (!hasOfficialNames && !handledClearFirstToSecond) {
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

    private boolean clearFirstToSecondWithBatterToFirst(GameSnapshot previous, ParsedGameDetail current) {
        return previous.isRunnerOnFirst()
                && !previous.isRunnerOnSecond()
                && !previous.isRunnerOnThird()
                && current.runnerOnFirst()
                && current.runnerOnSecond()
                && !current.runnerOnThird()
                && baseCount(current) == baseCount(previous) + 1
                && nullSafe(current.outs()) <= nullSafe(previous.getOuts())
                && (clean(previous.getFirstBaseRunnerName()) != null || clean(previous.getFirstBaseRunnerId()) != null);
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

    private String resetReason(GameSnapshot previous, ParsedGameDetail current) {
        if (previous == null) {
            return null;
        }
        if (gameChanged(previous, current)) {
            return "gameChanged";
        }
        if (baseCount(current) == 0) {
            return "basesEmpty";
        }
        if (nullSafe(previous.getOuts()) >= 3 || nullSafe(current.outs()) >= 3) {
            return "sideChangedOuts";
        }
        if (previous.getInning() != null
                && current.inning() != null
                && !Objects.equals(previous.getInning(), current.inning())) {
            return "inningChanged";
        }
        String previousHalf = normalizeHalf(previous.getInningHalf());
        String currentHalf = normalizeHalf(current.inningHalf());
        if (previousHalf != null && currentHalf != null && !Objects.equals(previousHalf, currentHalf)) {
            return "inningHalfChanged";
        }
        return null;
    }

    private boolean gameChanged(GameSnapshot previous, ParsedGameDetail current) {
        if (previous.getGame() == null) {
            return false;
        }
        String previousProviderGameId = clean(previous.getGame().getProviderGameId());
        String currentProviderGameId = clean(current.providerGameId());
        return previousProviderGameId != null
                && currentProviderGameId != null
                && !Objects.equals(previousProviderGameId, currentProviderGameId);
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

    private String normalizeHalf(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        String normalized = cleaned.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("top") || normalized.equals("초")) {
            return "top";
        }
        if (normalized.startsWith("bot") || normalized.startsWith("bottom") || normalized.equals("말")) {
            return "bottom";
        }
        return normalized;
    }

    private String baseKey(GameSnapshot snapshot) {
        return "%s%s%s".formatted(snapshot.isRunnerOnFirst() ? "1" : "-", snapshot.isRunnerOnSecond() ? "2" : "-", snapshot.isRunnerOnThird() ? "3" : "-");
    }

    private String baseKey(ParsedGameDetail detail) {
        return "%s%s%s".formatted(detail.runnerOnFirst() ? "1" : "-", detail.runnerOnSecond() ? "2" : "-", detail.runnerOnThird() ? "3" : "-");
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
            String thirdBaseRunnerId,
            String source
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
