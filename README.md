# JDBC Explorer

A [Model Context Protocol](https://modelcontextprotocol.io/introduction) server for connecting LLM to databases via JDBC. This server is implemented using the [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) framework. The server exposes tools, a prompt and resources to interact with the connected database.

[![codecov](https://codecov.io/github/mikechao/jdbc-explorer/graph/badge.svg?token=326RPXDFJP)](https://codecov.io/github/mikechao/jdbc-explorer)


## Java version branches

This repository keeps the same JDBC Explorer functionality available on separate Java baseline branches:

| Branch | Java baseline | Notes |
|--------|---------------|-------|
| `main` | JDK 21 | Primary branch for the current JDK 21 build. |
| `main-jdk-17` | JDK 17 | Compatibility branch that builds and runs the same MCP server behavior on JDK 17. |

The `main-jdk-17` branch changes only the build/runtime baseline and related Maven/Docker configuration for JDK 17 compatibility. The exposed MCP tools, prompt, resources, supported JDBC databases, command-line options, and JSON configuration formats are intended to remain functionally identical to `main`.

When building this branch locally, use JDK 17 or newer and run:

```bash
./mvnw clean package
```

## Tools 🛠

The server contains the following tools.

- **addBusinessInsight** 

    - Adds business insights discovered during data analysis to the "Business Insights" resource. Usually executed as part of the prompt "data-explorer"
    - Inputs:
        - `insight` (String): business insight discovered during data analysis 

- **executeQuery**

    - Executes a SQL query against the connected database, returning a bounded result with `rows`, `truncated`, `rowLimit`, and `elapsedMilliseconds`
    - Inputs:
        - `query` (string): the SQL query to be executed
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

### Query safety and concurrency

Each configured database has its own bounded HikariCP connection pool and query concurrency limit. This prevents a burst of parallel MCP tool calls against one database from exhausting connections or starving calls to another database. Query results and individual text/binary cells are bounded before MCP serialization, and queued or running queries have explicit timeouts.

The defaults can be overridden with Spring Boot command-line options:

| Property | Default | Purpose |
|----------|---------|---------|
| `db.pool.maximum-size` | `4` | Maximum pooled connections per configured database |
| `db.pool.minimum-idle` | `0` | Avoid opening unused connections at startup |
| `db.pool.connection-timeout-ms` | `10000` | Maximum wait for a pooled connection |
| `db.query.max-rows` | `1000` | Maximum rows returned to the MCP client |
| `db.query.fetch-size` | `100` | JDBC fetch-size hint |
| `db.query.timeout-seconds` | `30` | JDBC statement timeout |
| `db.query.max-concurrent-per-database` | `4` | Fair per-database query concurrency limit |
| `db.query.queue-timeout-seconds` | `5` | Maximum wait for a query concurrency slot |
| `db.query.max-cell-characters` | `10000` | Maximum characters or bytes retained per cell |

Keep the pool maximum and query concurrency limit aligned unless the database has a reason to reserve connections for metadata operations. A result with `truncated: true` should be refined with filters, aggregation, or pagination instead of increasing the limit without considering the LLM context size.

- **getTableNames**

    - Gets the table names, including type, schema, and remarks
    - Inputs:
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

- **describeTable**
    
    - Describe a table in the database including column information, primary keys, foreign keys, and indexes.
    - Inputs:
        - `catalog` (string, optional): Catalog Name
        - `schema` (string, optional): Schema Name
        - `tableName` (string): Name of the table to get description for
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

- **getDatabaseInfo**

    - Get information about the database including SQL dialect, keywords, database product name, etc.
    - Inputs:
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

- **listDatabases**

    - Lists configured database connections and identifies the default connection.
    - Inputs: none

- **analyzeSqlOptimization**

    - Collects database-side optimization signals for an LLM. MCP executes or parses `EXPLAIN`, returns referenced table metadata and existing indexes, detects common issues such as full scans, inefficient joins, repeated subqueries, and functions in predicates, recommends candidate indexes with write/storage cost notes, estimates relative query cost, normalizes SQL for similar-query deduplication/reuse, and returns database product/version compatibility context. The LLM is responsible for interpreting the plan by dialect, prioritizing findings, and drafting safe SQL rewrites or index DDL.
    - Inputs:
        - `sql` (string): SQL query to optimize
        - `catalog` (string, optional): catalog for metadata lookup
        - `schema` (string, optional): schema for metadata lookup
        - `runExplain` (boolean, optional): whether to run `EXPLAIN`; defaults to `true`
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

- **profileDataQuality**

    - Profiles a table for data quality and profiling signals. MCP deterministically computes row count, column null count/rate, distinct count, min/max values, average string length, Top N values, primary-key and foreign-key hints, candidate rule categories, and example validation SQL. The LLM is responsible for mapping natural-language business rules to the correct fields/tables, generating dialect-safe validation SQL, interpreting severity and false positives, and explaining remediation.
    - Inputs:
        - `catalog` (string, optional): catalog for metadata lookup
        - `schema` (string, optional): schema for metadata lookup
        - `tableName` (string): table to profile
        - `topN` (integer, optional): maximum Top N values per column; defaults to `5` and is capped at `20`
        - `connectionName` (string, optional): database connection name from `listDatabases`; omitted uses the default connection

## Prompts 📄

The server contains 1 prompt.

- **data-explorer**

This prompt helps the user explore the data in their databases. It should present the user with a choice of dashboards that the LLM can create. The LLM will then execute the necessary queries and create the selected dashboard using an artifact.

The prompt result in Claude Desktop

<a href="https://mikechao.github.io/images/jdbc-explorer-prompt.webp" target="_blank" rel="noopener noreferrer">
<img width="380" height="200" src="https://mikechao.github.io/images/jdbc-explorer-prompt.webp" alt="claude desktop example" />
</a>

## Resources 🗂️

The server contains 1 resource.

- **Business Insights**

    - Contains the list of business insights that the LLM came up with during data analysis.
    - `uri`: "memo://insights"

## Supported JDBC variants

This server currently supports the following databases.

| Database |
|----------|
|sqlite|
|PostgreSQL|
|Oracle|
|h2|
|MySQL|

## Example Databases

**Netflix Movies**

Sample movie data based on Netflix catalog
[Netflix sample DB](https://github.com/lerocha/netflixdb)

**Northwind**

Classic Microsoft sample database with customers, orders, products etc.

[Northwind Sqlite](https://github.com/jpwhite3/northwind-SQLite3)

**Chinook**

Sample music store data including artists, albums, tracks, invoices etc.

[Chinook Database](https://github.com/lerocha/chinook-database)

## Usage with Claude Desktop

### From jar

1. Download the jar from the [Releases](https://github.com/mikechao/jdbc-explorer/releases)
2. Or clone the repo and build the jar with maven
```bash
mvn clean package
```

Add this to your `claude_desktop_config.json`:

#### Sqlite
```json
{
    "mcpServers": {
		  "jdbc-explorer": {
			"command": "java",
			"args": [
			  "-jar",
			  "C:\\\\mcp\\\\jdbc.explorer-0.4.0.jar",
			  "--db.url=jdbc:sqlite:C:\\\\mcp\\\\jdbc-explorer\\\\netflixdb.sqlite"
			]
		  }
	}
}
```

#### Database with username and password
```json
{
    "mcpServers": {
		  "jdbc-explorer": {
			"command": "java",
			"args": [
			  "-jar",
			  "C:\\\\mcp\\\\jdbc.explorer-0.4.0.jar",
			  "--db.url=jdbc:postgresql://localhost:5432/chinook",
			  "--db.username=dbuser",
			  "--db.password=dbpassword"
			]
		  }
	}
}
```

#### Multiple databases from a JSON config file

You can keep using the single database flags above, or provide a JSON file path with `--config-file`. Each database gets a stable `name`; use that value as `connectionName` when calling database tools. If `connectionName` is omitted, the configured `default` connection is used.

Example `databases.json`:

```json
{
  "default": "h2_reporting",
  "databases": [
    {
      "name": "h2_reporting",
      "url": "jdbc:h2:file:C:\\\\mcp\\\\db\\\\reporting",
      "username": "sa",
      "password": ""
    },
    {
      "name": "h2_archive",
      "url": "jdbc:h2:file:C:\\\\mcp\\\\db\\\\archive",
      "username": "sa",
      "password": ""
    },
    {
      "name": "postgres_sales",
      "url": "jdbc:postgresql://localhost:5432/sales",
      "username": "dbuser",
      "password": "dbpassword"
    }
  ]
}
```

Claude Desktop config:

```json
{
    "mcpServers": {
        "jdbc-explorer": {
            "command": "java",
            "args": [
                "-jar",
                "C:\\\\mcp\\\\jdbc.explorer-0.4.0.jar",
                "--config-file=C:\\\\mcp\\\\jdbc-explorer\\\\databases.json"
            ]
        }
    }
}
```

Named object format is also supported:

```json
{
  "defaultConnectionName": "warehouse",
  "warehouse": {
    "url": "jdbc:h2:file:C:\\\\mcp\\\\db\\\\warehouse",
    "username": "sa",
    "password": ""
  },
  "postgres_sales": {
    "url": "jdbc:postgresql://localhost:5432/sales",
    "username": "dbuser",
    "password": "dbpassword"
  }
}
```

### From Docker image

You can either build the image locally or pull it from GitHub Container Registry:

#### Option 1: Pull from GitHub Container Registry
```bash
docker pull ghcr.io/mikechao/jdbc-explorer:latest
```

#### Option 2: Build locally

1. Clone the repo
2. Build the docker image
```bash
docker build -t jdbc-explorer .
```

Add this to your `claude_desktop_config.json`:

#### Database with username and password
```json
{
    "mcpServers": {
		  "jdbc-explorer": {
			"command": "docker",
			"args": [
			  "run",
			  "-i",
			  "--rm",
			  "-e",
			  "DB_URL=jdbc:postgresql://host.docker.internal:5432/chinook",
			  "-e",
			  "DB_USERNAME=dbuser",
			  "-e",
			  "DB_PASSWORD=dbpassword",
			  "ghcr.io/mikechao/jdbc-explorer"
			]
		  }
	}
}
```



## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This MCP server is licensed under the MIT License. This means you are free to use, modify, and distribute the software, subject to the terms and conditions of the MIT License. For more details, please see the LICENSE file in the project repository.
