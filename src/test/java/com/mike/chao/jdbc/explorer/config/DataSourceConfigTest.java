package com.mike.chao.jdbc.explorer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = DataSourceConfig.class)
@TestPropertySource(properties = {
    "db.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "db.username=sa",
    "db.password="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DataSourceConfigTest {

    @Autowired(required = false)
    private DataSource dataSource;

    @Autowired(required = false)
    private DataSourceRegistry dataSourceRegistry;

    @Test
    void testH2DataSourceCreated() {
        assertNotNull(dataSource);
        assertNotNull(dataSourceRegistry);
        assertEquals("default", dataSourceRegistry.getDefaultConnectionName());
        assertEquals(1, dataSourceRegistry.listConnectionInfo().size());
        assertEquals("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", ((DriverManagerDataSource) dataSource).getUrl());
        assertEquals("sa", ((DriverManagerDataSource) dataSource).getUsername());
    }

    @Test
    void testUnsupportedDbUrlThrowsException() {
        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                "db.url=jdbc:unsupported://localhost:1234/db",
                "db.username=test",
                "db.password=test"
            )
            .run(context -> assertThatThrownBy(() -> context.getBean(javax.sql.DataSource.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("Unsupported DB URL"));
    }

    @Test
    void testConfigFileWithMultipleDatabaseTypes(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("databases.json");
        Files.writeString(configFile, """
            {
              "default": "h2_reporting",
              "databases": [
                {
                  "name": "h2_reporting",
                  "url": "jdbc:h2:mem:reporting;DB_CLOSE_DELAY=-1",
                  "username": "sa",
                  "password": ""
                },
                {
                  "name": "h2_archive",
                  "url": "jdbc:h2:mem:archive;DB_CLOSE_DELAY=-1",
                  "username": "sa",
                  "password": ""
                },
                {
                  "name": "postgres_sales",
                  "url": "jdbc:postgresql://localhost:5432/sales",
                  "username": "sales_user",
                  "password": "secret"
                }
              ]
            }
            """);

        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues("config-file=" + configFile)
            .run(context -> {
                assertThat(context).hasNotFailed();

                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertEquals("h2_reporting", registry.getDefaultConnectionName());
                assertEquals(3, registry.listConnectionInfo().size());

                DriverManagerDataSource defaultDataSource = (DriverManagerDataSource) context.getBean(DataSource.class);
                assertEquals("jdbc:h2:mem:reporting;DB_CLOSE_DELAY=-1", defaultDataSource.getUrl());

                Optional<DatabaseConnectionInfo> postgresInfo = registry.listConnectionInfo().stream()
                    .filter(connection -> "postgres_sales".equals(connection.name()))
                    .findFirst();
                assertTrue(postgresInfo.isPresent());
                assertEquals("org.postgresql.Driver", postgresInfo.get().driverClassName());
            });
    }

    @Test
    void testConfigFileWithNamedObjectFormat(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("databases-object.json");
        Files.writeString(configFile, """
            {
              "defaultConnectionName": "warehouse",
              "warehouse": {
                "url": "jdbc:h2:mem:warehouse;DB_CLOSE_DELAY=-1",
                "username": "sa",
                "password": ""
              },
              "finance": {
                "url": "jdbc:h2:mem:finance;DB_CLOSE_DELAY=-1",
                "username": "sa",
                "password": ""
              }
            }
            """);

        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues("config-file=" + configFile)
            .run(context -> {
                assertThat(context).hasNotFailed();

                DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                assertEquals("warehouse", registry.getDefaultConnectionName());
                assertEquals(2, registry.listConnectionInfo().size());
                assertTrue(registry.listConnectionInfo().stream().anyMatch(connection -> "finance".equals(connection.name())));

                DriverManagerDataSource financeDataSource = (DriverManagerDataSource) registry.getDataSource("finance");
                assertEquals("jdbc:h2:mem:finance;DB_CLOSE_DELAY=-1", financeDataSource.getUrl());
            });
    }

}
