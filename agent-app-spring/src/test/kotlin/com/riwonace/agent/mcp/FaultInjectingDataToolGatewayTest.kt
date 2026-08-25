package com.riwonace.agent.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FaultInjectingDataToolGatewayTest {
    @Test
    fun `선택한 경로만 예외를 주입하고 다른 경로는 위임한다`() {
        val gateway = FaultInjectingDataToolGateway(FakeGateway(), "SQL", "EXCEPTION")

        assertFailsWith<IllegalStateException> { gateway.runSql("SELECT 1") }
        assertEquals("vector-ok", gateway.vectorSearch("q"))
        assertEquals("graph-ok", gateway.kgSearch("q"))
    }

    @Test
    fun `손상 응답과 빈 결과를 결정적으로 재현한다`() {
        val malformed = FaultInjectingDataToolGateway(FakeGateway(), "VECTOR", "MALFORMED")
        val empty = FaultInjectingDataToolGateway(FakeGateway(), "GRAPH", "EMPTY")

        assertEquals("{malformed", malformed.vectorSearch("q"))
        assertEquals("[]", empty.kgSearch("q"))
    }

    private class FakeGateway : DataToolGateway {
        override fun vectorSearch(query: String, topK: Int) = "vector-ok"
        override fun runSql(sql: String) = "sql-ok"
        override fun kgSearch(query: String) = "graph-ok"
        override fun schema() = "schema-ok"
    }
}
