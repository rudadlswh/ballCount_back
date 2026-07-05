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
                current.firstBaseRunnerId()
        );
        Runner second = resolveBase(
                current.runnerOnSecond(),
                current.secondBaseRunnerName(),
                current.secondBaseRunnerId()
        );
        Runner third = resolveBase(
                current.runnerOnThird(),
                current.thirdBaseRunnerName(),
                current.thirdBaseRunnerId()
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
            boolean occupied,
            String officialName,
            String officialId
    ) {
        if (!occupied) {
            return Runner.empty();
        }
        String name = clean(officialName);
        String id = clean(officialId);
        if (name != null || id != null) {
            return new Runner(name, id);
        }
        return Runner.empty();
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
}
