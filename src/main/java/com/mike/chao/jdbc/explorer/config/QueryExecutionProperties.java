package com.mike.chao.jdbc.explorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db.query")
public record QueryExecutionProperties(
    int maxRows,
    int fetchSize,
    int timeoutSeconds,
    int maxConcurrentPerDatabase,
    int queueTimeoutSeconds,
    int maxCellCharacters
) {
    public QueryExecutionProperties {
        maxRows = positive(maxRows, 1000, "max-rows");
        fetchSize = positive(fetchSize, 100, "fetch-size");
        timeoutSeconds = positive(timeoutSeconds, 30, "timeout-seconds");
        maxConcurrentPerDatabase = positive(maxConcurrentPerDatabase, 4, "max-concurrent-per-database");
        queueTimeoutSeconds = positive(queueTimeoutSeconds, 5, "queue-timeout-seconds");
        maxCellCharacters = positive(maxCellCharacters, 10000, "max-cell-characters");
    }

    public QueryExecutionProperties() {
        this(1000, 100, 30, 4, 5, 10000);
    }

    private static int positive(int value, int defaultValue, String property) {
        if (value == 0) {
            return defaultValue;
        }
        if (value < 0) {
            throw new IllegalArgumentException("db.query." + property + " must be positive");
        }
        return value;
    }
}
