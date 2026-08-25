package com.riwonace.agent.router

/**
 * 키워드 규칙에 하나도 안 걸렸을 때만 호출되는 2차 판단.
 * `agent.router.fallback` 값으로 구현체 하나만 활성화된다 (기본은 없음 → VECTOR 기본값 유지).
 */
fun interface RouteFallback {
    fun classify(question: String): List<Route>
}
