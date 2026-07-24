package com.mike.chao.jdbc.explorer.quality;

import java.util.List;
import java.util.Map;

public record ColumnQualityProfile(
    String columnName,
    String jdbcType,
    long nullCount,
    double nullRate,
    Long distinctCount,
    Object minValue,
    Object maxValue,
    Double averageLength,
    List<Map<String, Object>> topValues,
    List<String> signals
) {
}
