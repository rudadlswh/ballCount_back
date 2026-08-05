package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.api.dto.TeamStandingDto;
import java.util.List;

public interface TeamRankReadRepository {

    List<TeamStandingDto> findBySeason(int season);
}
