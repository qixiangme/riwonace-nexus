package com.riwonace.agent.core

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Recovery Policy: 실패 유형별 복구 전략을 결정한다.
 *
 * 실패를 분류하고 적절한 복구 전략을 선택:
 * - ROUTE_MISS → 대체 라우트로 폴백
 * - RETRIEVAL_EMPTY → 검색 쿼리 완화
 * - SQL_SCHEMA_ERROR → 스키마 재확인 후 재생성
 * - SQL_SYNTAX_ERROR → 오류 피드백 기반 재생성
 * - MCP_TIMEOUT → 캐시 응답 또는 부분 응답
 * - EVIDENCE_CONFLICT → 신뢰도 기반 선택
 */
@Component
class RecoveryPolicy {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 복구 전략
     */
    data class RecoveryStrategy(
        /** 전략 유형 */
        val type: StrategyType,
        /** 최대 재시도 횟수 */
        val maxRetries: Int,
        /** 재시도 간 대기 시간 (ms) */
        val backoffMs: Long,
        /** 폴백 라우트 (있으면) */
        val fallbackRoutes: List<com.riwonace.agent.router.Route>,
        /** 쿼리 변환 함수 (있으면) */
        val queryTransform: ((String) -> String)?,
        /** 추가 컨텍스트 */
        val additionalContext: Map<String, Any>,
    )

    enum class StrategyType {
        /** 동일 쿼리로 재시도 */
        RETRY_SAME,
        /** 변형된 쿼리로 재시도 */
        RETRY_MODIFIED,
        /** 다른 라우트로 폴백 */
        FALLBACK_ROUTE,
        /** 캐시된 응답 사용 */
        USE_CACHE,
        /** 부분 응답 생성 */
        PARTIAL_RESPONSE,
        /** 사용자에게 명확화 요청 */
        ASK_CLARIFICATION,
        /** 복구 불가 - 에러 응답 */
        GIVE_UP,
    }

    /**
     * 실패 분류 결과
     */
    data class FailureClassification(
        /** 실패 유형 */
        val type: FailureType,
        /** 심각도 (1-5) */
        val severity: Int,
        /** 복구 가능 여부 */
        val recoverable: Boolean,
        /** 분류 근거 */
        val evidence: String,
    )

    /**
     * 오류를 분류한다.
     */
    fun classifyFailure(error: String, route: com.riwonace.agent.router.Route): FailureClassification {
        val errorLower = error.lowercase()

        return when {
            // SQL 관련 오류
            errorLower.contains("relation") && errorLower.contains("does not exist") ->
                FailureClassification(FailureType.SQL_SCHEMA_ERROR, 3, true, "테이블/컬럼 미존재")

            errorLower.contains("column") && errorLower.contains("does not exist") ->
                FailureClassification(FailureType.SQL_SCHEMA_ERROR, 3, true, "컬럼 미존재")

            errorLower.contains("syntax error") || errorLower.contains("parse error") ->
                FailureClassification(FailureType.SQL_SYNTAX_ERROR, 2, true, "SQL 문법 오류")

            errorLower.contains("permission denied") ->
                FailureClassification(FailureType.SQL_SCHEMA_ERROR, 4, false, "권한 부족")

            // 검색 관련 오류
            errorLower.contains("no results") || errorLower.contains("not found") ||
                errorLower.contains("0행") || errorLower.contains("empty") ->
                FailureClassification(FailureType.RETRIEVAL_EMPTY, 2, true, "검색 결과 없음")

            // 연결 오류
            errorLower.contains("timeout") || errorLower.contains("timed out") ->
                FailureClassification(FailureType.MCP_TIMEOUT, 3, true, "타임아웃")

            errorLower.contains("connection") || errorLower.contains("refused") ||
                errorLower.contains("unreachable") ->
                FailureClassification(FailureType.MCP_CONNECTION_ERROR, 4, true, "연결 실패")

            // 증거 충돌
            errorLower.contains("conflict") || errorLower.contains("inconsistent") ->
                FailureClassification(FailureType.EVIDENCE_CONFLICT, 2, true, "증거 충돌")

            // 라우팅 실패
            route == com.riwonace.agent.router.Route.VECTOR && errorLower.contains("no match") ->
                FailureClassification(FailureType.ROUTE_MISS, 2, true, "라우트 미매칭")

            // 알 수 없는 오류
            else ->
                FailureClassification(FailureType.UNKNOWN, 3, true, "분류 불가: ${error.take(50)}")
        }
    }

