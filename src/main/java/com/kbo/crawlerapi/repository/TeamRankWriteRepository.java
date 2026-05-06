package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.service.TeamRankRow;
import java.util.List;

public interface TeamRankWriteRepository {

    int upsertAll(List<TeamRankRow> rows);
}
