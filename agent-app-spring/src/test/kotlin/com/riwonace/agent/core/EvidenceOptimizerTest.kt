package com.riwonace.agent.core

import com.riwonace.agent.context.ContextItem
import com.riwonace.agent.router.Route
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceOptimizerTest {

    private val optimizer = EvidenceOptimizer(budgetChars = 500)

    @Test
    fun `관련도 미달 항목은 제외된다`() {
        val items = listOf(
            ContextItem("a", "높은 관련도", 0.8),
            ContextItem("b", "낮은 관련도", 0.1), // MIN_RELEVANCE=0.3 미만
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.VECTOR), profile)

        assertEquals(1, result.selected.size)
        assertEquals("a", result.selected.first().source)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `예산 내에서 가치가 높은 항목이 우선 선택된다`() {
        val items = listOf(
            ContextItem("low", "x".repeat(200), 0.5),
            ContextItem("high", "y".repeat(200), 0.9),
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.VECTOR), profile)

        // 관련도가 높은 "high"가 먼저 선택됨
        assertEquals("high", result.selected.first().source)
    }

    @Test
    fun `중복 항목은 제거된다`() {
        val duplicateText = "중복된 문서 내용입니다."
        val items = listOf(
            ContextItem("a", duplicateText, 0.9),
            ContextItem("b", duplicateText, 0.8),
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.VECTOR), profile)

        assertEquals(1, result.selected.size)
        assertEquals(1, result.deduplicatedCount)
    }

    @Test
    fun `SQL 라우트에서 sql 소스가 신뢰도 가중된다`() {
        val items = listOf(
            ContextItem("document", "문서 내용", 0.9),
            ContextItem("sql", "SQL 결과 123", 0.7),
        )
        val profile = createProfile(intent = QueryIntent.FACTUAL)

        val result = optimizer.optimize(items, listOf(Route.SQL), profile)

        // SQL 결과가 신뢰도 가중치로 인해 더 높은 가치
        assertEquals("sql", result.selected.first().source)
    }

    @Test
    fun `첫 항목이 예산 초과면 잘라서라도 포함한다`() {
        val items = listOf(
            ContextItem("oversize", "x".repeat(1000), 0.9),
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.VECTOR), profile)

        assertEquals(1, result.selected.size)
        assertTrue(result.selected.first().text.length <= 500)
        assertTrue(result.selected.first().text.endsWith("…(truncated)"))
    }

    @Test
    fun `SQL 단독 라우트는 절반 예산을 사용한다`() {
        val items = listOf(
            ContextItem("sql1", "s".repeat(200), 0.9),
            ContextItem("sql2", "s".repeat(200), 0.8),
            ContextItem("sql3", "s".repeat(200), 0.7),
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.SQL), profile)

        // 예산 500의 절반(250) 이내
        assertTrue(result.usedBudget <= 250)
    }

    @Test
    fun `3개 이상이면 Lost in the Middle 완화가 적용된다`() {
        val items = listOf(
            ContextItem("1st", "a".repeat(50), 0.9),
            ContextItem("2nd", "b".repeat(50), 0.8),
            ContextItem("3rd", "c".repeat(50), 0.7),
        )
        val profile = createProfile()

        val result = optimizer.optimize(items, listOf(Route.VECTOR), profile)

        // 1st가 처음, 2nd가 마지막
        assertEquals("1st", result.selected.first().source)
        assertEquals("2nd", result.selected.last().source)
    }

    @Test
    fun `빈 목록은 빈 결과를 반환한다`() {
        val result = optimizer.optimize(emptyList(), listOf(Route.VECTOR), createProfile())

        assertTrue(result.selected.isEmpty())
        assertEquals(0.0, result.totalValue)
    }

    @Test
    fun `숫자가 필요한 질문에 숫자 포함 증거가 선택된다`() {
        val items = listOf(
            ContextItem("text", "설명만 있는 문서입니다.", 0.8),
            ContextItem("data", "총 매출은 1,234,567원입니다.", 0.7),
        )
        val profile = createProfile(intent = QueryIntent.AGGREGATION)

        val result = optimizer.optimize(items, listOf(Route.SQL), profile)

        // 두 항목 모두 선택되어야 함
        assertTrue(result.selected.isNotEmpty())
        // 숫자가 포함된 "data"가 포함되어 있어야 함
        assertTrue(result.selected.any { it.source == "data" })
    }

    private fun createProfile(
        intent: QueryIntent = QueryIntent.FACTUAL,
    ): QueryProfile {
        return QueryProfile(
            intent = intent,
            complexity = 0.3,
            uncertainty = 0.2,
            requiredEvidence = setOf(EvidenceType.STRUCTURED_DATA),
            suggestedRoutes = listOf(Route.SQL),
            isMultiHop = false,
            hasDependency = false,
        )
    }
}
