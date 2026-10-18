package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.service.DataRetentionService;
import org.junit.jupiter.api.Test;

class DataRetentionSchedulerTest {
    private final DataRetentionService retention = mock(DataRetentionService.class);
    private final SyncProperties sync = new SyncProperties();

    @Test
    void disabledOrNonWriterRuntimeDoesNotDeleteAnything() {
        new DataRetentionScheduler(retention, sync, true).cleanup();
        sync.setEnabled(true);
        new DataRetentionScheduler(retention, sync, false).cleanup();
        verifyNoInteractions(retention);
    }

    @Test
    void failureAllowsTheNextScheduledRunToRetry() {
        sync.setEnabled(true);
        when(retention.cleanup()).thenThrow(new IllegalStateException("test failure"))
                .thenReturn(new DataRetentionService.CleanupResult(1, 0, 0, 0, 0, 0));
        var scheduler = new DataRetentionScheduler(retention, sync, true);
        assertThatCode(scheduler::cleanup).doesNotThrowAnyException();
        scheduler.cleanup();
        verify(retention, times(2)).cleanup();
    }
}
