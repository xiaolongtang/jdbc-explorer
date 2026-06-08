package com.mike.chao.jdbc.explorer.config;

public record DatabaseConnectionInfo(
    String name,
    String url,
    String username,
    String driverClassName,
    boolean defaultConnection
) {
}
