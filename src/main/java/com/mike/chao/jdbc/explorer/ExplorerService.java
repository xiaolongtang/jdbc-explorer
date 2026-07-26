package com.mike.chao.jdbc.explorer;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.sql.DatabaseMetaData;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.mike.chao.jdbc.explorer.data.ErDiagram;
import com.mike.chao.jdbc.explorer.data.ErRelationship;
import com.mike.chao.jdbc.explorer.data.ForeignKeyDetail;
import com.mike.chao.jdbc.explorer.data.IndexDetail;
import com.mike.chao.jdbc.explorer.data.TableDetails;
import com.mike.chao.jdbc.explorer.data.TableInfo;
import com.mike.chao.jdbc.explorer.data.RelationshipSource;
import com.mike.chao.jdbc.explorer.optimization.IndexRecommendation;
import com.mike.chao.jdbc.explorer.optimization.SqlOptimizationReport;
import com.mike.chao.jdbc.explorer.quality.ColumnQualityProfile;
import com.mike.chao.jdbc.explorer.quality.DataQualityProfile;

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


    @Tool(name = "profileDataQuality", description = "Profile a table for data quality signals. MCP computes deterministic row counts, null rates, distinct counts, min/max values, string lengths, top values, metadata-backed key hints, and example SQL predicates; the LLM turns user-described business rules into dialect-safe SQL and interprets risk.")
    public DataQualityProfile profileDataQuality(
        @ToolParam(description = "Catalog Name for metadata lookup", required = false) String catalog,
        @ToolParam(description = "Schema Name for metadata lookup", required = false) String schema,
        @ToolParam(description = "Table name to profile", required = true) String tableName,
        @ToolParam(description = "Maximum number of Top N values per column. Defaults to 5.", required = false) Integer topN,
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        int resolvedTopN = topN == null || topN < 1 ? 5 : Math.min(topN, 20);
        List<ColumnQualityProfile> columnProfiles = new ArrayList<>();
        List<String> detectedSignals = new ArrayList<>();
        List<String> exampleRuleSql = new ArrayList<>();
        try (var conn = getDataSource(connectionName).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            long rowCount = scalarLong(conn, "SELECT COUNT(*) FROM " + qualifiedTableName(schema, tableName));
            List<ColumnDetail> columns = fetchColumnDetails(metaData, catalog, schema, tableName);
            Set<String> primaryKeys = fetchPrimaryKeyColumns(metaData, catalog, schema, tableName).stream()
                .map(this::normalizeName)
                .collect(Collectors.toSet());
            List<ForeignKeyDetail> foreignKeys = fetchForeignKeyDetails(metaData, catalog, schema, tableName);

            for (ColumnDetail column : columns) {
                String columnRef = quotedIdentifier(column.name());
                String tableRef = qualifiedTableName(schema, tableName);
                long nullCount = scalarLong(conn, "SELECT COUNT(*) FROM " + tableRef + " WHERE " + columnRef + " IS NULL");
                double nullRate = rowCount == 0 ? 0.0 : (double) nullCount / rowCount;
                Long distinctCount = scalarLong(conn, "SELECT COUNT(DISTINCT " + columnRef + ") FROM " + tableRef);
                Object minValue = scalarObject(conn, "SELECT MIN(" + columnRef + ") FROM " + tableRef);
                Object maxValue = scalarObject(conn, "SELECT MAX(" + columnRef + ") FROM " + tableRef);
                Double averageLength = isTextColumn(column.type())
                    ? scalarDouble(conn, "SELECT AVG(CHAR_LENGTH(" + columnRef + ")) FROM " + tableRef + " WHERE " + columnRef + " IS NOT NULL")
                    : null;
                List<Map<String, Object>> topValues = executeQuery(
                    "SELECT " + columnRef + " AS \"value\", COUNT(*) AS \"frequency\" FROM " + tableRef
                        + " GROUP BY " + columnRef + " ORDER BY \"frequency\" DESC LIMIT " + resolvedTopN,
                    connectionName
                );
                List<String> signals = new ArrayList<>();
                if (nullRate > 0.0) {
                    signals.add("Column has nulls; validate completeness expectations.");
                }
                if (rowCount > 0 && distinctCount == rowCount) {
                    signals.add("Column is unique in the current sample and may be a candidate key.");
                }
                if (isTextColumn(column.type()) && distinctCount != null && distinctCount <= Math.max(20, rowCount / 10)) {
                    signals.add("Low-cardinality text column may be an enum/status field.");
                }
                if (primaryKeys.contains(normalizeName(column.name()))) {
                    signals.add("Column is declared as a primary key.");
                }
                foreignKeys.stream()
                    .filter(fk -> normalizeName(fk.fkColumnName()).equals(normalizeName(column.name())))
                    .findFirst()
                    .ifPresent(fk -> signals.add("Column is declared as a foreign key to " + fk.pkTableName() + "." + fk.pkColumnName() + "."));
                detectedSignals.addAll(signals.stream().map(signal -> column.name() + ": " + signal).toList());
                columnProfiles.add(new ColumnQualityProfile(column.name(), column.type(), nullCount, nullRate, distinctCount,
                    boundCellValue(minValue), boundCellValue(maxValue), averageLength, topValues, signals));
            }

            exampleRuleSql.add("SELECT * FROM " + qualifiedTableName(schema, tableName) + " WHERE <amount_column> < 0;");
            exampleRuleSql.add("SELECT * FROM " + qualifiedTableName(schema, tableName) + " WHERE <status_column> = 'paid' AND <paid_at_column> IS NULL;");
            return new DataQualityProfile(metaData.getDatabaseProductName(), tableName, rowCount, columnProfiles,
                detectedSignals,
                List.of("uniqueness", "completeness", "referential integrity", "value domain", "time continuity", "data freshness", "cross-field consistency", "cross-table consistency"),
                exampleRuleSql,
                List.of("MCP profiles tables/columns with deterministic SQL", "MCP returns row counts, null rates, distinct counts, min/max, top values, and key metadata", "MCP provides SQL-ready examples and anomaly signals from measured data"),
                List.of("LLM maps natural-language rules to the correct tables and columns", "LLM writes dialect-safe validation SQL", "LLM judges business severity, false positives, caveats, and remediation steps"));
        } catch (Exception e) {
            logger.error("Error profileDataQuality for {} message: {}", tableName, e.getMessage(), e);
            throw new ToolExecutionException(getToolDefinition("profileDataQuality"), e);
        }
    }

    @Tool(name = "analyzeSqlOptimization", description = "Collect database-side SQL optimization signals for an LLM. Optionally runs EXPLAIN, inspects referenced table metadata and indexes, flags common anti-patterns, recommends candidate indexes with write/storage cost notes, estimates relative query cost, normalizes similar SQL, and reports database-version compatibility notes.")
    public SqlOptimizationReport analyzeSqlOptimization(
        @ToolParam(description = "SQL query to optimize", required = true) String sql,
        @ToolParam(description = "Catalog Name for metadata lookup", required = false) String catalog,
        @ToolParam(description = "Schema Name for metadata lookup", required = false) String schema,
        @ToolParam(description = "Run EXPLAIN for the query. Defaults to true.", required = false) Boolean runExplain,
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        List<Map<String, Object>> explainPlan = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> rewrites = new ArrayList<>();
        List<IndexRecommendation> indexes = new ArrayList<>();
        List<String> reuse = new ArrayList<>();
        List<String> compatibility = new ArrayList<>();
        List<TableDetails> metadata = new ArrayList<>();
        boolean explainExecuted = false;
        try (var conn = getDataSource(connectionName).getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String productName = dbMeta.getDatabaseProductName();
            String productVersion = dbMeta.getDatabaseProductVersion();
            if (runExplain == null || runExplain) {
                try (var stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(queryProperties.timeoutSeconds());
                    stmt.setMaxRows(50);
                    try (var rs = stmt.executeQuery(explainPrefix(productName) + sql)) {
                        var rsmd = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                                row.put(rsmd.getColumnLabel(i), boundCellValue(rs.getObject(i)));
                            }
                            explainPlan.add(row);
                        }
                        explainExecuted = true;
                    }
                } catch (Exception e) {
                    issues.add("EXPLAIN execution failed: " + e.getMessage());
                }
            }

            Set<String> referencedTables = referencedTables(sql);
            for (String table : referencedTables) {
                try {
                    metadata.add(new TableDetails(
                        table,
                        fetchColumnDetails(dbMeta, catalog, schema, table),
                        fetchPrimaryKeyColumns(dbMeta, catalog, schema, table),
                        fetchForeignKeyDetails(dbMeta, catalog, schema, table),
                        fetchIndexDetails(dbMeta, catalog, schema, table)
                    ));
                } catch (Exception e) {
                    issues.add("Could not inspect table metadata for " + table + ": " + e.getMessage());
                }
            }

            String lower = sql.toLowerCase(java.util.Locale.ROOT);
            String planText = explainPlan.toString().toLowerCase(java.util.Locale.ROOT);
            if (planText.contains("table scan") || planText.contains("seq scan") || planText.contains("full") || planText.contains("scan")) {
                issues.add("Execution plan indicates a possible full/large table scan; validate predicates and available indexes.");
            }
            if (lower.contains(" join ") && !lower.contains(" on ") && !lower.contains(" using ")) {
                issues.add("JOIN without ON/USING may create a Cartesian product or inefficient join.");
                rewrites.add("Add explicit JOIN predicates and prefer selective join keys backed by indexes.");
            }
            if (Pattern.compile("where\\s+\\w+\\s*\\(", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find()) {
                issues.add("A function appears in the WHERE predicate and may make a normal index unusable.");
                rewrites.add("Move functions to constants/generated columns, or create a function-based index where supported.");
            }
            if (countOccurrences(lower, "select") > 1) {
                issues.add("Query contains subqueries; repeated or correlated subqueries may be expensive.");
                rewrites.add("Consider replacing repeated subqueries with CTEs, derived tables, or joins when semantics match.");
            }
            for (String table : referencedTables) {
                for (String column : predicateColumns(sql)) {
                    if (metadata.stream().anyMatch(t -> normalizeName(t.tableName()).equals(normalizeName(table))
                        && t.indexes().stream().noneMatch(i -> normalizeName(i.columnName()).equals(normalizeName(column))))) {
                        indexes.add(new IndexRecommendation(table, column, "Column appears in WHERE/JOIN predicates without a visible single-column index.", "Every INSERT/UPDATE/DELETE must maintain the index; cost grows with table write volume and index width.", "Requires extra storage roughly proportional to row count plus indexed column/key size."));
                    }
                }
            }
            String normalized = normalizeSqlFingerprint(sql);
            reuse.add("Use normalized fingerprint to deduplicate/query-cache similar SQL: " + normalized);
            compatibility.add("Target dialect/version: " + productName + " " + productVersion + "; the LLM should adapt LIMIT/OFFSET, quoting, date functions, CTE/window support, and function-based indexes to this version.");
            String cost = explainExecuted ? "Relative cost should be inferred from EXPLAIN rows plus scan/join signals; JDBC metadata does not expose a portable numeric cost." : "EXPLAIN was not executed, so only heuristic cost signals are available.";
            return new SqlOptimizationReport(productName, productVersion, sql, explainExecuted, explainPlan, issues, rewrites, indexes, cost, normalized, reuse, compatibility, metadata,
                List.of("MCP runs/returns EXPLAIN output", "MCP returns table, column, primary key, foreign key, and index metadata", "MCP computes deterministic SQL fingerprints and heuristic anti-pattern signals", "MCP exposes database product/version for dialect compatibility"),
                List.of("LLM interprets plan semantics by dialect", "LLM prioritizes detected issues by business context", "LLM drafts safe SQL rewrites and migration/index DDL", "LLM explains trade-offs and validates compatibility assumptions"));
        } catch (Exception e) {
            logger.error("Error analyzeSqlOptimization message: {}", e.getMessage(), e);
            throw new ToolExecutionException(getToolDefinition("analyzeSqlOptimization"), e);
        }
    }

    public List<Map<String, Object>> executeQuery(String query) {
        return executeQuery(query, null);
    }

    public List<Map<String, Object>> executeQuery(String query, String connectionName) {
        return executeQueryResult(query, connectionName).rows();
    }

    private long scalarLong(java.sql.Connection conn, String sql) throws SQLException {
        Object value = scalarObject(conn, sql);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private Double scalarDouble(java.sql.Connection conn, String sql) throws SQLException {
        Object value = scalarObject(conn, sql);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private Object scalarObject(java.sql.Connection conn, String sql) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.setQueryTimeout(queryProperties.timeoutSeconds());
            try (var rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private String qualifiedTableName(String schema, String tableName) {
        if (schema == null || schema.isBlank()) {
            return quotedIdentifier(tableName);
        }
        return quotedIdentifier(schema) + "." + quotedIdentifier(tableName);
    }

    private String quotedIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean isTextColumn(String jdbcType) {
        String type = jdbcType == null ? "" : jdbcType.toUpperCase(java.util.Locale.ROOT);
        return type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB") || type.contains("STRING");
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


    @Tool(name = "generateErDiagram", description = "Generate a self-contained SVG ER diagram from database metadata. Uses declared foreign keys and can infer likely relationships from table and column naming when foreign keys are not declared.")
    public ErDiagram generateErDiagram(
        @ToolParam(description = "Catalog Name", required = false) String catalog,
        @ToolParam(description = "Schema Name", required = false) String schema,
        @ToolParam(description = "Optional comma-separated table names. Omit to diagram all user tables visible to JDBC metadata.", required = false) String tableNames,
        @ToolParam(description = "Infer likely relationships by matching ID columns to table primary keys when foreign keys are not declared. Defaults to true.", required = false) Boolean includeInferredRelationships,
        @ToolParam(description = "Database connection name from listDatabases. Omit to use the default connection.", required = false) String connectionName) {
        try (var conn = getDataSource(connectionName).getConnection()) {
            var metaData = conn.getMetaData();
            Set<String> requestedTables = parseTableNames(tableNames);
            List<TableInfo> tableInfos = new ArrayList<>();
            try (var rs = metaData.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (requestedTables.isEmpty() || requestedTables.contains(normalizeName(tableName))) {
                        tableInfos.add(new TableInfo(
                            tableName,
                            rs.getString("TABLE_TYPE"),
                            rs.getString("REMARKS"),
                            rs.getString("TABLE_SCHEM"),
                            rs.getString("TABLE_CAT")
                        ));
                    }
                }
            }
            tableInfos.sort(Comparator.comparing(TableInfo::tableName, String.CASE_INSENSITIVE_ORDER));

            List<TableDetails> tables = new ArrayList<>();
            for (TableInfo tableInfo : tableInfos) {
                tables.add(new TableDetails(
                    tableInfo.tableName(),
                    fetchColumnDetails(metaData, tableInfo.catalog(), tableInfo.schema(), tableInfo.tableName()),
                    fetchPrimaryKeyColumns(metaData, tableInfo.catalog(), tableInfo.schema(), tableInfo.tableName()),
                    fetchForeignKeyDetails(metaData, tableInfo.catalog(), tableInfo.schema(), tableInfo.tableName()),
                    fetchIndexDetails(metaData, tableInfo.catalog(), tableInfo.schema(), tableInfo.tableName())
                ));
            }

            List<ErRelationship> relationships = collectRelationships(tables, includeInferredRelationships == null || includeInferredRelationships);
            String svg = renderErSvg(tables, relationships);
            String dataUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
            return new ErDiagram("svg", "image/svg+xml", svg, dataUri, tables, relationships);
        } catch (Exception e) {
            logger.error("Error generateErDiagram message: {}", e.getMessage(), e);
            ToolDefinition toolDefinition = getToolDefinition("generateErDiagram");
            throw new ToolExecutionException(toolDefinition, e);
        }
    }

    public ErDiagram generateErDiagram(String catalog, String schema, String tableNames, Boolean includeInferredRelationships) {
        return generateErDiagram(catalog, schema, tableNames, includeInferredRelationships, null);
    }


    private String explainPrefix(String productName) {
        String name = productName == null ? "" : productName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("postgresql")) return "EXPLAIN (FORMAT JSON) ";
        if (name.contains("mysql") || name.contains("mariadb")) return "EXPLAIN FORMAT=JSON ";
        return "EXPLAIN ";
    }

    private Set<String> referencedTables(String sql) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?i)\\b(from|join)\\s+([\\w.]+)").matcher(sql.replace('"', ' ').replace('`', ' ').replace('[', ' ').replace(']', ' '));
        while (matcher.find()) {
            String table = matcher.group(2);
            if (table.contains(".")) table = table.substring(table.lastIndexOf('.') + 1);
            tables.add(table);
        }
        return tables;
    }

    private Set<String> predicateColumns(String sql) {
        Set<String> columns = new java.util.LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?i)(?:where|and|or|on)\\s+(?:\\w+\\.)?(\\w+)\\s*(=|<|>|like|in)").matcher(sql.replace('"', ' ').replace('`', ' ').replace('[', ' ').replace(']', ' '));
        while (matcher.find()) columns.add(matcher.group(1));
        return columns;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0, index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }

    private String normalizeSqlFingerprint(String sql) {
        return sql.replaceAll("'[^']*'", "?").replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "?").replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
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


    private Set<String> parseTableNames(String tableNames) {
        if (tableNames == null || tableNames.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(tableNames.split(","))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .map(this::normalizeName)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private List<ErRelationship> collectRelationships(List<TableDetails> tables, boolean includeInferredRelationships) {
        List<ErRelationship> relationships = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, TableDetails> tablesByName = new LinkedHashMap<>();
        for (TableDetails table : tables) {
            tablesByName.put(normalizeName(table.tableName()), table);
        }
        for (TableDetails table : tables) {
            for (ForeignKeyDetail fk : table.foreignKeys()) {
                addRelationship(relationships, seen, new ErRelationship(
                    table.tableName(), fk.fkColumnName(), fk.pkTableName(), fk.pkColumnName(),
                    RelationshipSource.EXPLICIT_FOREIGN_KEY, 1.0
                ));
            }
        }
        if (!includeInferredRelationships) {
            return relationships;
        }
        for (TableDetails fromTable : tables) {
            for (ColumnDetail column : fromTable.columns()) {
                if (fromTable.primaryKeyColumns().contains(column.name())) {
                    continue;
                }
                inferRelationship(fromTable, column, tablesByName).ifPresent(relationship -> addRelationship(relationships, seen, relationship));
            }
        }
        return relationships;
    }

    private Optional<ErRelationship> inferRelationship(TableDetails fromTable, ColumnDetail column, Map<String, TableDetails> tablesByName) {
        String columnName = normalizeName(column.name());
        if (!(columnName.endsWith("id") || columnName.endsWith("_id"))) {
            return Optional.empty();
        }
        String stem = columnName.endsWith("_id") ? columnName.substring(0, columnName.length() - 3) : columnName.substring(0, columnName.length() - 2);
        List<String> candidateTableNames = List.of(stem, stem + "s", stem + "es", stem.endsWith("y") ? stem.substring(0, stem.length() - 1) + "ies" : stem);
        for (String candidateTableName : candidateTableNames) {
            TableDetails toTable = tablesByName.get(candidateTableName);
            if (toTable == null || normalizeName(toTable.tableName()).equals(normalizeName(fromTable.tableName()))) {
                continue;
            }
            for (String pkColumn : toTable.primaryKeyColumns()) {
                String normalizedPk = normalizeName(pkColumn);
                if (normalizedPk.equals(columnName) || normalizedPk.equals("id") || normalizedPk.equals(normalizeName(toTable.tableName()) + "id")) {
                    return Optional.of(new ErRelationship(
                        fromTable.tableName(), column.name(), toTable.tableName(), pkColumn,
                        RelationshipSource.INFERRED_BY_COLUMN_NAME, normalizedPk.equals(columnName) ? 0.92 : 0.82
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private void addRelationship(List<ErRelationship> relationships, Set<String> seen, ErRelationship relationship) {
        String key = normalizeName(relationship.fromTable()) + "." + normalizeName(relationship.fromColumn())
            + "->" + normalizeName(relationship.toTable()) + "." + normalizeName(relationship.toColumn());
        if (seen.add(key)) {
            relationships.add(relationship);
        }
    }

    private String renderErSvg(List<TableDetails> tables, List<ErRelationship> relationships) {
        int cardWidth = 300;
        int gapX = 110;
        int gapY = 76;
        List<TableDetails> orderedTables = orderTablesForErLayout(tables, relationships);
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, orderedTables.size()))));
        Map<String, int[]> positions = new LinkedHashMap<>();
        List<Integer> rowHeights = new ArrayList<>();
        for (int i = 0; i < orderedTables.size(); i++) {
            int row = i / columns;
            int height = tableCardHeight(orderedTables.get(i));
            while (rowHeights.size() <= row) {
                rowHeights.add(0);
            }
            rowHeights.set(row, Math.max(rowHeights.get(row), height));
        }
        List<Integer> rowTops = new ArrayList<>();
        int currentTop = 32;
        for (Integer rowHeight : rowHeights) {
            rowTops.add(currentTop);
            currentTop += rowHeight + gapY;
        }
        int maxBottom = Math.max(96, currentTop - gapY + 32);
        for (int i = 0; i < orderedTables.size(); i++) {
            TableDetails table = orderedTables.get(i);
            int row = i / columns;
            int col = i % columns;
            int height = tableCardHeight(table);
            int x = 32 + col * (cardWidth + gapX);
            int y = rowTops.get(row);
            positions.put(normalizeName(table.tableName()), new int[] {x, y, cardWidth, height});
        }
        int width = 64 + columns * cardWidth + (columns - 1) * gapX;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"").append(maxBottom).append("\" viewBox=\"0 0 ").append(width).append(' ').append(maxBottom).append("\">");
        svg.append("<defs><linearGradient id=\"bg\" x1=\"0\" x2=\"1\" y1=\"0\" y2=\"1\"><stop stop-color=\"#f8fafc\"/><stop offset=\"1\" stop-color=\"#eef2ff\"/></linearGradient><filter id=\"shadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\"><feDropShadow dx=\"0\" dy=\"10\" stdDeviation=\"10\" flood-color=\"#1e293b\" flood-opacity=\".14\"/></filter></defs>");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"url(#bg)\"/>");
        Map<String, Integer> routeCounts = new HashMap<>();
        for (ErRelationship relationship : relationships) {
            int[] from = positions.get(normalizeName(relationship.fromTable()));
            int[] to = positions.get(normalizeName(relationship.toTable()));
            if (from == null || to == null) continue;
            String stroke = relationship.source() == RelationshipSource.EXPLICIT_FOREIGN_KEY ? "#2563eb" : "#f97316";
            String dash = relationship.source() == RelationshipSource.EXPLICIT_FOREIGN_KEY ? "" : " stroke-dasharray=\"7 5\"";
            int route = routeCounts.merge(normalizeName(relationship.fromTable()) + "->" + normalizeName(relationship.toTable()), 1, Integer::sum) - 1;
            int[] anchors = relationshipAnchors(from, to, route);
            int x1 = anchors[0]; int y1 = anchors[1]; int x2 = anchors[2]; int y2 = anchors[3];
            int bend = Math.max(48, Math.abs(x2 - x1) / 2);
            svg.append("<path d=\"M").append(x1).append(' ').append(y1).append(" C").append(x1 + (x1 <= x2 ? bend : -bend)).append(' ').append(y1).append(' ').append(x2 + (x1 <= x2 ? -bend : bend)).append(' ').append(y2).append(' ').append(x2).append(' ').append(y2).append("\" fill=\"none\" stroke=\"").append(stroke).append("\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\".82\"").append(dash).append("/>");
            svg.append("<circle cx=\"").append(x2).append("\" cy=\"").append(y2).append("\" r=\"4\" fill=\"").append(stroke).append("\"/>");
        }
        for (TableDetails table : orderedTables) {
            int[] p = positions.get(normalizeName(table.tableName()));
            svg.append("<g filter=\"url(#shadow)\"><rect x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" width=\"").append(p[2]).append("\" height=\"").append(p[3]).append("\" rx=\"18\" fill=\"#ffffff\" stroke=\"#dbeafe\"/>");
            svg.append("<rect x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" width=\"").append(p[2]).append("\" height=\"54\" rx=\"18\" fill=\"#1d4ed8\"/><text x=\"").append(p[0] + 18).append("\" y=\"").append(p[1] + 34).append("\" fill=\"#fff\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"18\" font-weight=\"700\">").append(escapeXml(table.tableName())).append("</text>");
            int y = p[1] + 78;
            for (ColumnDetail c : table.columns().stream().limit(14).toList()) {
                boolean pk = table.primaryKeyColumns().contains(c.name());
                svg.append("<circle cx=\"").append(p[0] + 23).append("\" cy=\"").append(y - 4).append("\" r=\"").append(pk ? 4 : 3).append("\" fill=\"").append(pk ? "#1d4ed8" : "#94a3b8").append("\"/>");
                svg.append("<text x=\"").append(p[0] + 34).append("\" y=\"").append(y).append("\" fill=\"").append(pk ? "#1d4ed8" : "#334155").append("\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"13\">").append(escapeXml(c.name())).append(" <tspan fill=\"#64748b\">").append(escapeXml(c.type())).append(c.nullable() ? "" : " not null").append("</tspan></text>");
                y += 24;
            }
            svg.append("</g>");
        }
        svg.append("<text x=\"32\" y=\"").append(maxBottom - 12).append("\" fill=\"#64748b\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"12\">solid blue = foreign key, dashed orange = inferred relationship</text>");
        svg.append("</svg>");
        return svg.toString();
    }

    private List<TableDetails> orderTablesForErLayout(List<TableDetails> tables, List<ErRelationship> relationships) {
        Map<String, TableDetails> byName = tables.stream().collect(Collectors.toMap(table -> normalizeName(table.tableName()), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (ErRelationship relationship : relationships) {
            String from = normalizeName(relationship.fromTable());
            String to = normalizeName(relationship.toTable());
            if (!byName.containsKey(from) || !byName.containsKey(to)) continue;
            neighbors.computeIfAbsent(from, key -> new TreeSet<>()).add(to);
            neighbors.computeIfAbsent(to, key -> new TreeSet<>()).add(from);
        }
        List<String> remaining = new ArrayList<>(byName.keySet());
        remaining.sort(Comparator.comparingInt((String table) -> neighbors.getOrDefault(table, Set.of()).size()).reversed().thenComparing(String::compareTo));
        List<TableDetails> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String start : remaining) {
            if (!visited.add(start)) continue;
            Queue<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String table = queue.remove();
                ordered.add(byName.get(table));
                neighbors.getOrDefault(table, Set.of()).stream()
                    .sorted(Comparator.comparingInt((String neighbor) -> neighbors.getOrDefault(neighbor, Set.of()).size()).reversed().thenComparing(String::compareTo))
                    .filter(visited::add)
                    .forEach(queue::add);
            }
        }
        return ordered;
    }

    private int[] relationshipAnchors(int[] from, int[] to, int route) {
        int offset = (route % 5 - 2) * 10;
        boolean fromLeft = from[0] > to[0];
        int x1 = fromLeft ? from[0] : from[0] + from[2];
        int x2 = fromLeft ? to[0] + to[2] : to[0];
        int y1 = from[1] + from[3] / 2 + offset;
        int y2 = to[1] + to[3] / 2 - offset;
        return new int[] {x1, y1, x2, y2};
    }

    private int tableCardHeight(TableDetails table) {
        return 86 + Math.min(table.columns().size(), 14) * 24;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
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
