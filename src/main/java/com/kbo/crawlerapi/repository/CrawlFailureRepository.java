package com.kbo.crawlerapi.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.CrawlFailure;

public interface CrawlFailureRepository extends JpaRepository<CrawlFailure, UUID> {
}
