package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaGuard {

    private static final String PRODUCTION_SCHEMA = "kbo_crawler_api";
    private static final String DEVELOPMENT_SCHEMA = "kbo_crawler_api_dev";
    private static final Set<String> PRODUCTION_PROFILES = Set.of("production", "prod");
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("local", "development", "dev", "test");

    private final Environment environment;

    public DatabaseSchemaGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateDatabaseSchemaConfiguration() {
        Set<String> activeProfiles = activeProfiles();
        Set<String> configuredSchemas = configuredSchemas();
        boolean productionProfile = intersects(activeProfiles, PRODUCTION_PROFILES);
        boolean developmentProfile = activeProfiles.isEmpty() || intersects(activeProfiles, DEVELOPMENT_PROFILES);

        if (productionProfile && developmentProfile) {
            throw new IllegalStateException("Database schema configuration cannot mix production and local/development/test profiles.");
        }
        if (productionProfile && !configuredSchemas.contains(PRODUCTION_SCHEMA)) {
            throw new IllegalStateException("Production profile requires DB schema kbo_crawler_api.");
        }
        if (productionProfile && configuredSchemas.stream().anyMatch(schema -> !PRODUCTION_SCHEMA.equals(schema))) {
            throw new IllegalStateException("Production profile must only use DB schema kbo_crawler_api.");
        }
        if (!productionProfile && configuredSchemas.contains(PRODUCTION_SCHEMA)) {
            throw new IllegalStateException("Non-production profiles must not use DB schema kbo_crawler_api.");
        }
        if (developmentProfile && configuredSchemas.stream().anyMatch(schema -> !DEVELOPMENT_SCHEMA.equals(schema))) {
            throw new IllegalStateException("Local/development/test profiles must use DB schema kbo_crawler_api_dev.");
        }
        String datasourceUrl = datasourceUrl();
        if (developmentProfile && datasourceUrl != null && !DEVELOPMENT_SCHEMA.equals(normalize(currentSchema(datasourceUrl)))) {
            throw new IllegalStateException("Local/development/test datasource URL must include currentSchema=kbo_crawler_api_dev.");
        }
    }

    private Set<String> activeProfiles() {
        Set<String> profiles = new LinkedHashSet<>();
        for (String profile : environment.getActiveProfiles()) {
            String normalized = normalize(profile);
            if (normalized != null) {
                profiles.add(normalized);
            }
        }
        return profiles;
    }

    private Set<String> configuredSchemas() {
        Set<String> schemas = new LinkedHashSet<>();
        addSchemas(schemas, environment.getProperty("app.db.schema"));
        addSchemas(schemas, environment.getProperty("spring.datasource.hikari.schema"));
        addSchemas(schemas, environment.getProperty("spring.jpa.properties.hibernate.default_schema"));
        addSchemas(schemas, environment.getProperty("spring.flyway.default-schema"));
        addSchemas(schemas, environment.getProperty("spring.flyway.schemas"));
        addSchemas(schemas, environment.getProperty("spring.flyway.placeholders.appSchema"));
        addSchemas(schemas, currentSchema(environment.getProperty("spring.datasource.url")));
        addSchemas(schemas, currentSchema(environment.getProperty("SPRING_DATASOURCE_URL")));
        return schemas;
    }

    private String datasourceUrl() {
        String datasourceUrl = normalizeText(environment.getProperty("spring.datasource.url"));
        return datasourceUrl == null ? normalizeText(environment.getProperty("SPRING_DATASOURCE_URL")) : datasourceUrl;
    }

    private void addSchemas(Set<String> schemas, String rawValue) {
        if (rawValue == null) {
            return;
        }
        Arrays.stream(rawValue.split(","))
                .map(this::normalize)
                .filter(schema -> schema != null && schema.startsWith("kbo_crawler_api"))
                .forEach(schemas::add);
    }

    private String currentSchema(String datasourceUrl) {
        if (datasourceUrl == null) {
            return null;
        }
        int queryStart = datasourceUrl.indexOf('?');
        if (queryStart < 0 || queryStart == datasourceUrl.length() - 1) {
            return null;
        }
        String query = datasourceUrl.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = decode(pair.substring(0, separator));
            if (!"currentschema".equals(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return decode(pair.substring(separator + 1));
        }
        return null;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
