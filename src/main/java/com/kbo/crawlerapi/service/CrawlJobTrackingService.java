package com.kbo.crawlerapi.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kbo.crawlerapi.domain.CrawlFailure;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.repository.CrawlFailureRepository;
import com.kbo.crawlerapi.repository.CrawlJobRepository;

@Service
public class CrawlJobTrackingService {

    private final CrawlJobRepository crawlJobRepository;
    private final CrawlFailureRepository crawlFailureRepository;

    public CrawlJobTrackingService(CrawlJobRepository crawlJobRepository, CrawlFailureRepository crawlFailureRepository) {
        this.crawlJobRepository = crawlJobRepository;
        this.crawlFailureRepository = crawlFailureRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CrawlJob createRunningScheduleImportJob(String targetKey) {
        return createRunningJob("monthly-schedule-import", "month", targetKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CrawlJob createRunningDailyScheduleImportJob(String targetKey) {
        return createRunningJob("daily-schedule-import", "date", targetKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CrawlJob createRunningGameDetailImportJob(String targetKey) {
        return createRunningJob("game-detail-import", "game", targetKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CrawlJob createRunningDetailRefreshOrchestrationJob(String phase, String targetKey) {
        CrawlJob crawlJob = createRunningJob("detail-refresh-orchestration-pass", "date", targetKey);
        crawlJob.assignOrchestrationPhase(phase);
        return crawlJobRepository.save(crawlJob);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markScheduleSucceeded(UUID crawlJobId, int skippedRowCount) {
        CrawlJob crawlJob = crawlJobRepository.findById(crawlJobId)
                .orElseThrow(() -> new IllegalStateException("Crawl job not found: " + crawlJobId));
        crawlJob.markScheduleSucceeded(skippedRowCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markGameDetailSucceeded(UUID crawlJobId, boolean snapshotCreated, int importedLineScoreCount) {
        CrawlJob crawlJob = crawlJobRepository.findById(crawlJobId)
                .orElseThrow(() -> new IllegalStateException("Crawl job not found: " + crawlJobId));
        crawlJob.markDetailSucceeded(snapshotCreated ? "created" : "unchanged", importedLineScoreCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDetailRefreshOrchestrationSucceeded(
            UUID crawlJobId,
            String phase,
            int selectedGameCount,
            int executedGameCount,
            int succeededCount,
            int failedCount,
            int skippedGameCount
    ) {
        CrawlJob crawlJob = crawlJobRepository.findById(crawlJobId)
                .orElseThrow(() -> new IllegalStateException("Crawl job not found: " + crawlJobId));
        crawlJob.markOrchestrationSucceeded(
                phase,
                selectedGameCount,
                executedGameCount,
                succeededCount,
                failedCount,
                skippedGameCount
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDetailRefreshOrchestrationFailed(
            UUID crawlJobId,
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
        CrawlJob crawlJob = crawlJobRepository.findById(crawlJobId)
                .orElseThrow(() -> new IllegalStateException("Crawl job not found: " + crawlJobId));
        crawlJob.markOrchestrationFailed(
                phase,
                selectedGameCount,
                executedGameCount,
                succeededCount,
                failedCount,
                skippedGameCount,
                errorMessage
        );
        crawlFailureRepository.save(new CrawlFailure(
                UUID.randomUUID(),
                crawlJob,
                "kbo",
                crawlJob.getTargetType(),
                crawlJob.getTargetKey(),
                failureStage,
                null,
                errorMessage,
                throwable == null ? null : toStackTrace(throwable)
        ));
    }

    private CrawlJob createRunningJob(String jobType, String targetType, String targetKey) {
        CrawlJob crawlJob = new CrawlJob(
                UUID.randomUUID(),
                jobType,
                targetType,
                targetKey,
                "running",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        return crawlJobRepository.save(crawlJob);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID crawlJobId, String failureStage, String errorMessage, Throwable throwable, int skippedRowCount) {
        CrawlJob crawlJob = crawlJobRepository.findById(crawlJobId)
                .orElseThrow(() -> new IllegalStateException("Crawl job not found: " + crawlJobId));
        crawlJob.markFailed(errorMessage, skippedRowCount);
        crawlFailureRepository.save(new CrawlFailure(
                UUID.randomUUID(),
                crawlJob,
                "kbo",
                crawlJob.getTargetType(),
                crawlJob.getTargetKey(),
                failureStage,
                null,
                errorMessage,
                throwable == null ? null : toStackTrace(throwable)
        ));
    }

    private String toStackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
