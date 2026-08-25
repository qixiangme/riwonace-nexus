package com.riwonace.agent.sql

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeterministicSqlPlannerTest {
    private val planner = DeterministicSqlPlanner()

    @Test
    fun `제품의 단순 속성과 활성 계약 집계를 컴파일한다`() {
        assertEquals(
            "SELECT price_monthly FROM products WHERE name = 'Product-C1'",
            planner.plan("Product-C1의 월 가격 알려줘"),
        )
        assertContains(planner.plan("Product-S1 활성 계약 수 알려줘")!!, "count(*)")
        assertContains(planner.plan("Product-D1 활성 계약 금액 합계 알려줘")!!, "sum(c.amount)")
    }

    @Test
    fun `부서 평균과 고객 매출은 올바른 외래키 조인을 사용한다`() {
        assertContains(planner.plan("기술지원팀 평균 급여")!!, "e.dept_id = d.id")
        assertContains(planner.plan("Client-Q 총 매출")!!, "s.client_id = c.id")
    }

    @Test
    fun `수치 조건으로 제품을 찾는 모호한 복합 질문을 컴파일한다`() {
        assertContains(
            planner.plan("월 120인 cloud 제품 중 CPU 기준을 만족하는 것은?")!!,
            "price_monthly = 120",
        )
        assertContains(
            planner.plan("활성 계약 금액이 22,000인 data 제품")!!,
            "HAVING sum(c.amount) = 22000",
        )
        assertContains(
            planner.plan("활성 계약이 6건인 security 제품")!!,
            "HAVING count(*) = 6",
        )
    }

    @Test
    fun `고신뢰 패턴 밖 질문은 LLM 폴백을 위해 null을 반환한다`() {
        assertNull(planner.plan("최근 복잡한 프로젝트 상태를 분석해줘"))
    }

    @Test
    fun `일반 집계 질문은 스키마 기반 SELECT로 컴파일한다`() {
        assertContains(planner.plan("서울 지역 매출 상위 5개 고객사를 알려줘")!!, "ORDER BY total_sales DESC")
        assertContains(planner.plan("2025년 3분기 총 매출액은 얼마야?")!!, "quarter = '2025-Q3'")
        assertContains(planner.plan("보안 솔루션 카테고리 제품들의 월 평균 매출은?")!!, "p.category = 'security'")
        assertContains(planner.plan("현재 활성 상태인 계약 수는 몇 개야?")!!, "status = 'active'")
        assertContains(planner.plan("평균 연봉이 가장 높은 부서는 어디야?")!!, "ORDER BY average_salary DESC")
    }

    @Test
    fun `미해결 Critical 티켓은 실제 저장값과 미해결 상태만 집계한다`() {
        val sql = planner.plan("아직 해결되지 않은 Critical 티켓은 몇 건이야?")!!

        assertContains(sql, "priority = 'critical'")
        assertContains(sql, "status IN ('open', 'in_progress')")
    }

    @Test
    fun `연도가 포함된 등록 고객사 질문은 연도 경계를 만든다`() {
        val sql = planner.plan("2024년에 등록된 고객사는 몇 곳이야?")!!

        assertContains(sql, "registered_at >= '2024-01-01'")
        assertContains(sql, "registered_at < '2025-01-01'")
    }
}
