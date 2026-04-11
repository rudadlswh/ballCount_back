package com.kbo.crawlerapi.config;

import java.util.Arrays;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true")
public class SchedulerProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(SchedulerProfileGuard.class);

    private final SchedulerShellProperties schedulerShellProperties;
    private final Environment environment;

    public SchedulerProfileGuard(SchedulerShellProperties schedulerShellProperties, Environment environment) {
        this.schedulerShellProperties = schedulerShellProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void validateConfiguration() {
        SchedulerEnvironment schedulerEnvironment = SchedulerEnvironment.from(environment.getActiveProfiles());

        if (schedulerEnvironment == SchedulerEnvironment.UNKNOWN) {
            throw new IllegalStateException(
                    "Scheduler startup requires an explicit Spring profile when app.scheduler.enabled=true. "
                            + "Use one of: local, dev, staging, production."
            );
        }

        if (schedulerEnvironment == SchedulerEnvironment.LOCAL && schedulerShellProperties.isLiveEnabled()) {
            throw new IllegalStateException(
                    "Live scheduler refresh is not allowed with the local profile. "
                            + "Keep app.scheduler.live-enabled=false or switch to dev/staging/production."
            );
        }

        if (!schedulerShellProperties.isPregameEnabled()
                && !schedulerShellProperties.isLiveEnabled()
                && !schedulerShellProperties.isPostFinalEnabled()) {
            log.warn("Scheduler shell is enabled, but all scheduler phases are disabled. No automatic refreshes will run.");
        }

        log.info(
                "Scheduler profile policy active. environment={}, activeProfiles={}, pregameEnabled={}, liveEnabled={}, postFinalEnabled={}",
                schedulerEnvironment.name().toLowerCase(),
                Arrays.toString(environment.getActiveProfiles()),
                schedulerShellProperties.isPregameEnabled(),
                schedulerShellProperties.isLiveEnabled(),
                schedulerShellProperties.isPostFinalEnabled()
        );
    }

    enum SchedulerEnvironment {
        LOCAL(Set.of("local")),
        DEV(Set.of("dev", "development")),
        STAGING(Set.of("staging", "stage")),
        PRODUCTION(Set.of("production", "prod")),
        UNKNOWN(Set.of());

        private final Set<String> profileAliases;

        SchedulerEnvironment(Set<String> profileAliases) {
            this.profileAliases = profileAliases;
        }

        static SchedulerEnvironment from(String[] activeProfiles) {
            Set<String> activeProfileSet = Arrays.stream(activeProfiles)
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toSet());

            SchedulerEnvironment matched = UNKNOWN;
            for (SchedulerEnvironment candidate : values()) {
                if (candidate == UNKNOWN) {
                    continue;
                }
                boolean profileMatched = candidate.profileAliases.stream().anyMatch(activeProfileSet::contains);
                if (!profileMatched) {
                    continue;
                }
                if (matched != UNKNOWN) {
                    throw new IllegalStateException(
                            "Scheduler startup found multiple environment profile groups: "
                                    + Arrays.toString(activeProfiles)
                    );
                }
                matched = candidate;
            }
            return matched;
        }
    }
}
