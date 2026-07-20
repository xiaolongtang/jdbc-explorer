package com.mike.chao.jdbc.explorer;

import java.util.ArrayList;
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
        int gapX = 70;
        int gapY = 56;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, tables.size()))));
        Map<String, int[]> positions = new LinkedHashMap<>();
        List<Integer> rowHeights = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            int row = i / columns;
            int height = tableCardHeight(tables.get(i));
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
        for (int i = 0; i < tables.size(); i++) {
            TableDetails table = tables.get(i);
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
        for (ErRelationship relationship : relationships) {
            int[] from = positions.get(normalizeName(relationship.fromTable()));
            int[] to = positions.get(normalizeName(relationship.toTable()));
            if (from == null || to == null) continue;
            String stroke = relationship.source() == RelationshipSource.EXPLICIT_FOREIGN_KEY ? "#2563eb" : "#f97316";
            String dash = relationship.source() == RelationshipSource.EXPLICIT_FOREIGN_KEY ? "" : " stroke-dasharray=\"7 5\"";
            int x1 = from[0] + from[2]; int y1 = from[1] + from[3] / 2; int x2 = to[0]; int y2 = to[1] + to[3] / 2;
            int mx = (x1 + x2) / 2;
            svg.append("<path d=\"M").append(x1).append(' ').append(y1).append(" C").append(mx).append(' ').append(y1).append(' ').append(mx).append(' ').append(y2).append(' ').append(x2).append(' ').append(y2).append("\" fill=\"none\" stroke=\"").append(stroke).append("\" stroke-width=\"2.5\"").append(dash).append("/>");
            svg.append("<circle cx=\"").append(x2).append("\" cy=\"").append(y2).append("\" r=\"4\" fill=\"").append(stroke).append("\"/>");
        }
        for (TableDetails table : tables) {
            int[] p = positions.get(normalizeName(table.tableName()));
            svg.append("<g filter=\"url(#shadow)\"><rect x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" width=\"").append(p[2]).append("\" height=\"").append(p[3]).append("\" rx=\"18\" fill=\"#ffffff\" stroke=\"#dbeafe\"/>");
            svg.append("<rect x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" width=\"").append(p[2]).append("\" height=\"54\" rx=\"18\" fill=\"#1d4ed8\"/><text x=\"").append(p[0] + 18).append("\" y=\"").append(p[1] + 34).append("\" fill=\"#fff\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"18\" font-weight=\"700\">").append(escapeXml(table.tableName())).append("</text>");
            int y = p[1] + 78;
            for (ColumnDetail c : table.columns().stream().limit(14).toList()) {
                boolean pk = table.primaryKeyColumns().contains(c.name());
                svg.append("<text x=\"").append(p[0] + 18).append("\" y=\"").append(y).append("\" fill=\"").append(pk ? "#1d4ed8" : "#334155").append("\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"13\">").append(pk ? "◆ " : "• ").append(escapeXml(c.name())).append(" <tspan fill=\"#64748b\">").append(escapeXml(c.type())).append(c.nullable() ? "" : " not null").append("</tspan></text>");
                y += 24;
            }
            svg.append("</g>");
        }
        svg.append("<text x=\"32\" y=\"").append(maxBottom - 12).append("\" fill=\"#64748b\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"12\">solid blue = foreign key, dashed orange = inferred relationship</text>");
        svg.append("</svg>");
        return svg.toString();
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
