package com.riwonace.agent.sql

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaLinkerTest {

    private val linker = SchemaLinker(ObjectMapper())

    @Test
    fun `db schema Resource의 valueHints 계약에서 질문의 값을 연결한다`() {
        val schema = """
            {
              "tables": {"departments": ["name (character varying)"]},
              "valueHints": {"departments.name": ["플랫폼팀", "기술지원팀"]}
            }
        """.trimIndent()

        val hints = linker.linkEntities(schema, "기술지원팀 직원 목록")

        assertEquals(1, hints.size)
        assertEquals("departments.name = '기술지원팀'", hints.single().suggestion)
    }
}
