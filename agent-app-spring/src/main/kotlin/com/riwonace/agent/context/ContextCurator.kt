package com.riwonace.agent.context

import com.riwonace.agent.router.Route
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class ContextItem(
    val source: String,
    val text: String,
    val score: Double,
)

/** 평가 전용 정책. 기본 CURRENT 외 값은 ablation 실행에서만 사용한다. */
enum class ContextPolicy {
    ALL_WITH_HARD_CAP,
    RELEVANCE,
    CURRENT,
    COVERAGE,
}

/**
 * 제한된 컨텍스트 예산에서 근거를 선택한다.
 *
 * 참조 연구의 결론을 "적을수록 좋다"로 과장하지 않는다. Knowledge를 유지하되
 * 관련도·과업·중복·질문 엔티티 coverage를 명시적으로 비교한다. 운영 기본값은 기존 동작을
 * 보존하는 CURRENT이며 나머지 정책은 동일 조건 ablation을 위한 평가 옵션이다.
 */
@Component
class ContextCurator(
    @Value("\${agent.context.budget-chars:2400}") private val budgetChars: Int,
    @Value("\${agent.evaluation.context-policy:CURRENT}") policyName: String = "CURRENT",
) {
    private val policy = ContextPolicy.valueOf(policyName.trim().uppercase())

    init {
        require(budgetChars > 0) { "agent.context.budget-chars는 0보다 커야 합니다." }
    }

    fun curate(items: List<ContextItem>, routes: List<Route>): List<ContextItem> =
        curate(question = "", items = items, routes = routes)

    fun curate(question: String, items: List<ContextItem>, routes: List<Route>): List<ContextItem> {
        if (items.isEmpty()) return emptyList()
        val budget = if (routes == listOf(Route.SQL)) budgetChars / 2 else budgetChars

        return when (policy) {
            ContextPolicy.ALL_WITH_HARD_CAP -> fitInBudget(items, budget)
            ContextPolicy.RELEVANCE -> fitInBudget(
                eligible(items).sortedByDescending { it.score },
                budget,
            )
            ContextPolicy.CURRENT -> current(items, routes, budget)
            ContextPolicy.COVERAGE -> coverage(question, items, routes, budget)
        }
    }

    private fun current(items: List<ContextItem>, routes: List<Route>, budget: Int): List<ContextItem> {
        val ranked = eligible(items)
            .map { it.copy(score = it.score * routeWeight(it.source, routes)) }
            .sortedByDescending { it.score }
        val selected = fitInBudget(ranked, budget).toMutableList()

        // 기존 A/B에서 gemma3:1b에 유리했던 배치를 보존한다.
        if (selected.size >= 3) selected.add(selected.removeAt(1))
        return selected
    }

    /**
     * 별도 모델과 튜닝 계수 없이 marginal evidence utility를 계산한다.
     * 각 항은 [0,1] 범위이며 검증되지 않은 confidence나 answerability 점수는 사용하지 않는다.
     * 복합 질문에서는 경로마다 가장 효용이 큰 근거 하나를 먼저 선택해 한 도구의 유사 문서가
     * 다른 도구 근거의 예산을 잠식하거나 소형 모델을 교란하지 않게 한다.
     */
    private fun coverage(
        question: String,
        items: List<ContextItem>,
        routes: List<Route>,
        budget: Int,
    ): List<ContextItem> {
        val remaining = eligible(items).toMutableList()
        val selected = mutableListOf<ContextItem>()
        var used = 0
        val questionTerms = terms(question)
        val coveredTerms = mutableSetOf<String>()
        val representedRoutes = mutableSetOf<Route>()

        while (remaining.isNotEmpty() && used < budget) {
            val available = remaining.filter { item ->
                val fits = item.text.length <= budget - used || selected.isEmpty()
                val candidateRoute = routeForSource(item.source)
                val respectsCompositionalQuota = routes.size == 1 || candidateRoute !in representedRoutes
                fits && respectsCompositionalQuota
            }
            if (available.isEmpty()) break
            val next = available.maxByOrNull { item ->
                val itemTerms = terms(item.text)
                val questionCoverage = if (questionTerms.isEmpty()) 0.0 else
                    itemTerms.intersect(questionTerms).minus(coveredTerms).size.toDouble() / questionTerms.size
                val redundancy = selected.maxOfOrNull { jaccard(itemTerms, terms(it.text)) } ?: 0.0
                item.score.coerceIn(0.0, 1.0) + routeMatch(item.source, routes) + questionCoverage - redundancy
            } ?: break

            val fitted = fitOne(next, budget - used)
            if (fitted != null) {
                selected += fitted
                used += fitted.text.length
                coveredTerms += terms(fitted.text).intersect(questionTerms)
                representedRoutes += routeForSource(fitted.source)
            }
            remaining.remove(next)
        }
        return selected
    }

    private fun eligible(items: List<ContextItem>): List<ContextItem> =
        items.filter { it.score >= MIN_SCORE }.distinctBy { it.text.take(80) }

    private fun fitInBudget(items: List<ContextItem>, budget: Int): List<ContextItem> {
        val selected = mutableListOf<ContextItem>()
        var used = 0
        for (item in items) {
            val remaining = budget - used
            if (remaining <= 0) break
            if (item.text.length <= remaining) {
                selected += item
                used += item.text.length
            } else if (selected.isEmpty()) {
                fitOne(item, remaining)?.let(selected::add)
            }
        }
        return selected
    }

    private fun fitOne(item: ContextItem, remaining: Int): ContextItem? {
        if (remaining <= 0) return null
        if (item.text.length <= remaining) return item
        val truncated = truncateAtBoundary(item.text, remaining)
        return truncated.takeIf { it.isNotEmpty() }?.let { item.copy(text = it) }
    }

    private fun truncateAtBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= TRUNCATION_MARKER.length) return TRUNCATION_MARKER.take(maxChars)
        val contentLimit = maxChars - TRUNCATION_MARKER.length
        val prefix = text.take(contentLimit)
        val boundary = maxOf(prefix.lastIndexOf('\n'), prefix.lastIndexOf(' '))
        val content = if (boundary >= contentLimit / 2) prefix.take(boundary).trimEnd() else prefix
        return content + TRUNCATION_MARKER
    }

    private fun routeWeight(source: String, routes: List<Route>): Double = when {
        source == "sql" && Route.SQL in routes -> 1.5
        source == "knowledge-graph" && Route.GRAPH in routes -> 1.3
        else -> 1.0
    }

    private fun routeMatch(source: String, routes: List<Route>): Double = when {
        source == "sql" && Route.SQL in routes -> 1.0
        source == "knowledge-graph" && Route.GRAPH in routes -> 1.0
        source !in setOf("sql", "knowledge-graph") && Route.VECTOR in routes -> 1.0
        else -> 0.0
    }

    private fun routeForSource(source: String): Route = when (source) {
        "sql" -> Route.SQL
        "knowledge-graph" -> Route.GRAPH
        else -> Route.VECTOR
    }

    private fun terms(text: String): Set<String> = TERM_REGEX.findAll(text.lowercase())
        .map { normalizeTerm(it.value) }
        .filter { it.length >= 2 }
        .filterNot { it in STOP_TERMS }
        .toSet()

    /** 가벼운 조사 정규화로 `Product-C1의`와 `Product-C1`, `설치에`와 `설치`를 같은 근거로 본다. */
    private fun normalizeTerm(term: String): String {
        val suffix = KOREAN_PARTICLES.firstOrNull { term.length > it.length + 1 && term.endsWith(it) }
        return if (suffix == null) term else term.dropLast(suffix.length)
    }

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() && right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    companion object {
        const val MIN_SCORE = 0.25
        private const val TRUNCATION_MARKER = "\n…(truncated)"
        private val TERM_REGEX = Regex("[가-힣a-z0-9][가-힣a-z0-9_-]{1,}")
        private val STOP_TERMS = setOf("알려줘", "어떻게", "무엇", "관련", "대한", "있는", "중에서")
        private val KOREAN_PARTICLES = listOf("으로", "에서", "에게", "까지", "부터", "처럼", "보다", "의", "에", "은", "는", "이", "가", "을", "를", "과", "와", "도")
    }
}

