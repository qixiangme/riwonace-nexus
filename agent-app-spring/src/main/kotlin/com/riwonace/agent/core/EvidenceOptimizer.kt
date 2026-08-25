package com.riwonace.agent.core

import com.riwonace.agent.context.ContextItem
import com.riwonace.agent.router.Route
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import kotlin.math.min

/**
 * Evidence Budget Optimizer: 토큰 예산 내에서 최적의 증거 조합을 선택한다.
 *
 * 기존 ContextCurator의 단순 예산 기반 선택에서
 * 제약 최적화 문제로 전환:
 *
 * maximize: Σ(relevance × reliability × answerability × novelty)
 * subject to: Σ(token_cost) ≤ budget
 *
 * 그리디 알고리즘으로 근사해를 구한다.
 */
@Component
class EvidenceOptimizer(
    @Value("\${agent.context.budget-chars:2400}")
    private val budgetChars: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 최소 관련도 임계값 */
        const val MIN_RELEVANCE = 0.3

        /** 중복 판정 유사도 임계값 */
        const val DUPLICATE_THRESHOLD = 0.8

        /** 소스별 신뢰도 가중치 */
        val RELIABILITY_WEIGHTS = mapOf(
            "sql" to 1.0,           // SQL 결과는 신뢰도 최고
            "knowledge-graph" to 0.9,
            "document" to 0.7,
            "vector" to 0.6,
        )
    }

    /**
     * 최적화된 증거 선택 결과
     */
    data class OptimizationResult(
        /** 선택된 증거 항목 */
        val selected: List<ContextItem>,
        /** 선택된 항목의 총 가치 */
        val totalValue: Double,
        /** 사용된 예산 (문자 수) */
        val usedBudget: Int,
        /** 제외된 항목 수 */
        val excludedCount: Int,
        /** 중복으로 제거된 항목 수 */
        val deduplicatedCount: Int,
        /** 최적화 사유 */
        val reasoning: String,
    )

    /**
     * 증거 항목들을 최적화하여 예산 내 최적 조합을 반환한다.
     *
     * @param items 후보 증거 항목들
     * @param routes 선택된 라우트들 (라우트별 가중치 적용)
     * @param profile 질문 프로파일 (answerability 계산용)
     */
    fun optimize(
        items: List<ContextItem>,
        routes: List<Route>,
        profile: QueryProfile,
    ): OptimizationResult {
        if (items.isEmpty()) {
            return OptimizationResult(
                selected = emptyList(),
                totalValue = 0.0,
                usedBudget = 0,
                excludedCount = 0,
                deduplicatedCount = 0,
                reasoning = "증거 항목 없음",
            )
        }

        // 1. 최소 관련도 필터링
        val filtered = items.filter { it.score >= MIN_RELEVANCE }
        val filteredOutCount = items.size - filtered.size

        // 2. 중복 제거
        val deduplicated = deduplicateItems(filtered)
        val deduplicatedCount = filtered.size - deduplicated.size

        // 3. 가치 점수 계산
        val scoredItems = deduplicated.map { item ->
            ScoredItem(
                item = item,
                value = calculateValue(item, routes, profile),
                cost = item.text.length,
            )
        }

        // 4. 효율성 기준 정렬 (가치/비용 비율)
        val sortedByEfficiency = scoredItems.sortedByDescending { it.value / it.cost.coerceAtLeast(1) }

        // 5. 그리디 선택
        val selected = mutableListOf<ScoredItem>()
        var remainingBudget = calculateEffectiveBudget(routes)

        for (scored in sortedByEfficiency) {
            if (scored.cost <= remainingBudget) {
                selected.add(scored)
                remainingBudget -= scored.cost
            } else if (selected.isEmpty()) {
                // 첫 항목이 예산 초과면 잘라서라도 포함
                val truncated = scored.copy(
                    item = scored.item.copy(
                        text = scored.item.text.take(remainingBudget - 12) + "…(truncated)",
                    ),
                    cost = remainingBudget,
                )
                selected.add(truncated)
                remainingBudget = 0
                break
            }
        }

        // 6. Lost in the Middle 완화: 3개 이상이면 차상위를 마지막으로
        val finalSelected = applyLostInMiddleMitigation(selected.map { it.item })

        val totalValue = selected.sumOf { it.value }
        val usedBudget = selected.sumOf { it.cost }

        log.info(
            "Evidence 최적화: {} 항목 → {} 선택 (가치={}, 예산={})",
            items.size, finalSelected.size, "%.2f".format(totalValue), usedBudget,
        )

        return OptimizationResult(
            selected = finalSelected,
            totalValue = totalValue,
            usedBudget = usedBudget,
            excludedCount = filteredOutCount + (deduplicated.size - selected.size),
            deduplicatedCount = deduplicatedCount,
            reasoning = buildReasoning(selected.size, filteredOutCount, deduplicatedCount, totalValue),
        )
    }

    /**
     * 증거 항목의 가치 점수 계산
     *
     * value = relevance × reliability × answerability × novelty
     */
    private fun calculateValue(
        item: ContextItem,
        routes: List<Route>,
        profile: QueryProfile,
    ): Double {
        // 1. Relevance: 기존 score 활용
        val relevance = item.score

        // 2. Reliability: 소스별 신뢰도
        val reliability = RELIABILITY_WEIGHTS[item.source] ?: 0.5

        // 3. Answerability: 질문 의도에 부합하는 정도
        val answerability = calculateAnswerability(item, profile)

        // 4. Novelty: 라우트 가중치 (선택된 라우트와 매칭되면 보너스)
        val novelty = calculateNovelty(item, routes)

        return relevance * reliability * answerability * novelty
    }

    /**
     * 답변 가능성 점수 계산
     */
    private fun calculateAnswerability(item: ContextItem, profile: QueryProfile): Double {
        var score = 1.0

        when (profile.intent) {
            QueryIntent.FACTUAL, QueryIntent.AGGREGATION -> {
                // 숫자가 포함된 증거 선호
                if (item.text.contains(Regex("\\d+"))) score *= 1.2
                // SQL 결과 선호
                if (item.source == "sql") score *= 1.3
            }
            QueryIntent.COMPARISON -> {
                // 비교 가능한 데이터 선호
                if (item.text.count { it.isDigit() } >= 2) score *= 1.2
            }
            QueryIntent.EXPLANATION -> {
                // 긴 설명 텍스트 선호
                if (item.text.length > 200) score *= 1.1
            }
            QueryIntent.RELATION -> {
                // 관계 키워드 포함 선호
                if (item.source == "knowledge-graph") score *= 1.3
            }
            QueryIntent.MULTI_HOP -> {
                // 다양한 정보 선호
                score *= 1.0
            }
        }

        return score.coerceAtMost(1.5)
    }

    /**
     * 신규성 점수 계산 (라우트 매칭)
     */
    private fun calculateNovelty(item: ContextItem, routes: List<Route>): Double {
        val sourceToRoute = mapOf(
            "sql" to Route.SQL,
            "knowledge-graph" to Route.GRAPH,
            "document" to Route.VECTOR,
            "vector" to Route.VECTOR,
        )

        val itemRoute = sourceToRoute[item.source]
        return if (itemRoute != null && itemRoute in routes) 1.2 else 0.8
    }

    /**
     * 중복 항목 제거
     */
    private fun deduplicateItems(items: List<ContextItem>): List<ContextItem> {
        if (items.size <= 1) return items

        val result = mutableListOf<ContextItem>()
        val seen = mutableSetOf<String>()

        for (item in items.sortedByDescending { it.score }) {
            val normalized = item.text.replace(Regex("\\s+"), " ").trim()
            val hash = normalized.take(100) // 앞 100자로 중복 판정

            if (hash !in seen) {
                seen.add(hash)
                result.add(item)
            }
        }

        return result
    }

    /**
     * Lost in the Middle 완화: 3개 이상이면 차상위를 마지막으로
     */
    private fun applyLostInMiddleMitigation(items: List<ContextItem>): List<ContextItem> {
        if (items.size < 3) return items

        val result = items.toMutableList()
        val second = result.removeAt(1)
        result.add(second)
        return result
    }

    /**
     * 유효 예산 계산 (SQL 단독이면 절반)
     */
    private fun calculateEffectiveBudget(routes: List<Route>): Int {
        return if (routes == listOf(Route.SQL)) {
            budgetChars / 2
        } else {
            budgetChars
        }
    }

    private fun buildReasoning(
        selectedCount: Int,
        filteredOut: Int,
        deduplicated: Int,
        totalValue: Double,
    ): String {
        val parts = mutableListOf<String>()
        parts += "${selectedCount}개 증거 선택"
        if (filteredOut > 0) parts += "관련도 미달 ${filteredOut}개 제외"
        if (deduplicated > 0) parts += "중복 ${deduplicated}개 제거"
        parts += "총 가치 ${String.format("%.2f", totalValue)}"
        return parts.joinToString(", ")
    }

    private data class ScoredItem(
        val item: ContextItem,
        val value: Double,
        val cost: Int,
    )
}
