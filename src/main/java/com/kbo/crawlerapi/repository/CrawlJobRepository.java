package com.kbo.crawlerapi.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.CrawlJob;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, UUID> {
}
