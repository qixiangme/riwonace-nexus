package com.riwonace.agent.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FewShotSelectorTest {

    private val selector = FewShotSelector()

    @Test
    fun `분기 매출 질문에는 실제 quarter 컬럼 예시를 선택한다`() {
        val selected = selector.selectExamples("2027년 4분기 총 매출은?", topK = 1)

        assertEquals("quarter-sales", selected.single().pattern)
        assertTrue(selected.single().sql.contains("FROM sales"))
        assertTrue(selected.single().sql.contains("quarter"))
    }

    @Test
    fun `제품별 계약 질문에는 실제 외래키 조인 예시를 선택한다`() {
        val selected = selector.selectExamples("제품별 계약 금액 합계를 보여줘", topK = 1)

        assertEquals("product-contract-sum", selected.single().pattern)
        assertTrue(selected.single().sql.contains("c.product_id = p.id"))
    }

    @Test
    fun `티켓 예시는 실제 테이블 이름을 사용한다`() {
        val selected = selector.selectExamples("해결되지 않은 티켓 수는?", topK = 1)

        assertEquals("unresolved-ticket-count", selected.single().pattern)
        assertTrue(selected.single().sql.contains("support_tickets"))
    }
}
