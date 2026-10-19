package com.kbo.crawlerapi.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdminLogBuffer extends AppenderBase<ILoggingEvent> {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile(
            "(?i)(?:publicGameId|providerGameId|gameId|game)=([^\\s,}\\]]+)"
    );
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|device[_-]?token|api[_-]?key)(\\s*[=:]\\s*)([^\\s,}&]+)"
    );
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)(authorization)(\\s*[=:]\\s*)(?:bearer\\s+)?([^\\s,}&]+)"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+)([^\\s,}&]+)"
    );

    private final AdminUiProperties properties;
    private final ArrayDeque<LogEntry> entries = new ArrayDeque<>();
    private Logger rootLogger;

    public AdminLogBuffer(AdminUiProperties properties) {
        this.properties = properties;
        setName("ADMIN_UI_MEMORY_BUFFER");
    }

    @PostConstruct
    public void register() {
        if (!(LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context)) {
            return;
        }
        setContext(context);
        start();
        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(this);
    }

    @PreDestroy
    public void unregister() {
        if (rootLogger != null) {
            rootLogger.detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        String message = abbreviate(maskSecrets(event.getFormattedMessage()), 4_000);
        String throwable = throwableSummary(event.getThrowableProxy());
        String gameId = extractGameId(message);
        LogEntry entry = new LogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel() == null ? "INFO" : event.getLevel().levelStr,
                feature(event.getLoggerName()),
                gameId,
                message,
                throwable
        );
        synchronized (entries) {
            entries.addFirst(entry);
            int capacity = Math.max(100, properties.getLogCapacity());
            while (entries.size() > capacity) {
                entries.removeLast();
            }
        }
    }

    public List<LogView> search(
            LocalDateTime from,
            LocalDateTime to,
            String level,
            String gameId,
            String feature
    ) {
        Instant fromInstant = from == null ? null : from.atZone(KST).toInstant();
        Instant toInstant = to == null ? null : to.atZone(KST).toInstant();
        String normalizedLevel = normalize(level);
        String normalizedGameId = normalize(gameId);
        String normalizedFeature = normalize(feature);
        List<LogEntry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        return snapshot.stream()
                .filter(entry -> fromInstant == null || !entry.timestamp().isBefore(fromInstant))
                .filter(entry -> toInstant == null || !entry.timestamp().isAfter(toInstant))
                .filter(entry -> normalizedLevel == null || entry.level().equalsIgnoreCase(normalizedLevel))
                .filter(entry -> normalizedGameId == null || contains(entry.gameId(), normalizedGameId)
                        || contains(entry.message(), normalizedGameId))
                .filter(entry -> normalizedFeature == null || contains(entry.feature(), normalizedFeature)
                        || contains(entry.message(), normalizedFeature))
                .limit(500)
                .map(this::toView)
                .toList();
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private LogView toView(LogEntry entry) {
        return new LogView(
                DISPLAY_FORMAT.format(entry.timestamp().atZone(KST)),
                entry.level(),
                entry.feature(),
                entry.gameId() == null ? "-" : entry.gameId(),
                entry.message(),
                entry.throwable()
        );
    }

    private String feature(String loggerName) {
        if (loggerName == null || loggerName.isBlank()) {
            return "application";
        }
        String prefix = "com.kbo.crawlerapi.";
        String shortened = loggerName.startsWith(prefix) ? loggerName.substring(prefix.length()) : loggerName;
        int separator = shortened.lastIndexOf('.');
        return separator >= 0 ? shortened.substring(separator + 1) : shortened;
    }

    private String extractGameId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = GAME_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String throwableSummary(IThrowableProxy throwable) {
        if (throwable == null) {
            return null;
        }
        String className = throwable.getClassName() == null ? "Exception" : throwable.getClassName();
        String message = throwable.getMessage();
        return abbreviate(maskSecrets(message == null ? className : className + ": " + message), 1_000);
    }

    private String maskSecrets(String value) {
        if (value == null) {
            return null;
        }
        String masked = AUTHORIZATION_PATTERN.matcher(value).replaceAll("$1$2••••••••");
        masked = BEARER_PATTERN.matcher(masked).replaceAll("$1••••••••");
        return SECRET_PATTERN.matcher(masked).replaceAll("$1$2••••••••");
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private record LogEntry(
            Instant timestamp,
            String level,
            String feature,
            String gameId,
            String message,
            String throwable
    ) {
    }

    public record LogView(
            String timestamp,
            String level,
            String feature,
            String gameId,
            String message,
            String throwable
    ) {
        public boolean hasThrowable() {
            return throwable != null && !throwable.isBlank();
        }

        public String levelClass() {
            return switch (Level.toLevel(level, Level.INFO).levelStr) {
                case "ERROR" -> "danger";
                case "WARN" -> "warning";
                case "DEBUG", "TRACE" -> "muted";
                default -> "success";
            };
        }
    }
}
