package com.riwonace.mcp.resources

import com.fasterxml.jackson.databind.ObjectMapper
import com.riwonace.mcp.tools.ToolResponseEncoder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * DB 메타데이터를 NL2SQL용 Knowledge(K)로 구성한다.
 *
 * 스키마는 실행 가능한 Action이 아니므로 Tool 목록을 늘리지 않고 MCP Resource로 제공한다.
 * 도메인 값과 외래키만 선별해 소형 모델에 불필요한 컨텍스트를 보내지 않는다.
 */
@Component
class JdbcSchemaCatalog(
    private val jdbc: JdbcTemplate,
    mapper: ObjectMapper,
) : SchemaCatalog {
    private val responseEncoder = ToolResponseEncoder(mapper, MAX_SCHEMA_OUTPUT_CHARS)

    override fun readJson(): String =
        try {
            responseEncoder.encode(buildSnapshot())
        } catch (e: Exception) {
            responseEncoder.encodeError(e)
        }

    private fun buildSnapshot(): Map<String, Any> {
        val columns = jdbc.queryForList(
            """
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name NOT IN ('vector_store', 'kg_triples', 'document_chunks')
            ORDER BY table_name, ordinal_position
            """.trimIndent(),
        )
        val tables = columns.groupBy({ it["table_name"] as String }) {
            "${it["column_name"]} (${it["data_type"]})"
        }
        return mapOf(
            "tables" to tables,
            "foreignKeys" to foreignKeys(),
            "valueHints" to valueHints(columns),
        )
    }

    private fun foreignKeys(): List<String> =
        jdbc.queryForList(
            """
            SELECT tc.table_name AS source_table,
                   kcu.column_name AS source_column,
                   ccu.table_name AS target_table,
                   ccu.column_name AS target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.constraint_schema = kcu.constraint_schema
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
             AND tc.constraint_schema = ccu.constraint_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = 'public'
            ORDER BY tc.table_name, kcu.ordinal_position
            """.trimIndent(),
        ).map {
            "${it["source_table"]}.${it["source_column"]} -> " +
                "${it["target_table"]}.${it["target_column"]}"
        }

    private fun valueHints(columns: List<Map<String, Any>>): Map<String, List<String>> {
        val hints = linkedMapOf<String, List<String>>()
        columns
            .filter { (it["data_type"] as String).contains("char") }
            .forEach {
                val table = it["table_name"] as String
                val column = it["column_name"] as String
                val values = jdbc.queryForList(
                    "SELECT DISTINCT $column FROM $table WHERE $column IS NOT NULL LIMIT ${MAX_HINT_VALUES + 1}",
                    String::class.java,
                )
                if (values.size in 1..MAX_HINT_VALUES) hints["$table.$column"] = values
            }
        return hints
    }

    companion object {
        const val MAX_SCHEMA_OUTPUT_CHARS = 8000
        const val MAX_HINT_VALUES = 12
    }
}
