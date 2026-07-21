package com.mike.chao.jdbc.explorer.optimization;

public record IndexRecommendation(
    String tableName,
    String columns,
    String reason,
    String writeCost,
    String storageCost
) {
}
