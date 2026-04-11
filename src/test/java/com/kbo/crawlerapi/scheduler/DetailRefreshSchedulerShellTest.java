package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.kbo.crawlerapi.config.SchedulerShellProperties;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.service.CrawlJobTrackingService;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.DetailRefreshPassResult;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.GameRefreshExecutionResult;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.RefreshPhase;

class DetailRefreshSchedulerShellTest {

    @Test
    void skipsOverlappingSamePhaseExecution() throws Exception {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);
        properties.setPregameEnabled(true);
        MutableClock clock = new MutableClock(Instant.parse("2026-04-09T03:00:00Z"));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingOrchestrator orchestrator = new RecordingOrchestrator(started, release, successResult(), null);
        RecordingTrackingService trackingService = new RecordingTrackingService();
        DetailRefreshSchedulerShell shell = new DetailRefreshSchedulerShell(orchestrator, trackingService, properties, clock);

        CompletableFuture<Void> firstRun = CompletableFuture.runAsync(shell::runPregamePass);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        shell.runPregamePass();
        assertThat(orchestrator.invocationCount()).isEqualTo(1);

        release.countDown();
        firstRun.get(2, TimeUnit.SECONDS);
        assertThat(orchestrator.invocationCount()).isEqualTo(1);
    }

    @Test
    void appliesOneExtraIntervalCooldownAfterFailureResult() {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);
        properties.setPregameEnabled(true);
        properties.setPregameInterval(Duration.ofSeconds(30));
        MutableClock clock = new MutableClock(Instant.parse("2026-04-09T03:00:00Z"));

        RecordingOrchestrator orchestrator = new RecordingOrchestrator(
                null,
                null,
                successResult(),
                List.of(failureResult(), successResult())
        );
        RecordingTrackingService trackingService = new RecordingTrackingService();
        DetailRefreshSchedulerShell shell = new DetailRefreshSchedulerShell(orchestrator, trackingService, properties, clock);

        shell.runPregamePass();
        shell.runPregamePass();
        assertThat(orchestrator.invocationCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        shell.runPregamePass();
        assertThat(orchestrator.invocationCount()).isEqualTo(2);
    }

    @Test
    void doesNothingWhenPhaseIsDisabled() {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);
        properties.setPregameEnabled(false);

        RecordingOrchestrator orchestrator = new RecordingOrchestrator(null, null, successResult(), null);
        RecordingTrackingService trackingService = new RecordingTrackingService();
        DetailRefreshSchedulerShell shell = new DetailRefreshSchedulerShell(
                orchestrator,
                trackingService,
                properties,
                Clock.systemUTC()
        );

        shell.runPregamePass();

        assertThat(orchestrator.invocationCount()).isZero();
    }

    private static DetailRefreshPassResult successResult() {
        return new DetailRefreshPassResult(
                LocalDate.of(2026, 4, 9),
                true,
                1,
                1,
                List.of(),
                List.of(new GameRefreshExecutionResult("20260409-DOO-KIW", true, null, false, false, 0))
        );
    }

    private static DetailRefreshPassResult failureResult() {
        return new DetailRefreshPassResult(
                LocalDate.of(2026, 4, 9),
                true,
                1,
                1,
                List.of(),
                List.of(new GameRefreshExecutionResult("20260409-DOO-KIW", false, "boom", null, null, null))
        );
    }

    private static final class RecordingOrchestrator extends DetailRefreshOrchestratorService {

        private final CountDownLatch started;
        private final CountDownLatch release;
        private final DetailRefreshPassResult fallbackResult;
        private final List<DetailRefreshPassResult> scriptedResults;
        private final AtomicInteger invocationCount = new AtomicInteger();

        private RecordingOrchestrator(
                CountDownLatch started,
                CountDownLatch release,
                DetailRefreshPassResult fallbackResult,
                List<DetailRefreshPassResult> scriptedResults
        ) {
            super(null, null, null, null, Clock.systemUTC(), new SchedulerShellProperties());
            this.started = started;
            this.release = release;
            this.fallbackResult = fallbackResult;
            this.scriptedResults = scriptedResults;
        }

        @Override
        public DetailRefreshPassResult runPass(LocalDate date, boolean execute, Set<RefreshPhase> allowedPhases) {
            int invocationIndex = invocationCount.getAndIncrement();
            if (started != null) {
                started.countDown();
            }
            if (release != null) {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            if (scriptedResults != null && invocationIndex < scriptedResults.size()) {
                return scriptedResults.get(invocationIndex);
            }
            return fallbackResult;
        }

        private int invocationCount() {
            return invocationCount.get();
        }
    }

    private static final class RecordingTrackingService extends CrawlJobTrackingService {

        private RecordingTrackingService() {
            super(null, null);
        }

        @Override
        public CrawlJob createRunningDetailRefreshOrchestrationJob(String phase, String targetKey) {
            CrawlJob crawlJob = new CrawlJob(
                    java.util.UUID.randomUUID(),
                    "detail-refresh-orchestration-pass",
                    "date",
                    targetKey,
                    "running",
                    OffsetDateTime.now(),
                    OffsetDateTime.now()
            );
            crawlJob.assignOrchestrationPhase(phase);
            return crawlJob;
        }

        @Override
        public void markDetailRefreshOrchestrationSucceeded(
                java.util.UUID crawlJobId,
                String phase,
                int selectedGameCount,
                int executedGameCount,
                int succeededCount,
                int failedCount,
                int skippedGameCount
        ) {
        }

        @Override
        public void markDetailRefreshOrchestrationFailed(
                java.util.UUID crawlJobId,
                String phase,
                int selectedGameCount,
                int executedGameCount,
                int succeededCount,
                int failedCount,
                int skippedGameCount,
                String failureStage,
                String errorMessage,
                Throwable throwable
        ) {
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
