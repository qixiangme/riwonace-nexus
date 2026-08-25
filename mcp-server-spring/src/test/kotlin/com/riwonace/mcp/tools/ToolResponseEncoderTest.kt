package com.riwonace.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolResponseEncoderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `출력 예산 이내 응답은 기존 JSON 구조를 유지한다`() {
        val encoder = ToolResponseEncoder(mapper, 200)

        val encoded = encoder.encode(listOf(mapOf("source" to "guide", "text" to "content")))
        val parsed = mapper.readTree(encoded)

        assertTrue(parsed.isArray)
        assertEquals("guide", parsed[0]["source"].asText())
    }

    @Test
    fun `출력 예산 초과 응답은 제한 정보를 담은 유효한 JSON이다`() {
        val encoder = ToolResponseEncoder(mapper, 200)

        val encoded = encoder.encode(mapOf("rows" to listOf("x".repeat(500))))
        val parsed = mapper.readTree(encoded)

        assertTrue(encoded.length <= 200)
        assertEquals("tool_output_too_large", parsed["error"].asText())
        assertTrue(parsed["truncated"].asBoolean())
        assertEquals(200, parsed["maxOutputChars"].asInt())
        assertTrue(parsed["originalChars"].asInt() > 200)
    }

    @Test
    fun `호출별 출력 예산을 지정하면 기본 예산 대신 해당 제한을 사용한다`() {
        val encoder = ToolResponseEncoder(mapper, 200)

        val encoded = encoder.encode(mapOf("schema" to "x".repeat(300)), outputLimit = 500)
        val parsed = mapper.readTree(encoded)

        assertEquals(300, parsed["schema"].asText().length)
    }

    @Test
    fun `예외 응답은 일반화된 메시지를 반환하고 내부 정보를 노출하지 않는다`() {
        val encoder = ToolResponseEncoder(mapper, 500)

        val encoded = encoder.encodeError(
            RuntimeException("column employees.secret_salary does not exist")
        )
        val parsed = mapper.readTree(encoded)

        // 내부 컬럼명이 노출되지 않고 일반화된 메시지 반환
        assertEquals("요청한 데이터를 찾을 수 없습니다.", parsed["error"].asText())
        assertEquals("SQL_NOT_FOUND", parsed["type"].asText())
    }

    @Test
    fun `SQL 문법 오류는 일반화된 메시지를 반환한다`() {
        val encoder = ToolResponseEncoder(mapper, 500)

        val encoded = encoder.encodeError(
            RuntimeException("syntax error at or near \"SELEC\" at position 1")
        )
        val parsed = mapper.readTree(encoded)

        assertEquals("SQL 문법 오류입니다. 질의를 다시 확인해주세요.", parsed["error"].asText())
        assertEquals("SQL_SYNTAX", parsed["type"].asText())
    }

    @Test
    fun `권한 오류는 일반화된 메시지를 반환한다`() {
        val encoder = ToolResponseEncoder(mapper, 500)

        val encoded = encoder.encodeError(
            RuntimeException("permission denied for table employees")
        )
        val parsed = mapper.readTree(encoded)

        assertEquals("데이터베이스 접근 권한이 없습니다.", parsed["error"].asText())
        assertEquals("SQL_PERMISSION", parsed["type"].asText())
    }

    @Test
    fun `알 수 없는 오류는 UNKNOWN 타입을 반환한다`() {
        val encoder = ToolResponseEncoder(mapper, 500)

        val encoded = encoder.encodeError(RuntimeException("unexpected error"))
        val parsed = mapper.readTree(encoded)

        assertEquals("내부 오류가 발생했습니다.", parsed["error"].asText())
        assertEquals("UNKNOWN", parsed["type"].asText())
    }
}
