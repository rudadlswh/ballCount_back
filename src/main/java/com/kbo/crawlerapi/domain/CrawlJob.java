package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "crawl_jobs")
public class CrawlJob {

    @Id
    private UUID id;

    @Column(name = "job_type", nullable = false, length = 50)
    private String jobType;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_key", nullable = false, length = 100)
    private String targetKey;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "skipped_row_count", nullable = false)
    private int skippedRowCount;

    @Column(name = "detail_snapshot_status", length = 30)
    private String detailSnapshotStatus;

    @Column(name = "imported_line_score_count", nullable = false)
    private int importedLineScoreCount;

    @Column(name = "orchestration_phase", length = 30)
    private String orchestrationPhase;

    @Column(name = "selected_game_count", nullable = false)
    private int selectedGameCount;

    @Column(name = "executed_game_count", nullable = false)
    private int executedGameCount;

    @Column(name = "succeeded_count", nullable = false)
    private int succeededCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "skipped_game_count", nullable = false)
    private int skippedGameCount;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CrawlJob() {
    }

    public CrawlJob(UUID id, String jobType, String targetType, String targetKey, String status, OffsetDateTime scheduledAt, OffsetDateTime startedAt) {
        this.id = id;
        this.jobType = jobType;
        this.targetType = targetType;
        this.targetKey = targetKey;
        this.status = status;
        this.scheduledAt = scheduledAt;
        this.startedAt = startedAt;
        this.retryCount = 0;
        this.skippedRowCount = 0;
        this.selectedGameCount = 0;
        this.executedGameCount = 0;
        this.succeededCount = 0;
        this.failedCount = 0;
        this.skippedGameCount = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getTargetType() {
        return targetType;
    }

    public void assignOrchestrationPhase(String orchestrationPhase) {
        this.orchestrationPhase = orchestrationPhase;
    }

    public void markScheduleSucceeded(int skippedRowCount) {
        this.status = "succeeded";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = skippedRowCount;
        this.detailSnapshotStatus = null;
        this.importedLineScoreCount = 0;
        clearOrchestrationFields();
        this.lastErrorMessage = skippedRowCount > 0 ? "Completed with skipped rows: " + skippedRowCount : null;
    }

    public void markDetailSucceeded(String detailSnapshotStatus, int importedLineScoreCount) {
        this.status = "succeeded";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = 0;
        this.detailSnapshotStatus = detailSnapshotStatus;
        this.importedLineScoreCount = importedLineScoreCount;
        clearOrchestrationFields();
        this.lastErrorMessage = null;
    }

    public void markDetailPartialSuccess(String detailSnapshotStatus, int importedLineScoreCount, String message) {
        this.status = "partial_success";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = 0;
        this.detailSnapshotStatus = detailSnapshotStatus;
        this.importedLineScoreCount = importedLineScoreCount;
        clearOrchestrationFields();
        this.lastErrorMessage = message;
    }

    public void markFailed(String errorMessage, int skippedRowCount) {
        this.status = "failed";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = skippedRowCount;
        this.detailSnapshotStatus = null;
        this.importedLineScoreCount = 0;
        clearOrchestrationFields();
        this.lastErrorMessage = errorMessage;
    }

    public void markOrchestrationSucceeded(
            String orchestrationPhase,
            int selectedGameCount,
            int executedGameCount,
            int succeededCount,
            int failedCount,
            int skippedGameCount
    ) {
        this.status = "succeeded";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = 0;
        this.detailSnapshotStatus = null;
        this.importedLineScoreCount = 0;
        this.orchestrationPhase = orchestrationPhase;
        this.selectedGameCount = selectedGameCount;
        this.executedGameCount = executedGameCount;
        this.succeededCount = succeededCount;
        this.failedCount = failedCount;
        this.skippedGameCount = skippedGameCount;
        this.lastErrorMessage = null;
    }

    public void markOrchestrationFailed(
            String orchestrationPhase,
            int selectedGameCount,
            int executedGameCount,
            int succeededCount,
            int failedCount,
            int skippedGameCount,
            String errorMessage
    ) {
        this.status = "failed";
        this.finishedAt = OffsetDateTime.now();
        this.skippedRowCount = 0;
        this.detailSnapshotStatus = null;
        this.importedLineScoreCount = 0;
        this.orchestrationPhase = orchestrationPhase;
        this.selectedGameCount = selectedGameCount;
        this.executedGameCount = executedGameCount;
        this.succeededCount = succeededCount;
        this.failedCount = failedCount;
        this.skippedGameCount = skippedGameCount;
        this.lastErrorMessage = errorMessage;
    }

    private void clearOrchestrationFields() {
        this.orchestrationPhase = null;
        this.selectedGameCount = 0;
        this.executedGameCount = 0;
        this.succeededCount = 0;
        this.failedCount = 0;
        this.skippedGameCount = 0;
    }
}
