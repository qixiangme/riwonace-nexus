package com.riwonace.agent.sql

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * SchemaGraphSQL 영감의 스키마 링킹 컴포넌트.
 *
 * 질문의 엔티티를 스키마 테이블/컬럼과 명시적으로 연결하여
 * NL2SQL 정확도를 향상시킨다.
 *
 * 논문 참고: SchemaGraphSQL (2025) - https://arxiv.org/pdf/2505.18363
 *
 * 예시:
 * - 질문: "플랫폼팀 직원 목록"
 * - 링킹 결과: departments.name = '플랫폼팀' 사용
 */
@Component
class SchemaLinker(
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스키마 JSON에서 valueHints를 추출하고 질문과 매칭한다.
     *
     * @param schemaJson db://schema Resource의 JSON
     * @param question 사용자 질문
     * @return 매칭된 스키마 힌트 목록 (예: "departments.name = '플랫폼팀'")
     */
    @Suppress("UNCHECKED_CAST")
    fun linkEntities(schemaJson: String, question: String): List<SchemaHint> {
        val hints = mutableListOf<SchemaHint>()

        try {
            val schema: Map<String, Any?> = mapper.readValue(
                schemaJson,
                mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
            )

            // db://schema 계약은 최상위 valueHints 객체에 "table.column": [values] 형태다.
            val valueHints = schema["valueHints"] as? Map<String, List<Any?>> ?: return emptyList()
            for ((qualifiedColumn, rawValues) in valueHints) {
                val separator = qualifiedColumn.indexOf('.')
                if (separator <= 0 || separator == qualifiedColumn.lastIndex) continue
                val tableName = qualifiedColumn.substring(0, separator)
                val columnName = qualifiedColumn.substring(separator + 1)
                val values = rawValues.mapNotNull { it?.toString() }

                for (hint in values) {
                    val exact = question.contains(hint, ignoreCase = true)
                    val partial = if (exact) null else findPartialMatch(question, hint)
                    if (exact || partial != null) {
                        hints += SchemaHint(
                            table = tableName,
                            column = columnName,
                            matchedValue = hint,
                            suggestion = "$tableName.$columnName = '${hint.replace("'", "''")}'",
                            confidence = if (exact) 1.0 else 0.8,
                        )
                        log.debug("스키마 링킹: '{}' → {}.{}", hint, tableName, columnName)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("스키마 링킹 실패: {}", e.message)
        }

        // 신뢰도 높은 순으로 정렬
        return hints.sortedByDescending { it.confidence }
    }

    /**
     * 부분 일치를 찾는다.
     * 예: question="영업팀 직원", hint="영업부" → "영업"
     */
    private fun findPartialMatch(question: String, hint: String): String? {
        // 한글 명사 추출 (2글자 이상)
        val koreanWords = Regex("[가-힣]{2,}").findAll(hint).map { it.value }.toList()

        for (word in koreanWords) {
            if (question.contains(word)) {
                return word
            }
        }
        return null
    }

    /**
     * 스키마 힌트를 프롬프트에 포함할 텍스트로 변환한다.
     */
    fun formatHintsForPrompt(hints: List<SchemaHint>): String {
        if (hints.isEmpty()) return ""

        val highConfidence = hints.filter { it.confidence >= 0.9 }
        val lowConfidence = hints.filter { it.confidence < 0.9 }

        val sb = StringBuilder("\n\n[스키마 힌트 - 질문에서 발견된 값]\n")

        if (highConfidence.isNotEmpty()) {
            sb.append("확실: ")
            sb.append(highConfidence.joinToString(", ") { it.suggestion })
            sb.append("\n")
        }

        if (lowConfidence.isNotEmpty()) {
            sb.append("참고: ")
            sb.append(lowConfidence.joinToString(", ") { "'${it.matchedValue}'이 ${it.table}.${it.column}에 있음" })
            sb.append("\n")
        }

        return sb.toString()
    }
}

/**
 * 스키마 링킹 결과
 */
data class SchemaHint(
    val table: String,
    val column: String,
    val matchedValue: String,
    val suggestion: String,
    val confidence: Double = 1.0,
)
