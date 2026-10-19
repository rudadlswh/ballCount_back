package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseSchemaGuardTest {

    @Test
    void allowsLocalProfileWithDevelopmentSchema() {
        MockEnvironment environment = developmentEnvironment("local");

        assertThatCode(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalProfileWithProductionSchema() {
        MockEnvironment environment = baseEnvironment("local", "kbo_crawler_api");

        assertThatThrownBy(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api");
    }

    @Test
    void rejectsLocalProfileWhenDatasourceUrlUsesProductionSchema() {
        MockEnvironment environment = developmentEnvironment("test")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres?currentSchema=kbo_crawler_api");

        assertThatThrownBy(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use DB schema kbo_crawler_api");
    }

    @Test
    void rejectsLocalProfileWhenDatasourceUrlOmitsCurrentSchema() {
        MockEnvironment environment = baseEnvironment("local", "kbo_crawler_api_dev")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres");

        assertThatThrownBy(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must include currentSchema=kbo_crawler_api_dev");
    }

    @Test
    void allowsProductionProfileWithProductionSchema() {
        MockEnvironment environment = baseEnvironment("production", "kbo_crawler_api");

        assertThatCode(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionProfileWithDevelopmentSchema() {
        MockEnvironment environment = developmentEnvironment("production");

        assertThatThrownBy(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production profile requires DB schema kbo_crawler_api");
    }

    @Test
    void rejectsProductionProfileWhenDatasourceUrlUsesDevelopmentSchema() {
        MockEnvironment environment = baseEnvironment("production", "kbo_crawler_api")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres?currentSchema=kbo_crawler_api_dev");

        assertThatThrownBy(() -> new DatabaseSchemaGuard(environment).validateDatabaseSchemaConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production profile must only use DB schema kbo_crawler_api");
    }

    private MockEnvironment developmentEnvironment(String profile) {
        return baseEnvironment(profile, "kbo_crawler_api_dev")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.supabase.co:5432/postgres?currentSchema=kbo_crawler_api_dev");
    }

    private MockEnvironment baseEnvironment(String profile, String schema) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.db.schema", schema)
                .withProperty("spring.datasource.hikari.schema", schema)
                .withProperty("spring.jpa.properties.hibernate.default_schema", schema)
                .withProperty("spring.flyway.default-schema", schema)
                .withProperty("spring.flyway.schemas", schema)
                .withProperty("spring.flyway.placeholders.appSchema", schema);
        environment.setActiveProfiles(profile);
        return environment;
    }
}
