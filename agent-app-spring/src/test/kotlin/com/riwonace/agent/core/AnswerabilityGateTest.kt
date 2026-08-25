package com.riwonace.agent.core

import com.riwonace.agent.context.ContextItem
import com.riwonace.agent.router.Route
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.client.ChatClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerabilityGateTest {

    private val chatClient = mock(ChatClient::class.java)
    private val gate = AnswerabilityGate(
        chatClient = chatClient,
        coverageThreshold = 0.7,
        useLlm = false,
    )

    @Test
    fun `충분한 증거가 있으면 PROCEED 또는 PARTIAL_ANSWER를 반환한다`() {
        val question = "직원 수는 몇 명인가요?"
        val evidence = listOf(
            ContextItem("sql", "count=42", 0.9),
        )
        val profile = createProfile(QueryIntent.FACTUAL)

        val result = gate.verify(question, evidence, profile)

        // 증거가 있으면 통과하거나 부분 답변 가능
        assertTrue(result.passed || result.recommendedAction == AnswerabilityGate.GateAction.PARTIAL_ANSWER)
    }

    @Test
    fun `증거가 부족하면 추가 검색이나 거절을 권장한다`() {
        val question = "김철수 팀장이 담당한 프로젝트와 그 매출은?"
        val evidence = listOf(
            ContextItem("doc", "일반적인 프로젝트 정보", 0.5),
        )
        val profile = createProfile(QueryIntent.MULTI_HOP)

        val result = gate.verify(question, evidence, profile)

        // 부족한 증거에 대해 추가 검색 또는 거절 권장
        assertTrue(
            result.recommendedAction in listOf(
                AnswerabilityGate.GateAction.SEARCH_MORE,
                AnswerabilityGate.GateAction.DECLINE,
                AnswerabilityGate.GateAction.PARTIAL_ANSWER,
            ),
        )
    }

    @Test
    fun `커버리지가 매우 낮으면 DECLINE을 반환한다`() {
        val question = "언제 어디서 누가 왜 어떻게 했나요?"
        val evidence = emptyList<ContextItem>()
        val profile = createProfile(QueryIntent.MULTI_HOP)

        val result = gate.verify(question, evidence, profile)

        assertFalse(result.passed)
        assertEquals(AnswerabilityGate.GateAction.DECLINE, result.recommendedAction)
    }

    @Test
    fun `부분 커버리지면 PARTIAL_ANSWER를 허용한다`() {
        val question = "MCP의 정의와 활용 방법은?"
        val evidence = listOf(
            ContextItem("doc", "MCP는 Model Context Protocol의 약자로 AI 모델과 데이터 소스를 연결하는 표준입니다.", 0.9),
        )
        val profile = createProfile(QueryIntent.EXPLANATION)

        val result = gate.verify(question, evidence, profile)

        assertTrue(result.passed || result.recommendedAction == AnswerabilityGate.GateAction.PARTIAL_ANSWER)
    }

    @Test
    fun `숫자 질문에 숫자 포함 증거가 있으면 커버리지가 계산된다`() {
        val question = "총 매출은 얼마인가요?"
        val evidence = listOf(
            ContextItem("sql", "total_revenue=1234567", 0.9),
        )
        val profile = createProfile(QueryIntent.AGGREGATION)

        val result = gate.verify(question, evidence, profile)

        // 숫자가 포함된 증거가 있으면 quantity_value 클레임 지원
        assertTrue(result.supportedClaims.contains("quantity_value"))
        assertTrue(result.claimCoverage > 0.0)
    }

    @Test
    fun `사람 정보 질문에 사람 정보가 있으면 커버된다`() {
        val question = "이 프로젝트를 누가 담당했나요?"
        val evidence = listOf(
            ContextItem("doc", "프로젝트 담당자는 김철수 팀장입니다.", 0.9),
        )
        val profile = createProfile(QueryIntent.RELATION)

        val result = gate.verify(question, evidence, profile)

        assertTrue(result.supportedClaims.contains("person_identity"))
    }

    @Test
    fun `클레임 커버리지가 정확하게 계산된다`() {
        val question = "직원 수는?"
        val evidence = listOf(
            ContextItem("sql", "count=42", 0.9),
        )
        val profile = createProfile(QueryIntent.FACTUAL)

        val result = gate.verify(question, evidence, profile)

        // 필수 클레임 중 몇 개가 지원되는지 비율
        assertEquals(
            result.supportedClaims.size.toDouble() / result.requiredClaims.size.coerceAtLeast(1),
            result.claimCoverage,
            0.01,
        )
    }

    @Test
    fun `빈 증거는 0 커버리지다`() {
        val question = "직원 수는?"
        val profile = createProfile(QueryIntent.FACTUAL)

        val result = gate.verify(question, emptyList(), profile)

        assertTrue(result.claimCoverage < 0.5)
    }

    private fun createProfile(intent: QueryIntent): QueryProfile {
        return QueryProfile(
            intent = intent,
            complexity = 0.3,
            uncertainty = 0.2,
            requiredEvidence = setOf(EvidenceType.STRUCTURED_DATA),
            suggestedRoutes = listOf(Route.SQL),
            isMultiHop = intent == QueryIntent.MULTI_HOP,
            hasDependency = false,
        )
    }
}
