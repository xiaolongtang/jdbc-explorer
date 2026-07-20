package com.mike.chao.jdbc.explorer.optimization;

import java.util.List;
import java.util.Map;

import com.mike.chao.jdbc.explorer.data.TableDetails;

public record SqlOptimizationReport(
    String databaseProductName,
    String databaseProductVersion,
    String sql,
    boolean explainExecuted,
    List<Map<String, Object>> explainPlan,
    List<String> detectedIssues,
    List<String> rewriteSuggestions,
    List<IndexRecommendation> indexRecommendations,
    String queryCostEstimate,
    String normalizedSqlFingerprint,
    List<String> similarSqlReuseSuggestions,
    List<String> compatibilityNotes,
    List<TableDetails> referencedTableMetadata,
    List<String> mcpProvidedSignals,
    List<String> llmAnalysisResponsibilities
) {
}