    /**
     * 실패에 대한 복구 전략을 결정한다.
     */
    fun determineStrategy(
        classification: FailureClassification,
        currentRoute: com.riwonace.agent.router.Route,
        retryCount: Int,
        originalQuery: String,
    ): RecoveryStrategy {
        if (!classification.recoverable || retryCount >= 3) {
            return createGiveUpStrategy(classification)
        }

        return when (classification.type) {
            FailureType.ROUTE_MISS -> createRouteFailbackStrategy(currentRoute)

            FailureType.RETRIEVAL_EMPTY -> createRetryWithRelaxedQuery(originalQuery, retryCount)

            FailureType.SQL_SCHEMA_ERROR -> createSchemaRetryStrategy(originalQuery)

            FailureType.SQL_SYNTAX_ERROR -> RecoveryStrategy(
                type = StrategyType.RETRY_MODIFIED,
                maxRetries = 2,
                backoffMs = 0,
                fallbackRoutes = emptyList(),
                queryTransform = null, // 외부에서 에러 피드백과 함께 처리
                additionalContext = mapOf("error" to classification.evidence),
            )

            FailureType.MCP_TIMEOUT -> createTimeoutStrategy(currentRoute, retryCount)

            FailureType.MCP_CONNECTION_ERROR -> RecoveryStrategy(
                type = StrategyType.FALLBACK_ROUTE,
                maxRetries = 1,
                backoffMs = 1000,
                fallbackRoutes = getFallbackRoutes(currentRoute),
                queryTransform = null,
                additionalContext = mapOf("reason" to "MCP 서버 연결 실패"),
            )

            FailureType.EVIDENCE_CONFLICT -> RecoveryStrategy(
                type = StrategyType.PARTIAL_RESPONSE,
                maxRetries = 0,
                backoffMs = 0,
                fallbackRoutes = emptyList(),
                queryTransform = null,
                additionalContext = mapOf("action" to "신뢰도 높은 증거만 사용"),
            )

            FailureType.UNKNOWN -> if (retryCount == 0) {
                RecoveryStrategy(
                    type = StrategyType.RETRY_SAME,
                    maxRetries = 1,
                    backoffMs = 500,
                    fallbackRoutes = emptyList(),
                    queryTransform = null,
                    additionalContext = emptyMap(),
                )
            } else {
                createGiveUpStrategy(classification)
            }
        }
    }

    private fun createRouteFailbackStrategy(currentRoute: com.riwonace.agent.router.Route): RecoveryStrategy {
        val fallbacks = getFallbackRoutes(currentRoute)
        return RecoveryStrategy(
            type = StrategyType.FALLBACK_ROUTE,
            maxRetries = fallbacks.size,
            backoffMs = 0,
            fallbackRoutes = fallbacks,
            queryTransform = null,
            additionalContext = mapOf("reason" to "라우트 미매칭으로 대체 경로 시도"),
        )
    }

    private fun createRetryWithRelaxedQuery(originalQuery: String, retryCount: Int): RecoveryStrategy {
        val transforms = listOf<(String) -> String>(
            // 1차: 구체적 조건 제거
            { q -> q.replace(Regex("\\d{4}년|올해|작년|이번|지난"), "") },
            // 2차: 더 일반적인 질문으로
            { q -> q.replace(Regex("(정확히|구체적으로|자세히)"), "").trim() },
        )

        val transform = transforms.getOrNull(retryCount)

        return RecoveryStrategy(
            type = StrategyType.RETRY_MODIFIED,
            maxRetries = 2,
            backoffMs = 0,
            fallbackRoutes = emptyList(),
            queryTransform = transform,
            additionalContext = mapOf("action" to "검색 조건 완화"),
        )
    }

    private fun createSchemaRetryStrategy(originalQuery: String): RecoveryStrategy {
        return RecoveryStrategy(
            type = StrategyType.RETRY_MODIFIED,
            maxRetries = 2,
            backoffMs = 0,
            fallbackRoutes = listOf(com.riwonace.agent.router.Route.VECTOR), // SQL 실패시 벡터로
            queryTransform = null,
            additionalContext = mapOf(
                "action" to "스키마 재확인 후 SQL 재생성",
                "includeSchemaHints" to true,
            ),
        )
    }

    private fun createTimeoutStrategy(
        currentRoute: com.riwonace.agent.router.Route,
        retryCount: Int,
    ): RecoveryStrategy {
        return if (retryCount == 0) {
            RecoveryStrategy(
                type = StrategyType.RETRY_SAME,
                maxRetries = 1,
                backoffMs = 2000, // 백오프 후 재시도
                fallbackRoutes = emptyList(),
                queryTransform = null,
                additionalContext = mapOf("reason" to "타임아웃 - 백오프 후 재시도"),
            )
        } else {
            RecoveryStrategy(
                type = StrategyType.FALLBACK_ROUTE,
                maxRetries = 1,
                backoffMs = 0,
                fallbackRoutes = getFallbackRoutes(currentRoute),
                queryTransform = null,
                additionalContext = mapOf("reason" to "타임아웃 지속 - 대체 경로"),
            )
        }
    }

    private fun createGiveUpStrategy(classification: FailureClassification): RecoveryStrategy {
        return RecoveryStrategy(
            type = StrategyType.GIVE_UP,
            maxRetries = 0,
            backoffMs = 0,
            fallbackRoutes = emptyList(),
            queryTransform = null,
            additionalContext = mapOf(
                "reason" to "복구 불가: ${classification.type}",
                "severity" to classification.severity,
            ),
        )
    }

    private fun getFallbackRoutes(currentRoute: com.riwonace.agent.router.Route): List<com.riwonace.agent.router.Route> {
        return when (currentRoute) {
            com.riwonace.agent.router.Route.SQL -> listOf(
                com.riwonace.agent.router.Route.VECTOR,
                com.riwonace.agent.router.Route.GRAPH,
            )
            com.riwonace.agent.router.Route.VECTOR -> listOf(
                com.riwonace.agent.router.Route.GRAPH,
                com.riwonace.agent.router.Route.SQL,
            )
            com.riwonace.agent.router.Route.GRAPH -> listOf(
                com.riwonace.agent.router.Route.VECTOR,
                com.riwonace.agent.router.Route.SQL,
            )
        }
    }

    /**
     * 복구 이력을 기록하고 패턴을 학습한다 (향후 확장용).
     */
    fun recordRecoveryAttempt(
        failureType: FailureType,
        strategy: StrategyType,
        success: Boolean,
    ) {
        log.info(
            "복구 시도 기록: {} → {} (성공={})",
            failureType, strategy, success,
        )
        // TODO: 복구 성공률 통계 수집 및 전략 최적화
    }
}
