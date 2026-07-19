package com.mike.chao.jdbc.explorer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.sql.DatabaseMetaData;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mike.chao.jdbc.explorer.config.DataSourceRegistry;
import com.mike.chao.jdbc.explorer.config.DatabaseConnectionInfo;
import com.mike.chao.jdbc.explorer.config.QueryExecutionProperties;
import com.mike.chao.jdbc.explorer.data.ColumnDetail;
import com.mike.chao.jdbc.explorer.data.ForeignKeyDetail;
import com.mike.chao.jdbc.explorer.data.IndexDetail;
import com.mike.chao.jdbc.explorer.data.TableDetails;
import com.mike.chao.jdbc.explorer.data.TableInfo;

@Service
public class ExplorerService {

    private final DataSourceRegistry dataSourceRegistry;
    private final QueryExecutionProperties queryProperties;
    private final Map<String, Semaphore> queryPermits = new ConcurrentHashMap<>();
    private final Logger logger = LoggerFactory.getLogger(ExplorerService.class);

    @Autowired
    public ExplorerService(DataSourceRegistry dataSourceRegistry, QueryExecutionProperties queryProperties) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.queryProperties = queryProperties;
    }

    public ExplorerService(DataSourceRegistry dataSourceRegistry) {
        this(dataSourceRegistry, new QueryExecutionProperties());
    }

    public ExplorerService(DataSource dataSource) {
        this(DataSourceRegistry.single(dataSource));
    }

    @Tool(name = "listDatabases", description = "List configured database connections and the default connection name")
    public List<DatabaseConnectionInfo> listDatabases() {
        return dataSourceRegistry.listConnectionInfo();
    }

    @Tool(name = "executeQuery", description = "Execute a SQL query and return a bounded result. The response reports whether rows were truncated.")
    public QueryResult executeQueryResult(
        @ToolParam(description = "SQL query to execute", required = true) String query,
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        String resolvedConnectionName = dataSourceRegistry.resolveConnectionName(connectionName);
        Semaphore permits = queryPermits.computeIfAbsent(
            resolvedConnectionName,
            ignored -> new Semaphore(queryProperties.maxConcurrentPerDatabase(), true)
        );
        boolean acquired = false;
        long startedAt = System.nanoTime();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            acquired = permits.tryAcquire(queryProperties.queueTimeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                    "Database '%s' is busy; no query slot became available within %d seconds."
                        .formatted(resolvedConnectionName, queryProperties.queueTimeoutSeconds())
                );
            }
            try (var conn = getDataSource(resolvedConnectionName).getConnection();
                 var stmt = conn.createStatement()) {
                stmt.setQueryTimeout(queryProperties.timeoutSeconds());
                stmt.setFetchSize(queryProperties.fetchSize());
                stmt.setMaxRows(queryProperties.maxRows() + 1);
                try (var rs = stmt.executeQuery(query)) {
                    var rsmd = rs.getMetaData();
                    int columnCount = rsmd.getColumnCount();
                    while (results.size() <= queryProperties.maxRows() && rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(rsmd.getColumnLabel(i), boundCellValue(rs.getObject(i)));
                        }
                        results.add(row);
                    }
                }
                boolean truncated = results.size() > queryProperties.maxRows();
                if (truncated) {
                    results.remove(results.size() - 1);
                }
                long elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                logger.info("Query completed connection={} rows={} truncated={} elapsedMs={}",
                    resolvedConnectionName, results.size(), truncated, elapsedMilliseconds);
                return new QueryResult(results, truncated, queryProperties.maxRows(), elapsedMilliseconds);
            }
        } catch (Exception e) {
            logger.error("Query failed connection={} elapsedMs={} message={}", resolvedConnectionName,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e.getMessage(), e);
            ToolDefinition toolDefinition = getToolDefinition("executeQuery");
            throw new ToolExecutionException(toolDefinition, e);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    public List<Map<String, Object>> executeQuery(String query) {
        return executeQuery(query, null);
    }

    public List<Map<String, Object>> executeQuery(String query, String connectionName) {
        return executeQueryResult(query, connectionName).rows();
    }

    private Object boundCellValue(Object value) throws SQLException {
        if (value instanceof String text && text.length() > queryProperties.maxCellCharacters()) {
            return text.substring(0, queryProperties.maxCellCharacters()) + "…[truncated]";
        }
        if (value instanceof byte[] bytes && bytes.length > queryProperties.maxCellCharacters()) {
            return Arrays.copyOf(bytes, queryProperties.maxCellCharacters());
        }
        if (value instanceof Clob clob) {
            int retainedLength = (int) Math.min(clob.length(), queryProperties.maxCellCharacters());
            String retained = clob.getSubString(1, retainedLength);
            return clob.length() > retainedLength ? retained + "…[truncated]" : retained;
        }
        if (value instanceof Blob blob) {
            int retainedLength = (int) Math.min(blob.length(), queryProperties.maxCellCharacters());
            return blob.getBytes(1, retainedLength);
        }
        return value;
    }

    @Tool(name = "getTableNames", description = "Get all table names from the database, including type, schema, and remarks")
    public List<TableInfo> getTableNames(
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        List<TableInfo> tables = new ArrayList<>();
        try (var conn = getDataSource(connectionName).getConnection()) {
            var metaData = conn.getMetaData();
            String[] types = {"TABLE"}; // Only include tables, exclude views and system tables
            try (var rs = metaData.getTables(null, null, "%", types)) {
                while (rs.next()) {
                    var table = new TableInfo(
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                        rs.getString("REMARKS"),
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_CAT")
                    );
                    tables.add(table);
                }
            }
        } catch (Exception e) {
            logger.error("Error getTableNames message: {}", e.getMessage(), e);
            ToolDefinition toolDefinition = getToolDefinition("getTableNames");
            throw new ToolExecutionException(toolDefinition, e);
        }
        return tables;
    }

    public List<TableInfo> getTableNames() {
        return getTableNames(null);
    }

    @Tool(name = "describeTable", description = "Describe a table in the database, including column information, primary keys, foreign keys, and indexes.")
    public TableDetails describeTable(
        @ToolParam(description = "Catalog Name", required = false) String catalog,
        @ToolParam(description = "Schema Name", required = false) String schema,
        @ToolParam(description = "Name of the table to get description for") String tableName,
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        try (var conn = getDataSource(connectionName).getConnection()) {
            var metaData = conn.getMetaData();
            // Check if the table exists
            try (ResultSet tables = metaData.getTables(catalog, schema, tableName, new String[] {"TABLE"})) {
                if (!tables.next()) {
                    throw new IllegalArgumentException("""
                        Table '%s' does not exist in the database.""".formatted(tableName));
                }
            }

            List<ColumnDetail> columnDetails = fetchColumnDetails(metaData, catalog, schema, tableName);
            List<String> primaryKeyColumns = fetchPrimaryKeyColumns(metaData, catalog, schema, tableName);
            List<ForeignKeyDetail> foreignKeyDetails = fetchForeignKeyDetails(metaData, catalog, schema, tableName);
            List<IndexDetail> indexDetails = fetchIndexDetails(metaData, catalog, schema, tableName);

            return new TableDetails(
                tableName,
                columnDetails,
                primaryKeyColumns,
                foreignKeyDetails,
                indexDetails
            );
        } catch (Exception e) {
            logger.error("Error describeTable for {} message: {}", tableName, e.getMessage(), e);
            ToolDefinition toolDefinition = getToolDefinition("describeTable");
            throw new ToolExecutionException(toolDefinition, e);
        }
    }

    public TableDetails describeTable(String catalog, String schema, String tableName) {
        return describeTable(catalog, schema, tableName, null);
    }

    private List<ColumnDetail> fetchColumnDetails(DatabaseMetaData metaData, String catalog, String schema, String tableName) throws java.sql.SQLException {
        List<ColumnDetail> columns = new ArrayList<>();
        try (var rs = metaData.getColumns(catalog, schema, tableName, null)) {
            while (rs.next()) {
                columns.add(new ColumnDetail(
                    rs.getString("COLUMN_NAME"),
                    rs.getString("TYPE_NAME"),
                    rs.getInt("COLUMN_SIZE"),
                    rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable
                ));
            }
        }
        return columns;
    }

    private List<String> fetchPrimaryKeyColumns(DatabaseMetaData metaData, String catalog, String schema, String tableName) throws java.sql.SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (var pk = metaData.getPrimaryKeys(catalog, schema, tableName)) {
            while (pk.next()) {
                primaryKeys.add(pk.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    private List<ForeignKeyDetail> fetchForeignKeyDetails(DatabaseMetaData metaData, String catalog, String schema, String tableName) throws java.sql.SQLException {
        List<ForeignKeyDetail> foreignKeys = new ArrayList<>();
        try (var fk = metaData.getImportedKeys(catalog, schema, tableName)) {
            while (fk.next()) {
                foreignKeys.add(new ForeignKeyDetail(
                    fk.getString("FKCOLUMN_NAME"),
                    fk.getString("PKTABLE_NAME"),
                    fk.getString("PKCOLUMN_NAME")
                ));
            }
        }
        return foreignKeys;
    }

    private List<IndexDetail> fetchIndexDetails(DatabaseMetaData metaData, String catalog, String schema, String tableName) throws java.sql.SQLException {
        List<IndexDetail> indexes = new ArrayList<>();
        // Setting approximate to true can be faster if exact results are not critical for row counts in indexes
        try (var idx = metaData.getIndexInfo(catalog, schema, tableName, false, true)) {
            while (idx.next()) {
                String indexName = idx.getString("INDEX_NAME");
                String columnName = idx.getString("COLUMN_NAME");
                // TYPE column can be used to filter out table statistics (value 0 or tableIndexStatistic)
                short type = idx.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue; // Skip table statistics row
                }
                if (indexName != null && columnName != null) {
                    indexes.add(new IndexDetail(
                        indexName,
                        columnName,
                        !idx.getBoolean("NON_UNIQUE")
                    ));
                }
            }
        }
        return indexes;
    }

    private DataSource getDataSource(String connectionName) {
        return dataSourceRegistry.getDataSource(connectionName);
    }

    /**
     * Get a ToolDefinition for a given tool name using the ToolCallbacks
     * method from Spring AI to find the methods annotated with @Tool in this class.
     * This is used to provide a description of the tool in case of an error.
     * @param toolName
     * @return
     */
    private ToolDefinition getToolDefinition(String toolName) {
        List<ToolCallback> toolCallBacks = List.of(ToolCallbacks.from(this));
        Optional<ToolDefinition> toolDefinitionOptional = toolCallBacks.stream()
            .map(ToolCallback::getToolDefinition)
            .filter(definition -> definition.name().equals(toolName))
            .findFirst();
        return toolDefinitionOptional.orElse(getUnknownToolDefinition(toolName));
    }

    private ToolDefinition getUnknownToolDefinition(String toolName) {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Tool not found")
            .inputSchema("""
                {
                    "$schema" : "https://json-schema.org/draft/2020-12/schema",
                    "type" : "object",
                    "properties" : { },
                    "required" : [ ],
                    "additionalProperties" : false
                }
            """)
            .build();
    }
}
