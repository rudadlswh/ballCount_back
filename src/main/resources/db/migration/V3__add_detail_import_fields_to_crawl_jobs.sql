ALTER TABLE crawl_jobs
ADD COLUMN detail_snapshot_status VARCHAR(30),
ADD COLUMN imported_line_score_count INTEGER NOT NULL DEFAULT 0;
