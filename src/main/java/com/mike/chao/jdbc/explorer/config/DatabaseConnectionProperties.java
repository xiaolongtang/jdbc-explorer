package com.mike.chao.jdbc.explorer.config;

import com.fasterxml.jackson.annotation.JsonAlias;

public record DatabaseConnectionProperties(
    @JsonAlias("connectionName") String name,
    @JsonAlias({"jdbcUrl", "dbUrl"}) String url,
    String username,
    String password,
    @JsonAlias({"driver", "driverClass"}) String driverClassName
) {

    public DatabaseConnectionProperties {
        name = normalize(name);
        url = normalize(url);
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        driverClassName = normalize(driverClassName);
    }

    public DatabaseConnectionProperties withName(String connectionName) {
        return new DatabaseConnectionProperties(connectionName, url, username, password, driverClassName);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
