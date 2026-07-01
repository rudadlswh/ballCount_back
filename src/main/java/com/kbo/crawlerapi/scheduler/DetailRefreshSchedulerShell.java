package com.kbo.crawlerapi.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kbo.crawlerapi.config.SchedulerShellProperties;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.service.CrawlJobTrackingService;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.DetailRefreshPassResult;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.RefreshPhase;

@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true")
public class DetailRefreshSchedulerShell {

    private static final Logger log = LoggerFactory.getLogger(DetailRefreshSchedulerShell.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DetailRefreshOrchestratorService detailRefreshOrchestratorService;
    private final CrawlJobTrackingService crawlJobTrackingService;
    private final SchedulerShellProperties schedulerShellProperties;
    private final Clock applicationClock;
    private final EnumMap<RefreshPhase, AtomicBoolean> activePhaseRuns;
    private final EnumMap<RefreshPhase, AtomicReference<Instant>> phaseCooldowns;

    public DetailRefreshSchedulerShell(
            DetailRefreshOrchestratorService detailRefreshOrchestratorService,
            CrawlJobTrackingService crawlJobTrackingService,
            SchedulerShellProperties schedulerShellProperties,
            Clock applicationClock
    ) {
        this.detailRefreshOrchestratorService = detailRefreshOrchestratorService;
        this.crawlJobTrackingService = crawlJobTrackingService;
        this.schedulerShellProperties = schedulerShellProperties;
        this.applicationClock = applicationClock;
        this.activePhaseRuns = new EnumMap<>(RefreshPhase.class);
        this.phaseCooldowns = new EnumMap<>(RefreshPhase.class);
        initializePhaseState(RefreshPhase.PREGAME);
        initializePhaseState(RefreshPhase.LIVE);
        initializePhaseState(RefreshPhase.POST_FINAL);
        log.info(
                "Detail refresh scheduler shell enabled. pregameEnabled={}, liveEnabled={}, postFinalEnabled={}, pregameInterval={}, liveInterval={}, postFinalInterval={}",
                schedulerShellProperties.isPregameEnabled(),
                schedulerShellProperties.isLiveEnabled(),
                schedulerShellProperties.isPostFinalEnabled(),
                schedulerShellProperties.getPregameInterval(),
                schedulerShellProperties.getLiveInterval(),
                schedulerShellProperties.getPostFinalInterval()
        );
    }

    @Scheduled(fixedDelayString = "${app.scheduler.pregame-interval:PT30M}")
    public void runPregamePass() {
        if (!schedulerShellProperties.isPregameEnabled()) {
            return;
        }
        runPass(RefreshPhase.PREGAME);
    }

    @Scheduled(fixedDelayString = "${app.scheduler.live-interval:PT15S}")
    public void runLivePass() {
        if (!schedulerShellProperties.isLiveEnabled()) {
            return;
        }
        runPass(RefreshPhase.LIVE);
    }

    @Scheduled(fixedDelayString = "${app.scheduler.post-final-interval:PT60S}")
    public void runPostFinalPass() {
        if (!schedulerShellProperties.isPostFinalEnabled()) {
            return;
        }
        runPass(RefreshPhase.POST_FINAL);
    }

    private void initializePhaseState(RefreshPhase phase) {
        activePhaseRuns.put(phase, new AtomicBoolean(false));
        phaseCooldowns.put(phase, new AtomicReference<>());
    }

    private void runPass(RefreshPhase phase) {
        AtomicBoolean runningGuard = activePhaseRuns.get(phase);
        if (!runningGuard.compareAndSet(false, true)) {
            log.warn("Skipping scheduler detail refresh pass because the previous same-phase run is still active. phase={}", phase.phaseName());
            return;
        }

        CrawlJob crawlJob = null;
        try {
            AtomicReference<Instant> cooldownReference = phaseCooldowns.get(phase);
            Instant now = applicationClock.instant();
            Instant cooldownUntil = cooldownReference.get();
            if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
                log.warn(
                        "Skipping scheduler detail refresh pass because the phase is in cooldown after a recent failure. phase={}, cooldownUntil={}",
                        phase.phaseName(),
                        cooldownUntil
                );
                return;
            }

            LocalDate targetDate = LocalDate.now(applicationClock.withZone(KST));
            crawlJob = crawlJobTrackingService.createRunningDetailRefreshOrchestrationJob(
                    phase.phaseName(),
                    targetDate.toString()
            );
            DetailRefreshPassResult result = detailRefreshOrchestratorService.runPass(targetDate, true, Set.of(phase));
            int succeededCount = (int) result.executionResults().stream().filter(execution -> execution.succeeded()).count();
            int failedCount = result.executionResults().size() - succeededCount;
            int executedCount = result.executionResults().size();
            int skippedCount = Math.max(0, result.totalGames() - result.selectedGameCount());

            if (failedCount > 0) {
                Instant nextAttemptAfter = now.plus(schedulerShellProperties.intervalFor(phase)).plus(schedulerShellProperties.intervalFor(phase));
                cooldownReference.set(nextAttemptAfter);
                crawlJobTrackingService.markDetailRefreshOrchestrationFailed(
                        crawlJob.getId(),
                        phase.phaseName(),
                        result.selectedGameCount(),
                        executedCount,
                        succeededCount,
                        failedCount,
                        skippedCount,
                        "execute",
                        "Detail refresh orchestration pass completed with " + failedCount + " failed child jobs.",
                        null
                );
                log.warn(
                        "Scheduler detail refresh pass recorded failures and will skip one additional interval before retrying. phase={}, nextAttemptAfter={}, failedCount={}",
                        phase.phaseName(),
                        nextAttemptAfter,
                        failedCount
                );
            } else {
                cooldownReference.set(null);
                crawlJobTrackingService.markDetailRefreshOrchestrationSucceeded(
                        crawlJob.getId(),
                        phase.phaseName(),
                        result.selectedGameCount(),
                        executedCount,
                        succeededCount,
                        failedCount,
                        skippedCount
                );
            }

            log.info(
                    "Scheduler detail refresh pass completed. phase={}, date={}, selectedGameCount={}, executedGameCount={}, succeededCount={}, failedCount={}",
                    phase.phaseName(),
                    result.date(),
                    result.selectedGameCount(),
                    result.executionResults().size(),
                    succeededCount,
                    failedCount
            );
        } catch (RuntimeException exception) {
            Duration phaseInterval = schedulerShellProperties.intervalFor(phase);
            Instant nextAttemptAfter = applicationClock.instant().plus(phaseInterval).plus(phaseInterval);
            phaseCooldowns.get(phase).set(nextAttemptAfter);
            if (crawlJob != null) {
                crawlJobTrackingService.markDetailRefreshOrchestrationFailed(
                        crawlJob.getId(),
                        phase.phaseName(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        "orchestrate",
                        exception.getMessage(),
                        exception
                );
            }
            log.error(
                    "Scheduler detail refresh pass failed and will skip one additional interval before retrying. phase={}, nextAttemptAfter={}",
                    phase.phaseName(),
                    nextAttemptAfter,
                    exception
            );
        } finally {
            runningGuard.set(false);
        }
    }
}
