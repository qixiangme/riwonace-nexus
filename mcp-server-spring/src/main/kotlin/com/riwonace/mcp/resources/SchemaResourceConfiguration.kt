package com.riwonace.mcp.resources

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** MCP Tool(Action)과 Resource(Knowledge)를 구분해 서버의 공개 계약을 구성한다. */
@Configuration
class SchemaResourceConfiguration {
    @Bean
    fun databaseSchemaResource(schemaCatalog: SchemaCatalog): List<McpServerFeatures.SyncResourceSpecification> {
        val resource = McpSchema.Resource(
            SCHEMA_URI,
            "database-schema",
            "NL2SQL 생성에 필요한 테이블·컬럼·외래키·저카디널리티 값 힌트",
            "application/json",
            null,
        )
        return listOf(
            McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
                McpSchema.ReadResourceResult(
                    listOf(McpSchema.TextResourceContents(request.uri(), "application/json", schemaCatalog.readJson())),
                )
            },
        )
    }

    companion object {
        const val SCHEMA_URI = "db://schema"
    }
}
