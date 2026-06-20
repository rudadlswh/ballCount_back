package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefreshSchedulerExclusivityGuardTest {

    @Test
    void rejectsWhenLiveSyncAndDetailRefreshSchedulersAreBothEnabled() {
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();
        liveSyncProperties.setEnabled(true);
        SchedulerShellProperties schedulerShellProperties = new SchedulerShellProperties();
        schedulerShellProperties.setEnabled(true);

        RefreshSchedulerExclusivityGuard guard = new RefreshSchedulerExclusivityGuard(liveSyncProperties, schedulerShellProperties);

        assertThatThrownBy(guard::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disable either app.live-sync.enabled or app.scheduler.enabled");
    }

    @Test
    void allowsOnlyLiveSyncSchedulerEnabled() {
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();
        liveSyncProperties.setEnabled(true);
        SchedulerShellProperties schedulerShellProperties = new SchedulerShellProperties();
        schedulerShellProperties.setEnabled(false);

        RefreshSchedulerExclusivityGuard guard = new RefreshSchedulerExclusivityGuard(liveSyncProperties, schedulerShellProperties);

        assertThatCode(guard::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void allowsOnlyDetailRefreshSchedulerEnabled() {
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();
        liveSyncProperties.setEnabled(false);
        SchedulerShellProperties schedulerShellProperties = new SchedulerShellProperties();
        schedulerShellProperties.setEnabled(true);

        RefreshSchedulerExclusivityGuard guard = new RefreshSchedulerExclusivityGuard(liveSyncProperties, schedulerShellProperties);

        assertThatCode(guard::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void allowsBothSchedulersDisabled() {
        RefreshSchedulerExclusivityGuard guard = new RefreshSchedulerExclusivityGuard(
                new LiveSyncProperties(),
                new SchedulerShellProperties()
        );

        assertThatCode(guard::validateConfiguration).doesNotThrowAnyException();
    }
}
