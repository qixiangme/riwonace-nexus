package com.riwonace.agent.core

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 모델 에스컬레이션 결정자: 복잡도/불확실성에 따라 1B → 4B → 7B 모델을 선택한다.
 *
 * 전략:
 * - 단순 질문 (complexity < 0.4): 1B 모델로 빠르게 응답
 * - 중간 복잡도 (0.4 ≤ complexity < 0.7): 4B 모델로 품질 확보
 * - 복잡한 질문 (complexity ≥ 0.7): 7B 모델로 정확도 우선
 *
 * 추가로 불확실성이 높으면 한 단계 상향한다.
 */
@Component
class ModelEscalator(
    @Value("\${agent.escalation.enabled:true}")
    private val enabled: Boolean,
    @Value("\${agent.escalation.models.small:gemma3:1b}")
    private val smallModel: String,
    @Value("\${agent.escalation.models.medium:qwen2.5:3b}")
    private val mediumModel: String,
    @Value("\${agent.escalation.models.large:qwen2.5:7b}")
    private val largeModel: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 모델 선택 결과
     */
    data class ModelSelection(
        /** 선택된 모델 이름 */
        val model: String,
        /** 선택 티어 (SMALL, MEDIUM, LARGE) */
        val tier: ModelTier,
        /** 선택 사유 */
        val reason: String,
        /** 예상 지연시간 배수 (1B 기준) */
        val latencyMultiplier: Double,
    )

    enum class ModelTier {
        SMALL,  // 1B급
        MEDIUM, // 3-4B급
        LARGE,  // 7B급
    }

    /**
     * 프로파일 기반으로 최적의 모델을 선택한다.
     */
    fun selectModel(profile: QueryProfile): ModelSelection {
        if (!enabled) {
            return ModelSelection(
                model = smallModel,
                tier = ModelTier.SMALL,
                reason = "에스컬레이션 비활성화 (고정 모델)",
                latencyMultiplier = 1.0,
            )
        }

        val complexity = profile.complexity
        val uncertainty = profile.uncertainty
        val isMultiHop = profile.isMultiHop

        // 기본 티어 결정 (복잡도 기준)
        var tier = when {
            complexity >= QueryProfiler.COMPLEXITY_THRESHOLD_HIGH -> ModelTier.LARGE
            complexity >= QueryProfiler.COMPLEXITY_THRESHOLD_MEDIUM -> ModelTier.MEDIUM
            else -> ModelTier.SMALL
        }

        // 불확실성이 높으면 한 단계 상향
        if (uncertainty >= QueryProfiler.UNCERTAINTY_THRESHOLD && tier != ModelTier.LARGE) {
            tier = ModelTier.entries[tier.ordinal + 1]
        }

        // 다단계 추론은 최소 MEDIUM
        if (isMultiHop && tier == ModelTier.SMALL) {
            tier = ModelTier.MEDIUM
        }

        val (model, latencyMultiplier) = when (tier) {
            ModelTier.SMALL -> smallModel to 1.0
            ModelTier.MEDIUM -> mediumModel to 2.5
            ModelTier.LARGE -> largeModel to 5.0
        }

        val reason = buildReason(complexity, uncertainty, isMultiHop, tier)

        log.info(
            "모델 에스컬레이션: {} (complexity={}, uncertainty={}, multiHop={}) → {}",
            profile.intent, "%.2f".format(complexity), "%.2f".format(uncertainty), isMultiHop, model,
        )

        return ModelSelection(
            model = model,
            tier = tier,
            reason = reason,
            latencyMultiplier = latencyMultiplier,
        )
    }

    /**
     * 응답 품질에 따른 재에스컬레이션 결정
     *
     * @param currentSelection 현재 모델 선택
     * @param answerQuality 답변 품질 점수 (0.0 ~ 1.0)
     * @param claimCoverage 클레임 커버리지 (0.0 ~ 1.0)
     * @return 재시도할 모델 선택, 또는 null (재시도 불필요)
     */
    fun shouldReescalate(
        currentSelection: ModelSelection,
        answerQuality: Double,
        claimCoverage: Double,
    ): ModelSelection? {
        // 이미 최대 모델이면 재에스컬레이션 불가
        if (currentSelection.tier == ModelTier.LARGE) {
            return null
        }

        // 품질이나 커버리지가 낮으면 상향
        val shouldEscalate = answerQuality < 0.6 || claimCoverage < 0.7

        if (!shouldEscalate) {
            return null
        }

        val nextTier = ModelTier.entries[currentSelection.tier.ordinal + 1]
        val (model, latencyMultiplier) = when (nextTier) {
            ModelTier.SMALL -> smallModel to 1.0
            ModelTier.MEDIUM -> mediumModel to 2.5
            ModelTier.LARGE -> largeModel to 5.0
        }

        log.info(
            "모델 재에스컬레이션: {} → {} (품질={}, 커버리지={})",
            currentSelection.model, model, "%.2f".format(answerQuality), "%.2f".format(claimCoverage),
        )

        return ModelSelection(
            model = model,
            tier = nextTier,
            reason = "품질(${String.format("%.0f", answerQuality * 100)}%) 또는 커버리지(${String.format("%.0f", claimCoverage * 100)}%) 미달로 재에스컬레이션",
            latencyMultiplier = latencyMultiplier,
        )
    }

    private fun buildReason(
        complexity: Double,
        uncertainty: Double,
        isMultiHop: Boolean,
        tier: ModelTier,
    ): String {
        val factors = mutableListOf<String>()

        when {
            complexity >= QueryProfiler.COMPLEXITY_THRESHOLD_HIGH ->
                factors += "복잡도 높음(${String.format("%.0f", complexity * 100)}%)"
            complexity >= QueryProfiler.COMPLEXITY_THRESHOLD_MEDIUM ->
                factors += "복잡도 중간(${String.format("%.0f", complexity * 100)}%)"
            else ->
                factors += "복잡도 낮음(${String.format("%.0f", complexity * 100)}%)"
        }

        if (uncertainty >= QueryProfiler.UNCERTAINTY_THRESHOLD) {
            factors += "불확실성 높음(${String.format("%.0f", uncertainty * 100)}%)"
        }

        if (isMultiHop) {
            factors += "다단계 추론"
        }

        return "${factors.joinToString(", ")} → ${tier.name} 모델 선택"
    }
}
