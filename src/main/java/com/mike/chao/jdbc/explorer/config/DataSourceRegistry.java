package com.mike.chao.jdbc.explorer.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class DataSourceRegistry {

    public static final String DEFAULT_CONNECTION_NAME = "default";

    private final String defaultConnectionName;
    private final Map<String, DataSource> dataSources;
    private final Map<String, DatabaseConnectionInfo> connectionInfo;

    public DataSourceRegistry(
        String defaultConnectionName,
        Map<String, DataSource> dataSources,
        Map<String, DatabaseConnectionInfo> connectionInfo
    ) {
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalArgumentException("At least one database connection must be configured.");
        }
        if (defaultConnectionName == null || defaultConnectionName.isBlank()) {
            throw new IllegalArgumentException("Default database connection name must not be blank.");
        }
        if (!dataSources.containsKey(defaultConnectionName)) {
            throw new IllegalArgumentException(
                "Default database connection '%s' was not found. Available connections: %s"
                    .formatted(defaultConnectionName, dataSources.keySet())
            );
        }
        this.defaultConnectionName = defaultConnectionName;
        this.dataSources = Collections.unmodifiableMap(new LinkedHashMap<>(dataSources));
        this.connectionInfo = Collections.unmodifiableMap(new LinkedHashMap<>(connectionInfo));
    }

    public static DataSourceRegistry single(DataSource dataSource) {
        return single(DEFAULT_CONNECTION_NAME, dataSource);
    }

    public static DataSourceRegistry single(String connectionName, DataSource dataSource) {
        String normalizedName = normalizeConnectionName(connectionName);
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put(normalizedName, dataSource);

        Map<String, DatabaseConnectionInfo> connectionInfo = new LinkedHashMap<>();
        connectionInfo.put(normalizedName, createConnectionInfo(normalizedName, dataSource, true));

        return new DataSourceRegistry(normalizedName, dataSources, connectionInfo);
    }

    public DataSource getDataSource(String connectionName) {
        String resolvedConnectionName = resolveConnectionName(connectionName);
        DataSource dataSource = dataSources.get(resolvedConnectionName);
        if (dataSource == null) {
            throw new IllegalArgumentException(
                "Unknown database connection '%s'. Available connections: %s"
                    .formatted(resolvedConnectionName, dataSources.keySet())
            );
        }
        return dataSource;
    }

    public DataSource getDefaultDataSource() {
        return getDataSource(defaultConnectionName);
    }

    public String getDefaultConnectionName() {
        return defaultConnectionName;
    }

    public List<DatabaseConnectionInfo> listConnectionInfo() {
        return dataSources.keySet().stream()
            .map(connectionName -> connectionInfo.getOrDefault(
                connectionName,
                createConnectionInfo(
                    connectionName,
                    dataSources.get(connectionName),
                    connectionName.equals(defaultConnectionName)
                )
            ))
            .toList();
    }

    private String resolveConnectionName(String connectionName) {
        return connectionName == null || connectionName.isBlank()
            ? defaultConnectionName
            : connectionName.trim();
    }

    private static String normalizeConnectionName(String connectionName) {
        return connectionName == null || connectionName.isBlank()
            ? DEFAULT_CONNECTION_NAME
            : connectionName.trim();
    }

    private static DatabaseConnectionInfo createConnectionInfo(
        String connectionName,
        DataSource dataSource,
        boolean defaultConnection
    ) {
        if (dataSource instanceof DriverManagerDataSource driverManagerDataSource) {
            return new DatabaseConnectionInfo(
                connectionName,
                driverManagerDataSource.getUrl(),
                driverManagerDataSource.getUsername(),
                null,
                defaultConnection
            );
        }
        return new DatabaseConnectionInfo(connectionName, null, null, null, defaultConnection);
    }
}
