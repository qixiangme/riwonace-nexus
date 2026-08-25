package com.riwonace.agent.answer

import org.springframework.stereotype.Component

/** 구조화 MCP 결과의 값을 바꾸지 않고 질문에 드러난 표현 단위만 복원한다. */
@Component
class StructuredEvidenceFormatter {
    fun formatSql(question: String, evidence: String): String {
        val count = SINGLE_COUNT.matchEntire(evidence.trim())?.groupValues?.get(1) ?: return evidence
        return "총 $count${countUnit(question)} 입니다."
    }

    private fun countUnit(question: String): String {
        EXPLICIT_COUNT_UNIT.find(question)?.groupValues?.get(1)?.let { return it }
        return when {
            question.contains("건") -> "건"
            question.contains("직원") || question.contains("사람") || question.contains("인원") -> "명"
            question.contains("고객사") -> "곳"
            else -> "개"
        }
    }

    companion object {
        private val SINGLE_COUNT = Regex("(?i)(?:count|count\\(\\*\\))=([0-9]+)")
        private val EXPLICIT_COUNT_UNIT = Regex("몇\\s*(명|개|건|곳)")
    }
}
