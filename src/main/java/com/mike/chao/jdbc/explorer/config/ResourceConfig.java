package com.mike.chao.jdbc.explorer.config;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mike.chao.jdbc.explorer.resources.BusinessInsights;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

@Configuration
public class ResourceConfig {

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> resources(BusinessInsights businessInsights) {
        var businessInsightsResource = new McpSchema.Resource(
            "memo://insights", 
            "Business Insights", 
            "Business Insights discover during data analysis", 
            "text/plain", 
            null
        );
        var businessAnalysisPlaybookResource = new McpSchema.Resource(
            "memo://business-analysis-playbook",
            "Business Analysis Playbook",
            "Advanced analysis templates, root cause workflow, proactive insight scans, and MCP-vs-LLM responsibility split",
            "text/plain",
            null
        );

        BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler =
        (exchange, readResourceRequest) -> {
            var uri = readResourceRequest.uri().toString();
            if (uri.startsWith("memo://business-analysis-playbook")) {
                var content = new McpSchema.TextResourceContents(uri, "text/plain", businessInsights.getAnalysisPlaybook());
                return new McpSchema.ReadResourceResult(List.of(content));
            }
            if (!uri.startsWith("memo://insights")) {
                return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(uri, "text/plain", "Unknown resource uri")
                ));
            }

            var content = new McpSchema.TextResourceContents(uri, "text/plain", businessInsights.getInsights());
            return new McpSchema.ReadResourceResult(List.of(content));
        };
        var businessInsightsSpec = new McpServerFeatures.SyncResourceSpecification(businessInsightsResource, readHandler);
        var businessAnalysisPlaybookSpec = new McpServerFeatures.SyncResourceSpecification(businessAnalysisPlaybookResource, readHandler);
        return List.of(businessInsightsSpec, businessAnalysisPlaybookSpec);
    }
}
