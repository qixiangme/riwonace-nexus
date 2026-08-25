package com.riwonace.agent.answer

import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredEvidenceFormatterTest {
    private val formatter = StructuredEvidenceFormatter()

    @Test
    fun `명시된 count 단위를 보존한다`() {
        assertEquals("총 8개 입니다.", formatter.formatSql("고객사는 몇 개야?", "count=8"))
        assertEquals("총 4명 입니다.", formatter.formatSql("직원은 몇 명이야?", "count=4"))
    }

    @Test
    fun `명시 단위가 없으면 질문의 도메인 명사로 보완한다`() {
        assertEquals("총 5건 입니다.", formatter.formatSql("해결되지 않은 건은?", "count=5"))
        assertEquals("총 3곳 입니다.", formatter.formatSql("등록 고객사 수는?", "count=3"))
    }

    @Test
    fun `단일 count가 아닌 구조화 결과는 변경하지 않는다`() {
        val rows = "name=Client-A, count=3\nname=Client-B, count=2"
        assertEquals(rows, formatter.formatSql("고객사별 계약 수는?", rows))
    }
}
