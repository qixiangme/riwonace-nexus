package com.riwonace.agent.sql

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** MCP의 기계 판독용 스키마 JSON을 소형 모델이 오인하지 않는 SQL 지향 표현으로 바꾼다. */
@Component
class SchemaPromptFormatter(private val mapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 원본 JSON의 테이블·외래키·값 힌트를 서로 다른 의미 영역으로 명시한다. */
    fun format(rawSchema: String): String =
        try {
            val root = mapper.readTree(rawSchema)
            val tables = root.path("tables")
            require(tables.isObject) { "tables가 JSON 객체가 아닙니다." }

            buildString {
                appendLine("DATABASE SCHEMA")
                tables.properties().forEach { (table, columns) ->
                    append("TABLE ").append(table).append(" (")
                    append(columns.joinToString(", ") { it.asText() })
                    appendLine(")")
                }

                val foreignKeys = root.path("foreignKeys")
                if (foreignKeys.isArray && !foreignKeys.isEmpty) {
                    appendLine("FOREIGN KEYS")
                    foreignKeys.forEach { appendLine("- ${it.asText()}") }
                }

                val valueHints = root.path("valueHints")
                if (valueHints.isObject && !valueHints.isEmpty) {
                    appendLine("KNOWN COLUMN VALUES (SQL 식별자가 아닌 실제 데이터 값)")
                    valueHints.properties().forEach { (column, values) ->
                        append("- ").append(column).append(" = [")
                        append(values.joinToString(", ") { "'${escapeLiteral(it.asText())}'" })
                        appendLine("]")
                    }
                }

                appendLine("RULES")
                appendLine("- TABLE에 선언된 테이블과 컬럼만 SQL 식별자로 사용한다.")
                appendLine("- KNOWN COLUMN VALUES의 값은 해당 컬럼의 비교값으로만 사용한다.")
                append("- JOIN은 FOREIGN KEYS에 선언된 관계만 사용한다.")
            }
        } catch (e: Exception) {
            // 구버전 또는 다른 MCP 구현의 스키마도 계속 사용할 수 있도록 원문으로 폴백한다.
            log.warn("스키마 프롬프트 구조화 실패, 원본 사용: {}", e.message)
            rawSchema
        }

    private fun escapeLiteral(value: String): String = value.replace("'", "''")
}
