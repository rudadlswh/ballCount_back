package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveGameSyncSchedulerTest {

    @Test
    void skipsWhenLiveSyncIsDisabled() {
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setEnabled(false);
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = new LiveGameSyncScheduler(service, properties, gameRepository());

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isZero();
    }

    @Test
    void runsWhenLiveSyncIsEnabled() {
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setEnabled(true);
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = new LiveGameSyncScheduler(service, properties, gameRepository());

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    private static final class RecordingLiveGameSyncService extends LiveGameSyncService {

        private final AtomicInteger invocationCount = new AtomicInteger();

        private RecordingLiveGameSyncService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public LiveSyncSummary syncToday() {
            invocationCount.incrementAndGet();
            return null;
        }
    }

    private static GameRepository gameRepository() {
        GameRepository repository = mock(GameRepository.class);
        when(repository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(any(LocalDate.class))).thenReturn(List.of());
        return repository;
    }
}
