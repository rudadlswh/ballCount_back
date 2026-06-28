package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeWriterGuardTest {

    @Test
    void rejectsProductionLikeDatasourceWithSchedulerEnabledAndMissingRuntimeRole() {
        SchedulerShellProperties schedulerProperties = new SchedulerShellProperties();
        schedulerProperties.setEnabled(true);

        assertThatThrownBy(() -> guard(schedulerProperties, new LiveSyncProperties(), environment("production", null))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production-like DB write runtime requires production profile and APP_RUNTIME_ROLE=writer when scheduler or live sync is enabled.");
    }

    @Test
    void rejectsProductionLikeDatasourceWithLiveSyncEnabledAndMissingRuntimeRole() {
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();
        liveSyncProperties.setEnabled(true);

        assertThatThrownBy(() -> guard(new SchedulerShellProperties(), liveSyncProperties, environment("production", null))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production-like DB write runtime requires production profile and APP_RUNTIME_ROLE=writer when scheduler or live sync is enabled.");
    }

    @Test
    void rejectsProductionLikeDatasourceWithNonProductionProfileEvenWhenRuntimeRoleIsWriter() {
        SchedulerShellProperties schedulerProperties = new SchedulerShellProperties();
        schedulerProperties.setEnabled(true);

        assertThatThrownBy(() -> guard(schedulerProperties, new LiveSyncProperties(), environment("preview", "writer"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production-like DB write runtime requires production profile and APP_RUNTIME_ROLE=writer when scheduler or live sync is enabled.");
    }

    @Test
    void allowsProductionLikeDatasourceWithWriterRoleAndProductionProfile() {
        SchedulerShellProperties schedulerProperties = new SchedulerShellProperties();
        schedulerProperties.setEnabled(true);
        LiveSyncProperties liveSyncProperties = new LiveSyncProperties();

        assertThatCode(() -> guard(schedulerProperties, liveSyncProperties, environment("production", "writer"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsProductionLikeDatasourceWithoutWriteRuntime() {
        assertThatCode(() -> guard(new SchedulerShellProperties(), new LiveSyncProperties(), environment("preview", null))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    private RuntimeWriterGuard guard(
            SchedulerShellProperties schedulerProperties,
            LiveSyncProperties liveSyncProperties,
            MockEnvironment environment
    ) {
        return new RuntimeWriterGuard(schedulerProperties, liveSyncProperties, environment);
    }

    private MockEnvironment environment(String profile, String runtimeRole) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres");
        environment.setActiveProfiles(profile);
        if (runtimeRole != null) {
            environment.setProperty("app.runtime.role", runtimeRole);
        }
        return environment;
    }
}
