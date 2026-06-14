package com.mike.chao.jdbc.explorer.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class DataSourceConfig {

    private static final String CONFIG_FILE_PROPERTY = "config-file";
    private static final String DB_CONFIG_FILE_PROPERTY = "db.config-file";

    private static final Map<String, String> DRIVER_CLASS_BY_PREFIX = createDriverClassByPrefix();

    @Value("${db.url:}")
    private String dbUrl;

    @Value("${db.username:}")
    private String dbUsername;

    @Value("${db.password:}")
    private String dbPassword;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSourceRegistry dataSourceRegistry(ObjectMapper objectMapper, Environment environment) {
        DatabaseConfigFile databaseConfigFile = loadDatabaseConfigFile(objectMapper, environment);
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        Map<String, DatabaseConnectionInfo> connectionInfo = new LinkedHashMap<>();

        for (DatabaseConnectionProperties connectionProperties : databaseConfigFile.connections()) {
            String connectionName = connectionProperties.name();
            if (!StringUtils.hasText(connectionName)) {
                throw new IllegalArgumentException("Database connection name must not be blank.");
            }
            if (dataSources.containsKey(connectionName)) {
                throw new IllegalArgumentException("Duplicate database connection name: " + connectionName);
            }

            String driverClassName = determineDriverClassName(connectionProperties);
            DataSource dataSource = createDataSource(connectionProperties, driverClassName);
            dataSources.put(connectionName, dataSource);
            connectionInfo.put(
                connectionName,
                new DatabaseConnectionInfo(
                    connectionName,
                    connectionProperties.url(),
                    connectionProperties.username(),
                    driverClassName,
                    connectionName.equals(databaseConfigFile.defaultConnectionName())
                )
            );
        }

        return new DataSourceRegistry(databaseConfigFile.defaultConnectionName(), dataSources, connectionInfo);
    }

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(DataSourceRegistry dataSourceRegistry) {
        return dataSourceRegistry.getDefaultDataSource();
    }

    private DatabaseConfigFile loadDatabaseConfigFile(ObjectMapper objectMapper, Environment environment) {
        String configFile = firstNonBlank(
            environment.getProperty(CONFIG_FILE_PROPERTY),
            environment.getProperty("config.file"),
            environment.getProperty(DB_CONFIG_FILE_PROPERTY),
            environment.getProperty("db.config.file")
        );

        if (StringUtils.hasText(configFile)) {
            return loadDatabaseConfigFileFromJson(objectMapper, configFile);
        }

        DatabaseConnectionProperties connectionProperties = new DatabaseConnectionProperties(
            DataSourceRegistry.DEFAULT_CONNECTION_NAME,
            dbUrl,
            dbUsername,
            dbPassword,
            null
        );
        validateConnection(connectionProperties);
        return new DatabaseConfigFile(List.of(connectionProperties), DataSourceRegistry.DEFAULT_CONNECTION_NAME);
    }

    private DatabaseConfigFile loadDatabaseConfigFileFromJson(ObjectMapper objectMapper, String configFile) {
        Path configPath = Path.of(configFile).toAbsolutePath().normalize();
        try {
            JsonNode root = objectMapper.readTree(configPath.toFile());
            DatabaseConfigFile databaseConfigFile = parseConfigJson(objectMapper, root);
            databaseConfigFile.connections().forEach(this::validateConnection);
            return databaseConfigFile;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read database config file: " + configPath, e);
        }
    }

    private DatabaseConfigFile parseConfigJson(ObjectMapper objectMapper, JsonNode root) {
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("Database config file cannot be empty.");
        }

        if (root.isArray()) {
            List<DatabaseConnectionProperties> connections = parseConnectionArray(objectMapper, root);
            return new DatabaseConfigFile(connections, firstConnectionName(connections));
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException("Database config file must be a JSON array or object.");
        }

        if (hasConnectionUrl(root)) {
            DatabaseConnectionProperties connection = objectMapper.convertValue(root, DatabaseConnectionProperties.class);
            connection = ensureConnectionName(connection, DataSourceRegistry.DEFAULT_CONNECTION_NAME);
            return new DatabaseConfigFile(List.of(connection), connection.name());
        }

        String defaultConnectionName = optionalText(root, "defaultConnectionName");
        if (!StringUtils.hasText(defaultConnectionName)) {
            defaultConnectionName = optionalText(root, "default");
        }

        JsonNode connectionsNode = firstPresent(root, "databases", "connections");
        List<DatabaseConnectionProperties> connections = connectionsNode == null
            ? parseNamedConnectionObject(objectMapper, root)
            : parseConnectionsNode(objectMapper, connectionsNode);

        if (!StringUtils.hasText(defaultConnectionName)) {
            defaultConnectionName = firstConnectionName(connections);
        }

        return new DatabaseConfigFile(connections, defaultConnectionName);
    }

    private List<DatabaseConnectionProperties> parseConnectionsNode(ObjectMapper objectMapper, JsonNode connectionsNode) {
        if (connectionsNode.isArray()) {
            return parseConnectionArray(objectMapper, connectionsNode);
        }
        if (connectionsNode.isObject()) {
            return parseNamedConnectionObject(objectMapper, connectionsNode);
        }
        throw new IllegalArgumentException("'databases' or 'connections' must be a JSON array or object.");
    }

    private List<DatabaseConnectionProperties> parseConnectionArray(ObjectMapper objectMapper, JsonNode connectionsNode) {
        List<DatabaseConnectionProperties> connections = new ArrayList<>();
        for (JsonNode node : connectionsNode) {
            DatabaseConnectionProperties connection = objectMapper.convertValue(node, DatabaseConnectionProperties.class);
            connections.add(connection);
        }
        if (connections.isEmpty()) {
            throw new IllegalArgumentException("At least one database connection must be configured.");
        }
        return connections;
    }

    private List<DatabaseConnectionProperties> parseNamedConnectionObject(ObjectMapper objectMapper, JsonNode root) {
        List<DatabaseConnectionProperties> connections = new ArrayList<>();
        root.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            if ("default".equals(fieldName) || "defaultConnectionName".equals(fieldName)) {
                return;
            }
            DatabaseConnectionProperties connection = objectMapper.convertValue(entry.getValue(), DatabaseConnectionProperties.class);
            connections.add(ensureConnectionName(connection, fieldName));
        });
        if (connections.isEmpty()) {
            throw new IllegalArgumentException("At least one database connection must be configured.");
        }
        return connections;
    }

    private DatabaseConnectionProperties ensureConnectionName(
        DatabaseConnectionProperties connection,
        String fallbackName
    ) {
        return StringUtils.hasText(connection.name()) ? connection : connection.withName(fallbackName);
    }

    private String firstConnectionName(List<DatabaseConnectionProperties> connections) {
        if (connections.isEmpty() || !StringUtils.hasText(connections.get(0).name())) {
            throw new IllegalArgumentException("The first database connection must define a name.");
        }
        return connections.get(0).name();
    }

    private JsonNode firstPresent(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String optionalText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private boolean hasConnectionUrl(JsonNode root) {
        return root.hasNonNull("url") || root.hasNonNull("jdbcUrl") || root.hasNonNull("dbUrl");
    }

    private DataSource createDataSource(DatabaseConnectionProperties connectionProperties, String driverClassName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(connectionProperties.url());
        ds.setDriverClassName(driverClassName);

        if (!connectionProperties.url().startsWith("jdbc:sqlite:")) {
            ds.setUsername(connectionProperties.username());
            ds.setPassword(connectionProperties.password());
        }

        return ds;
    }

    private String determineDriverClassName(DatabaseConnectionProperties connectionProperties) {
        if (StringUtils.hasText(connectionProperties.driverClassName())) {
            return connectionProperties.driverClassName();
        }

        for (String prefix : DRIVER_CLASS_BY_PREFIX.keySet()) {
            if (connectionProperties.url().startsWith(prefix)) {
                return DRIVER_CLASS_BY_PREFIX.get(prefix);
            }
        }

        throw new IllegalArgumentException("Unsupported DB URL: " + connectionProperties.url());
    }

    private void validateConnection(DatabaseConnectionProperties connectionProperties) {
        if (!StringUtils.hasText(connectionProperties.name())) {
            throw new IllegalArgumentException("Database connection name must not be blank.");
        }
        if (!StringUtils.hasText(connectionProperties.url())) {
            throw new IllegalArgumentException(
                "Database connection '%s' must define a JDBC url.".formatted(connectionProperties.name())
            );
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> createDriverClassByPrefix() {
        Map<String, String> driverClassByPrefix = new LinkedHashMap<>();
        driverClassByPrefix.put("jdbc:sqlite:", "org.sqlite.JDBC");
        driverClassByPrefix.put(
            "jdbc:postgresql:", "org.postgresql.Driver"
        );
        driverClassByPrefix.put(
            "jdbc:h2:", "org.h2.Driver"
        );
        driverClassByPrefix.put(
            "jdbc:mysql:", "com.mysql.cj.jdbc.Driver"
        );
        driverClassByPrefix.put(
            "jdbc:oracle:", "oracle.jdbc.OracleDriver"
        );
        return Collections.unmodifiableMap(driverClassByPrefix);
    }

    private record DatabaseConfigFile(
        List<DatabaseConnectionProperties> connections,
        String defaultConnectionName
    ) {
    }

}
