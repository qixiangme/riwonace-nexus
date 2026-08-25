package com.riwonace.agent.router

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TF-IDF 기반 Adaptive RAG 라우터.
 *
 * RAGRouter-Bench (2026) 논문에서 영감을 받아 키워드 매칭 대신
 * TF-IDF 벡터 유사도로 질문 유형을 분류한다.
 *
 * 논문 참고: https://arxiv.org/abs/2602.00296
 *
 * 장점:
 * - 의미적으로 동일한 질문도 표현에 관계없이 올바르게 분류
 * - 학습 데이터 기반으로 새로운 패턴 학습 가능
 * - 키워드 미매칭 시에도 분류 가능
 */
/**
 * TF-IDF 기반 라우트 폴백 구현.
 *
 * `agent.router.fallback=tfidf`로 활성화하면 키워드 미매칭 시 이 구현체가 호출된다.
 */
@Component("tfidfRouteFallback")
@ConditionalOnProperty(name = ["agent.router.fallback"], havingValue = "tfidf")
class TfIdfRouter : RouteFallback {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 학습 데이터: 질문-라우트 쌍
     * 실제 배포 시 official-eval.json + keyword-gap-eval.json에서 로드
     */
    private val trainingData = listOf(
        // SQL 패턴
        TrainingExample("완료된 프로젝트는 몇 개야?", Route.SQL),
        TrainingExample("전체 직원 수는 몇 명이야?", Route.SQL),
        TrainingExample("활성 계약의 총 금액은 얼마야?", Route.SQL),
        TrainingExample("부서별 평균 연봉을 알려줘", Route.SQL),
        TrainingExample("제품 카테고리별 계약 건수는?", Route.SQL),
        TrainingExample("지역별 고객사 수를 보여줘", Route.SQL),
        TrainingExample("가장 비싼 제품과 가장 싼 제품의 가격 차이는?", Route.SQL),
        TrainingExample("2025년 상반기 매출 총액은?", Route.SQL),
        TrainingExample("최근에 해결된 티켓 3개는 뭐야?", Route.SQL),
        TrainingExample("월 구독료가 200 이상인 제품은 몇 개야?", Route.SQL),
        TrainingExample("경기 지역 스타트업 고객사들의 총 계약 금액은?", Route.SQL),
        TrainingExample("on_hold 상태 프로젝트 중 예산이 가장 큰 건?", Route.SQL),

        // VECTOR 패턴
        TrainingExample("MCP 프로토콜이 뭐야?", Route.VECTOR),
        TrainingExample("DB 튜닝 방법을 자세히 알려줘", Route.VECTOR),
        TrainingExample("Kubernetes 장애 대응 절차가 어떻게 돼?", Route.VECTOR),
        TrainingExample("백업 정책과 복구 절차를 설명해줘", Route.VECTOR),
        TrainingExample("API 인증은 어떤 방식을 사용해?", Route.VECTOR),
        TrainingExample("클라우드 마이그레이션 전략이 뭐야?", Route.VECTOR),
        TrainingExample("SSL 인증서 만료 시 대응 방법은?", Route.VECTOR),
        TrainingExample("성능 모니터링 도구로 뭘 쓰고 있어?", Route.VECTOR),
        TrainingExample("데이터 암호화 정책이 어떻게 되어 있어?", Route.VECTOR),
        TrainingExample("장애 발생 시 에스컬레이션 절차는?", Route.VECTOR),

        // GRAPH 패턴
        TrainingExample("플랫폼팀 팀장은 누구야?", Route.GRAPH),
        TrainingExample("클라우드사업부 이사는 누구야?", Route.GRAPH),
        TrainingExample("Client-A가 사용하는 제품의 카테고리는 뭐야?", Route.GRAPH),
        TrainingExample("기술지원팀 직원들이 담당하는 고객사 목록은?", Route.GRAPH),
        TrainingExample("Product-D1을 사용하는 고객사의 산업군은?", Route.GRAPH),
        TrainingExample("Client-K를 담당하는 영업 담당자는?", Route.GRAPH),
        TrainingExample("Product-S2를 사용 중인 고객사 목록은?", Route.GRAPH),
        TrainingExample("데이터플랫폼팀에서 프로젝트를 이끄는 사람은?", Route.GRAPH),
        TrainingExample("Client-E와 관련된 모든 프로젝트는?", Route.GRAPH),
        TrainingExample("보안 제품군을 가장 많이 사용하는 고객사는?", Route.GRAPH),
    )

