package com.riwonace.agent.router

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.math.sqrt

/**
 * 키워드가 없을 때 임베딩 코사인 유사도로 라우트를 고른다.
 * 같은 질문 → 같은 임베딩 → 같은 결과이므로 여전히 결정적이다 (LLM 생성이 아님).
 * 라우트별 예시는 sqlKeywords/graphKeywords/vectorKeywords에 있는 단어를 피해서
 * 키워드 목록과는 다른 경로로 의미를 구분한다는 걸 보여준다.
 */
@Component
@ConditionalOnProperty(name = ["agent.router.fallback"], havingValue = "embedding")
class EmbeddingRouteFallback(private val embeddingModel: EmbeddingModel) : RouteFallback {

    private val examples = mapOf(
        Route.SQL to listOf(
            "이번 달에 생긴 돈의 규모를 알고 싶다",
            "부서마다 사람이 몇씩 있는지 세어봐",
            "가장 비싼 순서로 나열해줘",
            "특정 기간에 새로 생긴 건수를 세고 싶다",
        ),
        Route.VECTOR to listOf(
            "이게 왜 필요한지 배경을 이해하고 싶다",
            "문제가 생겼을 때 어떻게 대응해야 하는지 절차를 찾고 싶다",
            "설치나 설정 방법을 문서에서 찾아줘",
            "정책이나 가이드라인의 자세한 내용을 알고 싶다",
        ),
        Route.GRAPH to listOf(
            "이 사람과 저 물건이 서로 어떻게 이어져 있는지 알고 싶다",
            "누가 이 프로젝트를 책임지고 있는지 알고 싶다",
            "어느 조직에 속해 있는 사람인지 알고 싶다",
            "이걸 실제로 쓰고 있는 곳이 어디인지 알고 싶다",
        ),
    )

    // 예시 임베딩은 최초 호출 시 한 번만 계산해 캐시한다.
    private val exampleVectors: Map<Route, List<FloatArray>> by lazy {
        examples.mapValues { (_, texts) -> texts.map { embeddingModel.embed(it) } }
    }

    override fun classify(question: String): List<Route> {
        val q = embeddingModel.embed(question)
        return listOf(exampleVectors.entries
            .map { (route, vectors) -> route to vectors.maxOf { cosine(q, it) } }
            .maxBy { it.second }
            .first)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-9)
    }
}
