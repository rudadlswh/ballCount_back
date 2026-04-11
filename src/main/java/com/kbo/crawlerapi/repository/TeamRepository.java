package com.kbo.crawlerapi.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.Team;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByTeamCode(String teamCode);
}