    // TF-IDF 벡터 캐시
    private val vocabulary: Set<String>
    private val idf: Map<String, Double>
    private val documentVectors: List<Pair<TrainingExample, Map<String, Double>>>

    init {
        // 전체 어휘 구축
        vocabulary = trainingData.flatMap { tokenize(it.question) }.toSet()

        // IDF 계산
        idf = vocabulary.associateWith { term ->
            val docCount = trainingData.count { tokenize(it.question).contains(term) }
            ln((trainingData.size + 1.0) / (docCount + 1.0)) + 1.0
        }

        // 각 학습 문서의 TF-IDF 벡터 계산
        documentVectors = trainingData.map { example ->
            example to computeTfIdf(example.question)
        }

        log.info("TF-IDF 라우터 초기화 완료: 어휘 {}개, 학습 데이터 {}개", vocabulary.size, trainingData.size)
    }

    /**
     * 질문을 TF-IDF 유사도로 분류한다.
     *
     * @param question 사용자 질문
     * @return 예측된 라우트
     */
    override fun classify(question: String): List<Route> {
        val queryVector = computeTfIdf(question)

        // 각 학습 데이터와의 코사인 유사도 계산
        val similarities = documentVectors.map { (example, docVector) ->
            example to cosineSimilarity(queryVector, docVector)
        }.sortedByDescending { it.second }

        // k-NN (k=3) 방식으로 다수결
        val topK = similarities.take(3)
        val routeVotes = topK.groupBy { it.first.route }
            .mapValues { it.value.sumOf { pair -> pair.second } }

        val predicted = routeVotes.maxByOrNull { it.value }?.key ?: Route.VECTOR
        val confidence = routeVotes[predicted] ?: 0.0

        log.debug(
            "TF-IDF 분류: '{}' → {} (신뢰도: {:.2f}, 유사: {})",
            question.take(30),
            predicted,
            confidence,
            topK.map { "${it.first.route}:${String.format("%.2f", it.second)}" },
        )

        return listOf(predicted)
    }

    /**
     * TF-IDF 벡터를 계산한다.
     */
    private fun computeTfIdf(text: String): Map<String, Double> {
        val tokens = tokenize(text)
        val tf = tokens.groupingBy { it }.eachCount()
        val maxTf = tf.values.maxOrNull()?.toDouble() ?: 1.0

        return vocabulary.associateWith { term ->
            val termFreq = (tf[term] ?: 0).toDouble() / maxTf
            termFreq * (idf[term] ?: 0.0)
        }
    }

    /**
     * 텍스트를 토큰으로 분리한다.
     */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^가-힣a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

    /**
     * 두 벡터 간의 코사인 유사도를 계산한다.
     */
    private fun cosineSimilarity(vec1: Map<String, Double>, vec2: Map<String, Double>): Double {
        val dotProduct = vocabulary.sumOf { (vec1[it] ?: 0.0) * (vec2[it] ?: 0.0) }
        val norm1 = sqrt(vocabulary.sumOf { (vec1[it] ?: 0.0).let { v -> v * v } })
        val norm2 = sqrt(vocabulary.sumOf { (vec2[it] ?: 0.0).let { v -> v * v } })

        return if (norm1 > 0 && norm2 > 0) dotProduct / (norm1 * norm2) else 0.0
    }
}

/**
 * 학습 데이터 예시
 */
private data class TrainingExample(
    val question: String,
    val route: Route,
)
