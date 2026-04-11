package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SchedulerProfileGuardTest {

    @Test
    void rejectsEnabledSchedulerWithoutKnownEnvironmentProfile() {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);

        SchedulerProfileGuard guard = new SchedulerProfileGuard(properties, new MockEnvironment());

        assertThatThrownBy(guard::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an explicit Spring profile");
    }

    @Test
    void rejectsLiveRefreshUnderLocalProfile() {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);
        properties.setLiveEnabled(true);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SchedulerProfileGuard guard = new SchedulerProfileGuard(properties, environment);

        assertThatThrownBy(guard::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed with the local profile");
    }

    @Test
    void allowsPregameOnlySchedulerUnderLocalProfile() {
        SchedulerShellProperties properties = new SchedulerShellProperties();
        properties.setEnabled(true);
        properties.setLiveEnabled(false);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        SchedulerProfileGuard guard = new SchedulerProfileGuard(properties, environment);

        assertThatCode(guard::validateConfiguration).doesNotThrowAnyException();
    }
}
