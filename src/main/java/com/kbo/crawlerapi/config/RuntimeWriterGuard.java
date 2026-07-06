package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeWriterGuard {

    private static final Logger log = LoggerFactory.getLogger(RuntimeWriterGuard.class);
    private static final String WRITER_ROLE = "writer";
    private static final String READER_ROLE = "reader";
    private static final String PRODUCTION_SCHEMA = "kbo_crawler_api";
    private static final String DEVELOPMENT_SCHEMA = "kbo_crawler_api_dev";
    private static final Set<String> PRODUCTION_PROFILES = Set.of("production", "prod");
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("local", "development", "dev", "test");

    private final SyncProperties syncProperties;
    private final Environment environment;

    public RuntimeWriterGuard(
            SyncProperties syncProperties,
            Environment environment
    ) {
        this.syncProperties = syncProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void validateRuntimeWriterConfiguration() {
        boolean syncEnabled = syncProperties.isEnabled();
        String[] activeProfiles = environment.getActiveProfiles();
        Set<String> profiles = normalizedProfiles(activeProfiles);
        Set<String> schemas = configuredSchemas();
        String runtimeRole = runtimeRole();
        boolean productionProfile = intersects(profiles, PRODUCTION_PROFILES);
        boolean developmentProfile = profiles.isEmpty() || intersects(profiles, DEVELOPMENT_PROFILES);
        boolean writerRole = WRITER_ROLE.equals(runtimeRole);
        boolean writeRuntimeEnabled = syncEnabled;

        if (productionProfile && schemas.contains(DEVELOPMENT_SCHEMA)) {
            reject(activeProfiles, runtimeRole, schemas, syncEnabled,
                    "Production runtime must not use DB schema kbo_crawler_api_dev.");
        }
        if (developmentProfile && schemas.contains(PRODUCTION_SCHEMA)) {
            reject(activeProfiles, runtimeRole, schemas, syncEnabled,
                    "Local/development/test runtime must not use DB schema kbo_crawler_api.");
        }

        if (!writeRuntimeEnabled) {
            return;
        }

        if (productionProfile && writerRole && schemas.contains(PRODUCTION_SCHEMA)) {
            return;
        }
        if (developmentProfile && writerRole && schemas.contains(DEVELOPMENT_SCHEMA)) {
            return;
        }

        String expected = productionProfile
                ? "Production write runtime requires DB schema kbo_crawler_api and APP_RUNTIME_ROLE=writer."
                : "Local/development/test write runtime requires DB schema kbo_crawler_api_dev and APP_RUNTIME_ROLE=writer.";
        reject(activeProfiles, runtimeRole, schemas, syncEnabled, expected);
    }

    private void reject(
            String[] activeProfiles,
            String runtimeRole,
            Set<String> schemas,
            boolean syncEnabled,
            String message
    ) {
        log.error(
                "DB write runtime misconfigured: profiles={} runtimeRole={} schemas={} syncEnabled={}",
                Arrays.toString(activeProfiles),
                runtimeRole,
                schemas,
                syncEnabled
        );
        throw new IllegalStateException(message);
    }

    private Set<String> normalizedProfiles(String[] activeProfiles) {
        Set<String> profiles = new LinkedHashSet<>();
        for (String profile : activeProfiles) {
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
        addSchemas(schemas, environment.getProperty("APP_DB_SCHEMA"));
        addSchemas(schemas, environment.getProperty("spring.datasource.hikari.schema"));
        addSchemas(schemas, environment.getProperty("spring.jpa.properties.hibernate.default_schema"));
        addSchemas(schemas, environment.getProperty("spring.flyway.default-schema"));
        addSchemas(schemas, environment.getProperty("spring.flyway.schemas"));
        addSchemas(schemas, environment.getProperty("spring.flyway.placeholders.appSchema"));
        addSchemas(schemas, currentSchema(environment.getProperty("spring.datasource.url")));
        addSchemas(schemas, currentSchema(environment.getProperty("SPRING_DATASOURCE_URL")));
        return schemas;
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
            if ("currentschema".equals(key.toLowerCase(Locale.ROOT))) {
                return decode(pair.substring(separator + 1));
            }
        }
        return null;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    private String runtimeRole() {
        String role = firstText(
                environment.getProperty("app.runtime.role"),
                environment.getProperty("APP_RUNTIME_ROLE")
        );
        return role == null ? READER_ROLE : role.toLowerCase(Locale.ROOT);
    }

    private String firstText(String first, String second) {
        String normalizedFirst = normalizeText(first);
        return normalizedFirst == null ? normalizeText(second) : normalizedFirst;
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalize(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
