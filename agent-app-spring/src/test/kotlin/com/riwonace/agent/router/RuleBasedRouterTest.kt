package com.riwonace.agent.router

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RuleBasedRouterTest {

    private val router = RuleBasedRouter()

    @Test
    fun `집계 질문은 SQL로 라우팅된다`() {
        assertContains(router.route("플랫폼팀 직원의 평균 급여는 얼마야?"), Route.SQL)
        assertContains(router.route("가장 비싼 제품이 뭐야?"), Route.SQL)
    }

    @Test
    fun `개념 질문은 VECTOR로 라우팅된다`() {
        assertEquals(listOf(Route.VECTOR), router.route("MCP가 뭔가요? 설명해줘"))
    }

    @Test
    fun `관계 질문은 GRAPH로 라우팅된다`() {
        assertContains(router.route("air는 누가 개발했어?"), Route.GRAPH)
        assertContains(router.route("MCP와 RAG는 무슨 사이야?"), Route.GRAPH)
    }

    @Test
    fun `복합 질문은 여러 라우트로 병렬 처리된다 (MCP Parallel)`() {
        val routes = router.route("pgvector 개념을 설명하고 관련된 제품 목록도 알려줘")
        assertContains(routes, Route.SQL)
        assertContains(routes, Route.VECTOR)
    }

    @Test
    fun `가격과 설치를 함께 묻는 질문은 SQL과 VECTOR를 선택한다`() {
        assertEquals(
            listOf(Route.SQL, Route.VECTOR),
            router.route("월 가격과 설치에 필요한 컨테이너 도구를 함께 알려줘"),
        )
    }

    @Test
    fun `가격과 실제 이용 고객을 함께 묻는 질문은 SQL과 GRAPH를 선택한다`() {
        assertEquals(
            listOf(Route.SQL, Route.GRAPH),
            router.route("월 가격과 실제 이용 고객을 함께 알려줘"),
        )
    }

    @Test
    fun `제품명만으로 SQL을 과잉 선택하지 않는다`() {
        assertEquals(
            listOf(Route.GRAPH, Route.VECTOR),
            router.route("Product-C1 설치 방식과 이 제품을 실제 사용하는 고객사를 알려줘"),
        )
    }

    @Test
    fun `수치 조건 제품과 문서 및 사용 관계는 세 경로를 선택한다`() {
        assertEquals(
            listOf(Route.SQL, Route.GRAPH, Route.VECTOR),
            router.route("월 120인 cloud 제품 중 CPU 62%이며 Client-A가 사용하는 것은?"),
        )
    }

    @Test
    fun `출시 상태와 백업은 SQL과 VECTOR를 선택한다`() {
        assertEquals(
            listOf(Route.SQL, Route.VECTOR),
            router.route("출시 상태와 백업 실행 시각 및 보관일을 알려줘"),
        )
    }

    @Test
    fun `어떤 규칙에도 안 걸리면 VECTOR가 기본값이다`() {
        assertEquals(listOf(Route.VECTOR), router.route("안녕하세요"))
    }

    @Test
    fun `fallback이 있으면 키워드 미매칭 시 위임한다`() {
        val withFallback = RuleBasedRouter(fallback = RouteFallback { listOf(Route.GRAPH) })
        assertEquals(listOf(Route.GRAPH), withFallback.route("키워드가 하나도 안 걸리는 질문"))
    }

    @Test
    fun `키워드가 하나라도 걸리면 fallback은 호출되지 않는다`() {
        var called = false
        val withFallback = RuleBasedRouter(fallback = RouteFallback { called = true; listOf(Route.GRAPH) })
        withFallback.route("가장 비싼 제품이 뭐야?")
        assertEquals(false, called)
    }

    @Test
    fun `복합 접속 표현에서 단일 규칙 결과를 의미 폴백의 다중 경로로 보강한다`() {
        val withFallback = RuleBasedRouter(fallback = RouteFallback { listOf(Route.SQL, Route.GRAPH) })
        assertEquals(
            listOf(Route.SQL, Route.GRAPH),
            withFallback.route("월 가격과 실제 이용 고객을 함께 알려줘"),
        )
    }
}
