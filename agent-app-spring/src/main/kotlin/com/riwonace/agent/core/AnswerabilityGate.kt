package com.riwonace.agent.core

import com.riwonace.agent.context.ContextItem
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Answerability Gate: 수집된 증거가 질문에 답할 수 있는지 검증한다.
 *
 * 핵심 메트릭: claim_coverage = supported_claims / required_claims
 *
 * claim_coverage < 임계값이면:
 * - 추가 검색 요청
 * - 또는 "답변 불가" 응답 생성
 */
@Component
class AnswerabilityGate(
    private val chatClient: ChatClient,
    @Value("\${agent.answerability.coverage-threshold:0.7}")
    private val coverageThreshold: Double,
    @Value("\${agent.answerability.use-llm:false}")
    private val useLlm: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 검증 결과
     */
    data class GateResult(
        /** 통과 여부 */
        val passed: Boolean,
        /** 클레임 커버리지 (0.0 ~ 1.0) */
        val claimCoverage: Double,
        /** 감지된 필수 클레임들 */
        val requiredClaims: List<String>,
        /** 지원되는 클레임들 */
        val supportedClaims: List<String>,
        /** 누락된 클레임들 */
        val missingClaims: List<String>,
        /** 권장 액션 */
        val recommendedAction: GateAction,
        /** 검증 사유 */
        val reasoning: String,
    )

    enum class GateAction {
        /** 답변 생성 진행 */
        PROCEED,
        /** 추가 증거 검색 필요 */
        SEARCH_MORE,
        /** 답변 불가 응답 생성 */
        DECLINE,
        /** 부분 답변 (가능한 것만) */
        PARTIAL_ANSWER,
    }

    /**
     * 증거가 질문에 답하기 충분한지 검증한다.
     */
    fun verify(
        question: String,
        evidence: List<ContextItem>,
        profile: QueryProfile,
    ): GateResult {
        // 1. 질문에서 필수 클레임 추출
        val requiredClaims = extractRequiredClaims(question, profile)

        // 2. 증거에서 지원 가능한 클레임 추출
        val supportedClaims = extractSupportedClaims(evidence, requiredClaims)

        // 3. 커버리지 계산
        val coverage = if (requiredClaims.isEmpty()) {
            1.0
        } else {
            supportedClaims.size.toDouble() / requiredClaims.size
        }

        // 4. 누락 클레임 식별
        val missingClaims = requiredClaims - supportedClaims.toSet()

        // 5. 액션 결정
        val action = determineAction(coverage, missingClaims, profile)

        val passed = action == GateAction.PROCEED || action == GateAction.PARTIAL_ANSWER

        log.info(
            "Answerability 검증: coverage={}, required={}, supported={}, missing={}, action={}",
            "%.2f".format(coverage), requiredClaims.size, supportedClaims.size, missingClaims.size, action,
        )

        return GateResult(
            passed = passed,
            claimCoverage = coverage,
            requiredClaims = requiredClaims,
            supportedClaims = supportedClaims,
            missingClaims = missingClaims,
            recommendedAction = action,
            reasoning = buildReasoning(coverage, requiredClaims.size, supportedClaims.size, action),
        )
    }

    /**
     * 질문에서 필수 클레임 추출 (휴리스틱 기반)
     */
    private fun extractRequiredClaims(question: String, profile: QueryProfile): List<String> {
        val claims = mutableListOf<String>()
        val q = question.lowercase()

        // 의문사 기반 클레임
        if (q.contains("누가") || q.contains("누구")) claims += "person_identity"
        if (q.contains("언제")) claims += "time_info"
        if (q.contains("어디") || q.contains("위치")) claims += "location_info"
        if (q.contains("왜") || q.contains("이유")) claims += "reason_explanation"
        if (q.contains("어떻게") || q.contains("방법")) claims += "method_process"
        if (q.contains("얼마") || q.contains("몇")) claims += "quantity_value"

        // 의도별 필수 클레임
        when (profile.intent) {
            QueryIntent.FACTUAL -> claims += "factual_data"
            QueryIntent.AGGREGATION -> claims += "aggregated_value"
            QueryIntent.COMPARISON -> {
                claims += "comparison_item_a"
                claims += "comparison_item_b"
            }
            QueryIntent.EXPLANATION -> claims += "concept_definition"
            QueryIntent.MULTI_HOP -> {
                claims += "intermediate_result"
                claims += "final_result"
            }
            QueryIntent.RELATION -> claims += "relationship_info"
        }

        // 엔티티 추출 (간단한 패턴)
        val entityPatterns = listOf(
            Regex("[가-힣]{2,}(의|은|는|이|가|을|를)"),
            Regex("'[^']+'"),
            Regex("\"[^\"]+\""),
        )
        for (pattern in entityPatterns) {
            pattern.findAll(question).forEach { match ->
                val entity = match.value.replace(Regex("[의은는이가을를'\"]"), "").trim()
                if (entity.length >= 2) {
                    claims += "entity_$entity"
                }
            }
        }

        return claims.distinct()
    }

    /**
     * 증거에서 지원 가능한 클레임 추출
     */
    private fun extractSupportedClaims(
        evidence: List<ContextItem>,
        requiredClaims: List<String>,
    ): List<String> {
        val supported = mutableListOf<String>()
        val combinedText = evidence.joinToString(" ") { it.text }.lowercase()

        for (claim in requiredClaims) {
            when {
                claim == "person_identity" &&
                    (evidence.any { hasPersonInfo(it.text) } || evidence.any { it.source == "knowledge-graph" }) ->
                    supported += claim

                claim == "time_info" && combinedText.contains(Regex("\\d{4}|\\d+월|\\d+일|\\d+년")) ->
                    supported += claim

                // "어디"는 지명(서울시, 강남구)뿐 아니라 그래프 엔티티(Client-X)나 고객사/부서명을
                // 묻는 경우도 많다 — knowledge-graph/sql 근거가 있으면 위치 클레임도 지원된 것으로 본다.
                claim == "location_info" &&
                    (
                        combinedText.contains(Regex("[가-힣]+(시|도|구|동|로|길)")) ||
                            evidence.any { it.source == "knowledge-graph" || it.source == "sql" }
                    ) ->
                    supported += claim

                claim == "quantity_value" && combinedText.contains(Regex("\\d+")) ->
                    supported += claim

                claim == "factual_data" && evidence.isNotEmpty() ->
                    supported += claim

                claim == "aggregated_value" && evidence.any { it.source == "sql" } ->
                    supported += claim

                // "A vs B"류 명시적 비교는 근거가 2건 이상이어야 하지만, "가장 낮은/높은 X"
                // 같은 최상급 질문은 SQL이 GROUP BY+ORDER BY+LIMIT 1로 이미 비교를 마치고
                // 단일 행만 반환한다 — 이 경우 SQL/GRAPH 근거 자체가 비교 결과이므로 충족된다.
                claim.startsWith("comparison_item_") &&
                    (evidence.size >= 2 || evidence.any { it.source == "sql" || it.source == "knowledge-graph" }) ->
                    supported += claim

                claim == "concept_definition" && combinedText.length > 100 ->
                    supported += claim

                claim == "relationship_info" && evidence.any { it.source == "knowledge-graph" } ->
                    supported += claim

                claim.startsWith("entity_") -> {
                    val entityName = claim.removePrefix("entity_")
                    if (combinedText.contains(entityName)) {
                        supported += claim
                    }
                }

                claim == "intermediate_result" && evidence.size >= 1 ->
                    supported += claim

                claim == "final_result" && evidence.isNotEmpty() ->
                    supported += claim

                claim == "reason_explanation" && combinedText.length > 50 ->
                    supported += claim

                claim == "method_process" && combinedText.length > 50 ->
                    supported += claim
            }
        }

        return supported
    }

    /**
     * 텍스트에 사람 정보가 포함되어 있는지 확인
     */
    private fun hasPersonInfo(text: String): Boolean {
        val personPatterns = listOf(
            Regex("[가-힣]{2,4}\\s*(씨|님|대표|사장|이사|팀장|과장|부장|매니저)"),
            Regex("담당자|개발자|관리자|작성자"),
        )
        return personPatterns.any { it.containsMatchIn(text) }
    }

    /**
     * 커버리지와 상황에 따른 액션 결정
     */
    private fun determineAction(
        coverage: Double,
        missingClaims: List<String>,
        profile: QueryProfile,
    ): GateAction {
        return when {
            // 완전 커버리지
            coverage >= coverageThreshold -> GateAction.PROCEED

            // 부분 커버리지 (50% 이상)
            coverage >= 0.5 -> {
                // 핵심 클레임이 누락되었으면 추가 검색
                val criticalMissing = missingClaims.any {
                    it in listOf("factual_data", "aggregated_value", "quantity_value")
                }
                if (criticalMissing) GateAction.SEARCH_MORE else GateAction.PARTIAL_ANSWER
            }

            // 낮은 커버리지
            coverage >= 0.3 -> {
                // 설명형 질문은 부분 답변 허용
                if (profile.intent == QueryIntent.EXPLANATION) {
                    GateAction.PARTIAL_ANSWER
                } else {
                    GateAction.SEARCH_MORE
                }
            }

            // 매우 낮은 커버리지
            else -> GateAction.DECLINE
        }
    }

    private fun buildReasoning(
        coverage: Double,
        required: Int,
        supported: Int,
        action: GateAction,
    ): String {
        val coveragePercent = String.format("%.0f", coverage * 100)
        return "커버리지 $coveragePercent% ($supported/$required 클레임) → $action"
    }
}
