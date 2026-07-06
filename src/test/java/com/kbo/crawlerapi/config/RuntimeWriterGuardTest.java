package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeWriterGuardTest {

    @Test
    void allowsDisabledSyncWithReaderRoleAndLocalDevelopmentSchema() {
        assertThatCode(() -> guard(false, environment("local", "kbo_crawler_api_dev", "reader"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEnabledSyncWithReaderRole() {
        assertThatThrownBy(() -> guard(true, environment("local", "kbo_crawler_api_dev", "reader"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RUNTIME_ROLE=writer");
    }

    @Test
    void rejectsProductionEnabledSyncWithReaderRole() {
        assertThatThrownBy(() -> guard(true, environment("production", "kbo_crawler_api", "reader"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RUNTIME_ROLE=writer");
    }

    @Test
    void rejectsEnabledSyncWithUnsetRole() {
        assertThatThrownBy(() -> guard(true, environment("local", "kbo_crawler_api_dev", null))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RUNTIME_ROLE=writer");
    }

    @Test
    void allowsDevelopmentSchemaWithWriterRoleAndEnabledSync() {
        assertThatCode(() -> guard(true, environment("development", "kbo_crawler_api_dev", "writer"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsLocalDevelopmentSchemaWithWriterRoleAndEnabledSync() {
        assertThatCode(() -> guard(true, environment("local", "kbo_crawler_api_dev", "writer"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTestDevelopmentSchemaWithReaderRoleAndDisabledSync() {
        assertThatCode(() -> guard(false, environment("test", "kbo_crawler_api_dev", "reader"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTestDevelopmentSchemaWithWriterRoleAndEnabledSync() {
        assertThatCode(() -> guard(true, environment("test", "kbo_crawler_api_dev", "writer"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsProductionSchemaWithWriterRoleAndEnabledSync() {
        assertThatCode(() -> guard(true, environment("production", "kbo_crawler_api", "writer"))
                .validateRuntimeWriterConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionDevelopmentSchemaWithWriterRole() {
        assertThatThrownBy(() -> guard(true, environment("production", "kbo_crawler_api_dev", "writer"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api_dev");
    }

    @Test
    void rejectsLocalProductionSchemaWithWriterRole() {
        assertThatThrownBy(() -> guard(true, environment("local", "kbo_crawler_api", "writer"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api");
    }

    @Test
    void rejectsDevelopmentProductionSchemaWithWriterRole() {
        assertThatThrownBy(() -> guard(true, environment("development", "kbo_crawler_api", "writer"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api");
    }

    @Test
    void rejectsTestProductionSchemaWithWriterRole() {
        assertThatThrownBy(() -> guard(true, environment("test", "kbo_crawler_api", "writer"))
                .validateRuntimeWriterConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api");
    }

    private RuntimeWriterGuard guard(boolean syncEnabled, MockEnvironment environment) {
        SyncProperties syncProperties = new SyncProperties();
        syncProperties.setEnabled(syncEnabled);
        return new RuntimeWriterGuard(syncProperties, environment);
    }

    private MockEnvironment environment(String profile, String schema, String runtimeRole) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.db.schema", schema)
                .withProperty("spring.datasource.hikari.schema", schema)
                .withProperty("spring.jpa.properties.hibernate.default_schema", schema)
                .withProperty("spring.flyway.default-schema", schema)
                .withProperty("spring.flyway.schemas", schema)
                .withProperty("spring.flyway.placeholders.appSchema", schema)
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres?sslmode=require&currentSchema=" + schema);
        environment.setActiveProfiles(profile);
        if (runtimeRole != null) {
            environment.setProperty("app.runtime.role", runtimeRole);
        }
        return environment;
    }
}
