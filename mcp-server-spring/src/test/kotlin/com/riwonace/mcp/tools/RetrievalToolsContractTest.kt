package com.riwonace.mcp.tools

import org.junit.jupiter.api.Test
import org.springframework.ai.tool.annotation.Tool
import kotlin.test.assertEquals

class RetrievalToolsContractTest {
    @Test
    fun `공개 실행 도구는 과제에서 요구한 세 종류뿐이다`() {
        val toolNames = RetrievalTools::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(Tool::class.java)?.name }
            .toSet()

        assertEquals(setOf("vector_search", "run_sql", "kg_search"), toolNames)
    }
}
