package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "crawl_failures")
public class CrawlFailure {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_job_id")
    private CrawlJob crawlJob;

    @Column(length = 30)
    private String provider;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_key", length = 100)
    private String targetKey;

    @Column(name = "failure_stage", nullable = false, length = 50)
    private String failureStage;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", nullable = false)
    private String errorMessage;

    @Column(name = "stack_trace")
    private String stackTrace;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected CrawlFailure() {
    }

    public CrawlFailure(
            UUID id,
            CrawlJob crawlJob,
            String provider,
            String targetType,
            String targetKey,
            String failureStage,
            String errorCode,
            String errorMessage,
            String stackTrace
    ) {
        this.id = id;
        this.crawlJob = crawlJob;
        this.provider = provider;
        this.targetType = targetType;
        this.targetKey = targetKey;
        this.failureStage = failureStage;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
    }
}
