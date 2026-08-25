package com.riwonace.agent.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class McpGatewayTest {
    @Test
    fun `스키마는 MCP Resource에서 읽고 프로세스 안에서 재사용한다`() {
        val client = mock(McpSyncClient::class.java)
        val expected = """{"tables":{"employees":["id (integer)"]}}"""
        `when`(client.readResource(any(McpSchema.ReadResourceRequest::class.java))).thenReturn(
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(McpGateway.SCHEMA_URI, "application/json", expected)),
            ),
        )
        val gateway = McpGateway(listOf(client))

        assertEquals(expected, gateway.schema())
        assertEquals(expected, gateway.schema())
        verify(client, times(1)).readResource(any(McpSchema.ReadResourceRequest::class.java))
    }
}
