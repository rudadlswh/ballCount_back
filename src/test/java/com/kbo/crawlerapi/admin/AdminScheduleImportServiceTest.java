package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kbo.crawlerapi.service.KboScheduleImportService;
import com.kbo.crawlerapi.service.ScheduleIngestionResult;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminScheduleImportServiceTest {

    @Test
    void importsEachMonthOnceAndAggregatesResults() {
        StubKboScheduleImportService importer = new StubKboScheduleImportService();
        AdminScheduleImportService service = new AdminScheduleImportService(importer);

        var result = service.importRange(YearMonth.of(2026, 9), YearMonth.of(2026, 10));

        assertThat(importer.requestedMonths).containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(result.requestedMonthCount()).isEqualTo(2);
        assertThat(result.succeededMonthCount()).isEqualTo(2);
        assertThat(result.failedMonthCount()).isZero();
        assertThat(result.createdCount()).isEqualTo(20);
        assertThat(result.updatedCount()).isEqualTo(4);
        assertThat(result.skippedCount()).isEqualTo(2);
    }

    @Test
    void continuesWithNextMonthWhenOneMonthFails() {
        StubKboScheduleImportService importer = new StubKboScheduleImportService();
        importer.failedMonth = YearMonth.of(2026, 9);
        AdminScheduleImportService service = new AdminScheduleImportService(importer);

        var result = service.importRange(YearMonth.of(2026, 9), YearMonth.of(2026, 10));

        assertThat(importer.requestedMonths).containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(result.succeededMonthCount()).isEqualTo(1);
        assertThat(result.failedMonthCount()).isEqualTo(1);
        assertThat(result.months().get(0).errorMessage()).isEqualTo("KBO source timeout");
        assertThat(result.months().get(1).succeeded()).isTrue();
    }

    @Test
    void rejectsReversedOrOversizedRanges() {
        AdminScheduleImportService service = new AdminScheduleImportService(new StubKboScheduleImportService());

        assertThatThrownBy(() -> service.importRange(YearMonth.of(2026, 10), YearMonth.of(2026, 9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작 월");
        assertThatThrownBy(() -> service.importRange(YearMonth.of(2026, 1), YearMonth.of(2026, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 3개월");
    }

    private static final class StubKboScheduleImportService extends KboScheduleImportService {

        private final List<YearMonth> requestedMonths = new ArrayList<>();
        private YearMonth failedMonth;

        private StubKboScheduleImportService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ScheduleIngestionResult importMonthlySchedule(YearMonth yearMonth) {
            requestedMonths.add(yearMonth);
            if (yearMonth.equals(failedMonth)) {
                throw new IllegalStateException("KBO source timeout");
            }
            return new ScheduleIngestionResult(yearMonth, 0, 0, 10, 2, 1, 0, 0, List.of());
        }
    }
}
