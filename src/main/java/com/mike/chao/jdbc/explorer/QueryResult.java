package com.mike.chao.jdbc.explorer;

import java.util.List;
import java.util.Map;

public record QueryResult(
    List<Map<String, Object>> rows,
    boolean truncated,
    int rowLimit,
    long elapsedMilliseconds
) {
}
