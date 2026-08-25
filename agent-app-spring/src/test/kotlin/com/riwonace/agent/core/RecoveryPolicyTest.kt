package com.riwonace.agent.core

import com.riwonace.agent.router.Route
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryPolicyTest {

    private val policy = RecoveryPolicy()

    // Helper function to create FailureClassification
    private fun createClassification(
        type: FailureType,
        severity: Int,
        recoverable: Boolean,
        evidence: String,
    ) = RecoveryPolicy.FailureClassification(type, severity, recoverable, evidence)

    // === 실패 분류 테스트 ===

    @Test
    fun `테이블 미존재 오류는 SQL_SCHEMA_ERROR로 분류된다`() {
        val classification = policy.classifyFailure(
            "ERROR: relation \"non_existent_table\" does not exist",
            Route.SQL,
        )

        assertEquals(FailureType.SQL_SCHEMA_ERROR, classification.type)
        assertTrue(classification.recoverable)
    }

    @Test
    fun `컬럼 미존재 오류는 SQL_SCHEMA_ERROR로 분류된다`() {
        val classification = policy.classifyFailure(
            "ERROR: column \"wrong_column\" does not exist",
            Route.SQL,
        )

        assertEquals(FailureType.SQL_SCHEMA_ERROR, classification.type)
    }

    @Test
    fun `SQL 문법 오류는 SQL_SYNTAX_ERROR로 분류된다`() {
        val classification = policy.classifyFailure(
            "syntax error at or near \"SELEC\"",
            Route.SQL,
        )

        assertEquals(FailureType.SQL_SYNTAX_ERROR, classification.type)
        assertTrue(classification.recoverable)
    }

    @Test
    fun `빈 결과는 RETRIEVAL_EMPTY로 분류된다`() {
        val classification = policy.classifyFailure(
            "조회 결과가 0행입니다",
            Route.SQL,
        )

        assertEquals(FailureType.RETRIEVAL_EMPTY, classification.type)
    }

    @Test
    fun `타임아웃은 MCP_TIMEOUT으로 분류된다`() {
        val classification = policy.classifyFailure(
            "Request timed out after 30 seconds",
            Route.VECTOR,
        )

        assertEquals(FailureType.MCP_TIMEOUT, classification.type)
    }

    @Test
    fun `연결 오류는 MCP_CONNECTION_ERROR로 분류된다`() {
        val classification = policy.classifyFailure(
            "Connection refused to MCP server",
            Route.SQL,
        )

        assertEquals(FailureType.MCP_CONNECTION_ERROR, classification.type)
    }

    @Test
    fun `권한 오류는 복구 불가로 분류된다`() {
        val classification = policy.classifyFailure(
            "permission denied for table users",
            Route.SQL,
        )

        assertFalse(classification.recoverable)
    }

    // === 복구 전략 테스트 ===

    @Test
    fun `ROUTE_MISS는 대체 라우트로 폴백한다`() {
        val classification = createClassification(
            type = FailureType.ROUTE_MISS,
            severity = 2,
            recoverable = true,
            evidence = "라우트 미매칭",
        )

        val strategy = policy.determineStrategy(classification, Route.VECTOR, 0, "테스트 질문")

        assertEquals(RecoveryPolicy.StrategyType.FALLBACK_ROUTE, strategy.type)
        assertTrue(strategy.fallbackRoutes.isNotEmpty())
    }

    @Test
    fun `RETRIEVAL_EMPTY는 쿼리를 완화하여 재시도한다`() {
        val classification = createClassification(
            type = FailureType.RETRIEVAL_EMPTY,
            severity = 2,
            recoverable = true,
            evidence = "검색 결과 없음",
        )

        val strategy = policy.determineStrategy(classification, Route.VECTOR, 0, "2023년 매출")

        assertEquals(RecoveryPolicy.StrategyType.RETRY_MODIFIED, strategy.type)
    }

    @Test
    fun `SQL_SCHEMA_ERROR는 스키마 재확인 후 재생성한다`() {
        val classification = createClassification(
            type = FailureType.SQL_SCHEMA_ERROR,
            severity = 3,
            recoverable = true,
            evidence = "테이블 미존재",
        )

        val strategy = policy.determineStrategy(classification, Route.SQL, 0, "직원 수")

        assertEquals(RecoveryPolicy.StrategyType.RETRY_MODIFIED, strategy.type)
        assertTrue(strategy.fallbackRoutes.contains(Route.VECTOR))
    }

    @Test
    fun `MCP_TIMEOUT 첫 번째는 백오프 후 재시도한다`() {
        val classification = createClassification(
            type = FailureType.MCP_TIMEOUT,
            severity = 3,
            recoverable = true,
            evidence = "타임아웃",
        )

        val strategy = policy.determineStrategy(classification, Route.SQL, 0, "테스트")

        assertEquals(RecoveryPolicy.StrategyType.RETRY_SAME, strategy.type)
        assertTrue(strategy.backoffMs > 0)
    }

    @Test
    fun `MCP_TIMEOUT 두 번째는 대체 라우트로 폴백한다`() {
        val classification = createClassification(
            type = FailureType.MCP_TIMEOUT,
            severity = 3,
            recoverable = true,
            evidence = "타임아웃",
        )

        val strategy = policy.determineStrategy(classification, Route.SQL, 1, "테스트")

        assertEquals(RecoveryPolicy.StrategyType.FALLBACK_ROUTE, strategy.type)
    }

    @Test
    fun `EVIDENCE_CONFLICT는 부분 응답을 생성한다`() {
        val classification = createClassification(
            type = FailureType.EVIDENCE_CONFLICT,
            severity = 2,
            recoverable = true,
            evidence = "증거 충돌",
        )

        val strategy = policy.determineStrategy(classification, Route.VECTOR, 0, "테스트")

        assertEquals(RecoveryPolicy.StrategyType.PARTIAL_RESPONSE, strategy.type)
    }

    @Test
    fun `3회 이상 재시도하면 포기한다`() {
        val classification = createClassification(
            type = FailureType.RETRIEVAL_EMPTY,
            severity = 2,
            recoverable = true,
            evidence = "검색 결과 없음",
        )

        val strategy = policy.determineStrategy(classification, Route.VECTOR, 3, "테스트")

        assertEquals(RecoveryPolicy.StrategyType.GIVE_UP, strategy.type)
    }

    @Test
    fun `복구 불가 분류는 즉시 포기한다`() {
        val classification = createClassification(
            type = FailureType.SQL_SCHEMA_ERROR,
            severity = 4,
            recoverable = false,
            evidence = "권한 부족",
        )

        val strategy = policy.determineStrategy(classification, Route.SQL, 0, "테스트")

        assertEquals(RecoveryPolicy.StrategyType.GIVE_UP, strategy.type)
    }

    // === 폴백 라우트 테스트 ===

    @Test
    fun `SQL 실패 시 VECTOR, GRAPH 순으로 폴백한다`() {
        val classification = createClassification(
            type = FailureType.ROUTE_MISS,
            severity = 2,
            recoverable = true,
            evidence = "미매칭",
        )

        val strategy = policy.determineStrategy(classification, Route.SQL, 0, "테스트")

        assertEquals(Route.VECTOR, strategy.fallbackRoutes.first())
    }

    @Test
    fun `VECTOR 실패 시 GRAPH, SQL 순으로 폴백한다`() {
        val classification = createClassification(
            type = FailureType.ROUTE_MISS,
            severity = 2,
            recoverable = true,
            evidence = "미매칭",
        )

        val strategy = policy.determineStrategy(classification, Route.VECTOR, 0, "테스트")

        assertEquals(Route.GRAPH, strategy.fallbackRoutes.first())
    }
}
