package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeWriterGuard {

    private static final Logger log = LoggerFactory.getLogger(RuntimeWriterGuard.class);
    private static final String WRITER_ROLE = "writer";

    private final SchedulerShellProperties schedulerShellProperties;
    private final LiveSyncProperties liveSyncProperties;
    private final Environment environment;

    public RuntimeWriterGuard(
            SchedulerShellProperties schedulerShellProperties,
            LiveSyncProperties liveSyncProperties,
            Environment environment
    ) {
        this.schedulerShellProperties = schedulerShellProperties;
        this.liveSyncProperties = liveSyncProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void validateRuntimeWriterConfiguration() {
        boolean schedulerEnabled = schedulerShellProperties.isEnabled();
        boolean liveSyncEnabled = liveSyncProperties.isEnabled();
        boolean detailRefreshEnabled = schedulerEnabled
                && (schedulerShellProperties.isPregameEnabled()
                || schedulerShellProperties.isLiveEnabled()
                || schedulerShellProperties.isPostFinalEnabled());
        if (!schedulerEnabled && !liveSyncEnabled && !detailRefreshEnabled) {
            return;
        }
        if (!isProductionLikeDatasource()) {
            return;
        }

        String[] activeProfiles = environment.getActiveProfiles();
        String runtimeRole = runtimeRole();
        boolean productionProfile = hasProductionProfile(activeProfiles);
        boolean writerRole = WRITER_ROLE.equals(runtimeRole);
        if (productionProfile && writerRole) {
            return;
        }

        log.error(
                "DB write runtime misconfigured: profiles={} runtimeRole={} schedulerEnabled={} liveSyncEnabled={} detailRefreshEnabled={}",
                Arrays.toString(activeProfiles),
                runtimeRole,
                schedulerEnabled,
                liveSyncEnabled,
                detailRefreshEnabled
        );
        throw new IllegalStateException(
                "Production-like DB write runtime requires production profile and APP_RUNTIME_ROLE=writer when scheduler or live sync is enabled."
        );
    }

    private boolean isProductionLikeDatasource() {
        if (hasProductionProfile(environment.getActiveProfiles())) {
            return true;
        }
        String datasourceUrl = firstText(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("SPRING_DATASOURCE_URL")
        );
        if (datasourceUrl == null) {
            return false;
        }
        String normalized = datasourceUrl.toLowerCase(Locale.ROOT);
        return normalized.contains("supabase.co") || normalized.contains("supabase.com");
    }

    private boolean hasProductionProfile(String[] activeProfiles) {
        return Arrays.stream(activeProfiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "production".equals(profile) || "prod".equals(profile));
    }

    private String runtimeRole() {
        String role = firstText(
                environment.getProperty("app.runtime.role"),
                environment.getProperty("APP_RUNTIME_ROLE")
        );
        return role == null ? "unset" : role.toLowerCase(Locale.ROOT);
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
}
