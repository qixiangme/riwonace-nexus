package com.riwonace.agent.core

import com.riwonace.agent.router.Route
import com.riwonace.agent.router.RuleBasedRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 질문 프로파일러: 질문을 분석하여 의도, 복잡도, 불확실성을 추정한다.
 *
 * 복잡도와 불확실성 점수는 후속 단계에서:
 * - 모델 에스컬레이션 (1B → 4B → 7B)
 * - 실행 계획 생성 (병렬 vs 순차)
 * - 답변 검증 수준 결정
 * 에 활용된다.
 */
@Component
class QueryProfiler(
    private val router: RuleBasedRouter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 복잡도 임계값: 이 이상이면 더 큰 모델 필요 */
        const val COMPLEXITY_THRESHOLD_MEDIUM = 0.4
        const val COMPLEXITY_THRESHOLD_HIGH = 0.7

        /** 불확실성 임계값: 이 이상이면 다중 소스 검증 필요 */
        const val UNCERTAINTY_THRESHOLD = 0.5
    }

    /**
     * 질문을 분석하여 프로파일을 반환한다.
     *
     * 휴리스틱 + 선택적 LLM 검증의 2단계 접근:
     * 1. 키워드/패턴 기반 빠른 분류 (지연시간 0)
     * 2. 불확실성이 높으면 LLM으로 재확인 (선택적)
     */
    fun profile(question: String): QueryProfile {
        val intent = classifyIntent(question)
        val complexity = estimateComplexity(question, intent)
        val uncertainty = estimateUncertainty(question, intent)
        val requiredEvidence = inferRequiredEvidence(intent, question)
        // 라우트 선택은 공식·키워드 갭 평가로 검증된 결정적 라우터를 단일 진실 공급원으로 둔다.
        // 프로파일러는 복잡도·불확실성·증거 요구량을 계산하고 실행 라우트를 중복 추론하지 않는다.
        val suggestedRoutes = router.route(question)
        val isMultiHop = intent == QueryIntent.MULTI_HOP || question.contains("그리고") || question.contains("후에")
        val hasDependency = detectDependency(question)

        val profile = QueryProfile(
            intent = intent,
            complexity = complexity,
            uncertainty = uncertainty,
            requiredEvidence = requiredEvidence,
            suggestedRoutes = suggestedRoutes,
            isMultiHop = isMultiHop,
            hasDependency = hasDependency,
        )

        log.info(
            "질문 프로파일링 완료: intent={}, complexity={}, uncertainty={}, routes={}",
            intent, "%.2f".format(complexity), "%.2f".format(uncertainty), suggestedRoutes,
        )
        return profile
    }

    /**
     * 질문 의도 분류 (휴리스틱 기반)
     */
    private fun classifyIntent(question: String): QueryIntent {
        val q = question.lowercase()

        return when {
            // 비교 패턴
            q.contains("비교") || q.contains("차이") || q.contains("vs") ||
                q.contains("보다") || Regex("(더|가장|제일)\\s*(높|낮|많|적|크|작)").containsMatchIn(q) ->
                QueryIntent.COMPARISON

            // 집계/통계 패턴
            q.contains("평균") || q.contains("합계") || q.contains("총") ||
                q.contains("몇 개") || q.contains("몇개") || q.contains("몇 명") ||
                q.contains("개수") || q.contains("수는") || Regex("(전체|모든).*수").containsMatchIn(q) ->
                QueryIntent.AGGREGATION

            // 다단계 추론 패턴
            q.contains("그리고") || q.contains("후에") || q.contains("다음에") ||
                Regex("(상위|하위).*의.*").containsMatchIn(q) ||
                q.count { it == '?' || it == '의' } >= 3 ->
                QueryIntent.MULTI_HOP

            // 관계 탐색 패턴
            q.contains("누가") || q.contains("담당") || q.contains("소속") ||
                q.contains("관련") || q.contains("연결") ->
                QueryIntent.RELATION

            // 설명/개념 패턴
            q.contains("무엇") || q.contains("뭐") || q.contains("왜") ||
                q.contains("어떻게") || q.contains("설명") || q.contains("정의") ||
                q.contains("이란") || q.contains("란") ->
                QueryIntent.EXPLANATION

            // 기본: 단순 사실 조회
            else -> QueryIntent.FACTUAL
        }
    }

    /**
     * 복잡도 추정 (0.0 ~ 1.0)
     *
     * 복잡도 요소:
     * - 의도 유형 (MULTI_HOP > COMPARISON > AGGREGATION > FACTUAL)
     * - 질문 길이
     * - 엔티티 수 추정
     * - 조건절 수
     */
    private fun estimateComplexity(question: String, intent: QueryIntent): Double {
        var score = 0.0

        // 의도별 기본 복잡도
        score += when (intent) {
            QueryIntent.MULTI_HOP -> 0.5
            QueryIntent.COMPARISON -> 0.35
            QueryIntent.RELATION -> 0.3
            QueryIntent.AGGREGATION -> 0.25
            QueryIntent.EXPLANATION -> 0.2
            QueryIntent.FACTUAL -> 0.1
        }

        // 질문 길이 (긴 질문 = 복잡)
        score += (question.length / 200.0).coerceAtMost(0.2)

        // 조건절 수 추정 ("~인", "~한", "~의", 숫자 포함)
        val conditionCount = Regex("(인|한|의|에서|부터|까지|이상|이하|초과|미만|\\d+)").findAll(question).count()
        score += (conditionCount * 0.05).coerceAtMost(0.2)

        // 의문사 수 (다중 질문)
        val questionMarks = question.count { it == '?' }
        if (questionMarks > 1) score += 0.1 * (questionMarks - 1)

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 불확실성 추정 (0.0 ~ 1.0)
     *
     * 불확실성 요소:
     * - 모호한 표현 ("아마", "것 같은", "정도")
     * - 추상적 개념 질문
     * - 시간 범위 불명확
     */
    private fun estimateUncertainty(question: String, intent: QueryIntent): Double {
        var score = 0.0
        val q = question.lowercase()

        // 모호한 표현
        val vagueTerms = listOf("아마", "것 같", "정도", "대략", "약", "거의", "좀", "어느정도", "대충")
        score += vagueTerms.count { q.contains(it) } * 0.15

        // 설명형 질문은 기본 불확실성이 높음
        if (intent == QueryIntent.EXPLANATION) score += 0.2

        // 시간 범위 불명확
        if (!Regex("(\\d{4}|올해|작년|이번|지난|최근)").containsMatchIn(q) &&
            (q.contains("매출") || q.contains("실적") || q.contains("추이"))
        ) {
            score += 0.15
        }

        // 복수 해석 가능한 대명사
        if (q.contains("그것") || q.contains("이것") || q.contains("저것")) {
            score += 0.2
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 필요한 증거 유형 추론
     */
    private fun inferRequiredEvidence(intent: QueryIntent, question: String): Set<EvidenceType> {
        val evidence = mutableSetOf<EvidenceType>()
        val q = question.lowercase()

        // SQL 데이터가 필요한 패턴
        if (intent in listOf(QueryIntent.FACTUAL, QueryIntent.AGGREGATION, QueryIntent.COMPARISON) ||
            Regex("(몇|수|개수|합계|평균|최대|최소|총)").containsMatchIn(q)
        ) {
            evidence += EvidenceType.STRUCTURED_DATA
        }

        // 문서 검색이 필요한 패턴
        if (intent == QueryIntent.EXPLANATION ||
            q.contains("설명") || q.contains("정의") || q.contains("방법") || q.contains("절차")
        ) {
            evidence += EvidenceType.DOCUMENT
        }

        // 관계 탐색이 필요한 패턴
        if (intent == QueryIntent.RELATION ||
            q.contains("누가") || q.contains("관계") || q.contains("연결")
        ) {
            evidence += EvidenceType.GRAPH_RELATION
        }

        // 다단계는 복수 증거 필요
        if (intent == QueryIntent.MULTI_HOP) {
            evidence += EvidenceType.STRUCTURED_DATA
            evidence += EvidenceType.DOCUMENT
        }

        // 최소 하나는 필요
        if (evidence.isEmpty()) {
            evidence += EvidenceType.DOCUMENT
        }

        return evidence
    }

    /**
     * 의존 관계 감지 (SQL 결과로 Vector 검색 등)
     */
    private fun detectDependency(question: String): Boolean {
        // "~의 ~" 패턴이 2회 이상이면 의존 관계 가능성
        val dependencyPatterns = listOf(
            Regex("(결과|데이터).*로.*검색"),
            Regex("(상위|하위).*의.*(정보|내용|설명)"),
            Regex("(해당|그).*에 대한"),
        )
        return dependencyPatterns.any { it.containsMatchIn(question) }
    }
}
