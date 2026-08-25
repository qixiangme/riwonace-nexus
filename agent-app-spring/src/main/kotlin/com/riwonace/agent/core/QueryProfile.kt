package com.riwonace.agent.core

import com.riwonace.agent.router.Route

/**
 * 질문 프로파일링 결과.
 *
 * 질문의 의도, 복잡도, 불확실성을 분석하여
 * 후속 실행 계획과 모델 선택에 활용한다.
 */
data class QueryProfile(
    /** 주요 의도: factual, aggregation, comparison, explanation, multi-hop */
    val intent: QueryIntent,
    /** 복잡도 점수 (0.0~1.0) - 높을수록 더 큰 모델 필요 */
    val complexity: Double,
    /** 불확실성 점수 (0.0~1.0) - 라우팅/답변 확신도 */
    val uncertainty: Double,
    /** 필요한 증거 유형 */
    val requiredEvidence: Set<EvidenceType>,
    /** 추정 라우트 */
    val suggestedRoutes: List<Route>,
    /** multi-hop 여부 */
    val isMultiHop: Boolean,
    /** 의존 관계가 있는 질문인지 (SQL 결과로 Vector 검색 등) */
    val hasDependency: Boolean,
)

enum class QueryIntent {
    /** 단순 사실 조회: "직원 수는?" */
    FACTUAL,
    /** 집계/통계: "평균 급여는?" */
    AGGREGATION,
    /** 비교: "A와 B의 차이는?" */
    COMPARISON,
    /** 설명/개념: "MCP란 무엇인가?" */
    EXPLANATION,
    /** 다단계 추론: "매출 상위 제품의 장애 이력은?" */
    MULTI_HOP,
    /** 관계 탐색: "누가 개발했어?" */
    RELATION,
}

enum class EvidenceType {
    STRUCTURED_DATA,  // SQL 결과
    DOCUMENT,         // 벡터 검색 문서
    GRAPH_RELATION,   // 지식 그래프 관계
}
