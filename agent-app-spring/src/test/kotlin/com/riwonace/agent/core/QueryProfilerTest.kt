package com.riwonace.agent.core

import com.riwonace.agent.router.Route
import com.riwonace.agent.router.RuleBasedRouter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryProfilerTest {

    private val profiler = QueryProfiler(RuleBasedRouter())

    @Test
    fun `단순 사실 질문은 FACTUAL 또는 AGGREGATION으로 분류된다`() {
        val profile = profiler.profile("직원 수는?")

        // "수는"이 포함되어 AGGREGATION으로 분류될 수도 있음
        assertTrue(profile.intent in listOf(QueryIntent.FACTUAL, QueryIntent.AGGREGATION))
        assertTrue(profile.complexity < 0.5)
        assertTrue(profile.requiredEvidence.contains(EvidenceType.STRUCTURED_DATA))
    }

    @Test
    fun `집계 질문은 AGGREGATION으로 분류된다`() {
        val profile = profiler.profile("평균 급여는 얼마인가요?")

        assertEquals(QueryIntent.AGGREGATION, profile.intent)
        assertTrue(profile.requiredEvidence.contains(EvidenceType.STRUCTURED_DATA))
        assertTrue(profile.suggestedRoutes.contains(Route.SQL))
    }

    @Test
    fun `비교 질문은 COMPARISON으로 분류된다`() {
        val profile = profiler.profile("A팀과 B팀의 매출 차이는?")

        assertEquals(QueryIntent.COMPARISON, profile.intent)
        assertTrue(profile.complexity >= 0.35) // 비교는 기본 복잡도 0.35
    }

    @Test
    fun `설명 질문은 EXPLANATION으로 분류되고 문서 검색이 필요하다`() {
        val profile = profiler.profile("MCP란 무엇인가?")

        assertEquals(QueryIntent.EXPLANATION, profile.intent)
        assertTrue(profile.requiredEvidence.contains(EvidenceType.DOCUMENT))
        assertTrue(profile.suggestedRoutes.contains(Route.VECTOR))
    }

    @Test
    fun `다단계 질문은 MULTI_HOP으로 분류되고 복잡도가 높다`() {
        val profile = profiler.profile("매출 상위 제품의 장애 이력은?")

        assertEquals(QueryIntent.MULTI_HOP, profile.intent)
        assertTrue(profile.complexity >= 0.5) // MULTI_HOP 기본 복잡도
        assertTrue(profile.isMultiHop)
    }

    @Test
    fun `관계 질문은 RELATION으로 분류되고 그래프 검색이 필요하다`() {
        val profile = profiler.profile("이 프로젝트를 누가 개발했어?")

        assertEquals(QueryIntent.RELATION, profile.intent)
        assertTrue(profile.requiredEvidence.contains(EvidenceType.GRAPH_RELATION))
        assertTrue(profile.suggestedRoutes.contains(Route.GRAPH))
    }

    @Test
    fun `프로파일러는 검증된 결정적 라우터의 경로를 보존한다`() {
        val vector = profiler.profile("백업 정책은 어떻게 되어 있어?")
        val graph = profiler.profile("플랫폼팀 팀장은 누구야?")

        assertTrue(Route.VECTOR in vector.suggestedRoutes)
        assertTrue(Route.GRAPH in graph.suggestedRoutes)
        assertTrue(Route.SQL !in vector.suggestedRoutes)
    }

    @Test
    fun `모호한 표현이 있으면 불확실성이 높다`() {
        val profile = profiler.profile("아마 작년쯤 매출 정도가 어느정도였나요?")

        assertTrue(profile.uncertainty >= 0.3) // 모호한 표현들로 인해
    }

    @Test
    fun `긴 질문은 복잡도가 높다`() {
        val shortProfile = profiler.profile("직원 수는?")
        val longProfile = profiler.profile(
            "2023년 1분기에 A 부서에서 진행한 프로젝트 중 " +
                "예산이 1억 이상이고 기간이 3개월 이상인 프로젝트의 " +
                "담당자와 최종 결과를 알려주세요.",
        )

        assertTrue(longProfile.complexity > shortProfile.complexity)
    }

    @Test
    fun `의존성 패턴이 감지되면 hasDependency가 true다`() {
        val profile = profiler.profile("상위 5개 제품의 상세 정보를 알려줘")

        assertTrue(profile.hasDependency)
    }
}
