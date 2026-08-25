package com.riwonace.mcp.resources

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

class SchemaResourceConfigurationTest {
    @Test
    fun `DB 스키마는 도구가 아니라 JSON 리소스로 제공된다`() {
        val expected = """{"tables":{"employees":["id (integer)"]}}"""
        val specification = SchemaResourceConfiguration()
            .databaseSchemaResource(SchemaCatalog { expected })
            .single()

        val result = specification.readHandler().apply(
            mock(McpSyncServerExchange::class.java),
            McpSchema.ReadResourceRequest(SchemaResourceConfiguration.SCHEMA_URI),
        )
        val content = result.contents().single() as McpSchema.TextResourceContents

        assertEquals("db://schema", specification.resource().uri())
        assertEquals("application/json", content.mimeType())
        assertEquals(expected, content.text())
    }
}
