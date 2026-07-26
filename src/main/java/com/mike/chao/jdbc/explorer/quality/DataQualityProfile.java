package com.mike.chao.jdbc.explorer.quality;

import java.util.List;

public record DataQualityProfile(
    String databaseProductName,
    String tableName,
    long rowCount,
    List<ColumnQualityProfile> columns,
    List<String> detectedSignals,
    List<String> candidateRuleTypes,
    List<String> exampleRuleSql,
    List<String> mcpProvidedSignals,
    List<String> llmAnalysisResponsibilities
) {
}
