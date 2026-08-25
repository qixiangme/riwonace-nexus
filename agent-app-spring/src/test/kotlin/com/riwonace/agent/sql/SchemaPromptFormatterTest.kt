package com.riwonace.agent.sql

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SchemaPromptFormatterTest {

    private val formatter = SchemaPromptFormatter(ObjectMapper())

    @Test
    fun `테이블 외래키 값 힌트의 의미 영역을 분리한다`() {
        val raw = """
            {
              "tables": {
                "clients": ["id (integer)", "region (character varying)"],
                "sales": ["client_id (integer)", "amount (integer)"]
              },
              "foreignKeys": ["sales.client_id -> clients.id"],
              "valueHints": {"clients.region": ["서울", "경기"]}
            }
        """.trimIndent()

        val result = formatter.format(raw)

        assertContains(result, "TABLE clients (id (integer), region (character varying))")
        assertContains(result, "sales.client_id -> clients.id")
        assertContains(result, "clients.region = ['서울', '경기']")
        assertContains(result, "SQL 식별자가 아닌 실제 데이터 값")
        assertFalse(result.contains("TABLE valueHints"))
    }

    @Test
    fun `작은따옴표가 있는 실제 값은 SQL 리터럴 규칙으로 이스케이프한다`() {
        val raw = """{"tables":{"clients":["name (varchar)"]},"valueHints":{"clients.name":["O'Reilly"]}}"""

        assertContains(formatter.format(raw), "'O''Reilly'")
    }

    @Test
    fun `다른 MCP 구현의 비 JSON 응답은 원문으로 폴백한다`() {
        val raw = "TABLE clients(id integer)"

        assertEquals(raw, formatter.format(raw))
    }
}
