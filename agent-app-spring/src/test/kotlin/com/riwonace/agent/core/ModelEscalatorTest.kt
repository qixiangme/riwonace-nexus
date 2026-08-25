package com.riwonace.agent.core

import com.riwonace.agent.router.Route
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModelEscalatorTest {

    private val escalator = ModelEscalator(
        enabled = true,
        smallModel = "gemma3:1b",
        mediumModel = "qwen2.5:3b",
        largeModel = "qwen2.5:7b",
    )

    @Test
    fun `낮은 복잡도는 SMALL 모델을 선택한다`() {
        val profile = createProfile(complexity = 0.2, uncertainty = 0.1)

        val selection = escalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.SMALL, selection.tier)
        assertEquals("gemma3:1b", selection.model)
    }

    @Test
    fun `중간 복잡도는 MEDIUM 모델을 선택한다`() {
        val profile = createProfile(complexity = 0.5, uncertainty = 0.1)

        val selection = escalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.MEDIUM, selection.tier)
        assertEquals("qwen2.5:3b", selection.model)
    }

    @Test
    fun `높은 복잡도는 LARGE 모델을 선택한다`() {
        val profile = createProfile(complexity = 0.8, uncertainty = 0.1)

        val selection = escalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.LARGE, selection.tier)
        assertEquals("qwen2.5:7b", selection.model)
    }

    @Test
    fun `높은 불확실성은 한 단계 상향한다`() {
        val profile = createProfile(complexity = 0.3, uncertainty = 0.6)

        val selection = escalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.MEDIUM, selection.tier) // SMALL → MEDIUM
    }

    @Test
    fun `다단계 추론은 최소 MEDIUM 모델이다`() {
        val profile = createProfile(complexity = 0.2, uncertainty = 0.1, isMultiHop = true)

        val selection = escalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.MEDIUM, selection.tier)
    }

    @Test
    fun `품질이 낮으면 재에스컬레이션을 권장한다`() {
        val currentSelection = ModelEscalator.ModelSelection(
            model = "gemma3:1b",
            tier = ModelEscalator.ModelTier.SMALL,
            reason = "테스트",
            latencyMultiplier = 1.0,
        )

        val reescalation = escalator.shouldReescalate(
            currentSelection,
            answerQuality = 0.4,
            claimCoverage = 0.5,
        )

        assertEquals(ModelEscalator.ModelTier.MEDIUM, reescalation?.tier)
    }

    @Test
    fun `품질이 충분하면 재에스컬레이션하지 않는다`() {
        val currentSelection = ModelEscalator.ModelSelection(
            model = "gemma3:1b",
            tier = ModelEscalator.ModelTier.SMALL,
            reason = "테스트",
            latencyMultiplier = 1.0,
        )

        val reescalation = escalator.shouldReescalate(
            currentSelection,
            answerQuality = 0.8,
            claimCoverage = 0.9,
        )

        assertEquals(null, reescalation)
    }

    @Test
    fun `이미 LARGE 모델이면 재에스컬레이션하지 않는다`() {
        val currentSelection = ModelEscalator.ModelSelection(
            model = "qwen2.5:7b",
            tier = ModelEscalator.ModelTier.LARGE,
            reason = "테스트",
            latencyMultiplier = 5.0,
        )

        val reescalation = escalator.shouldReescalate(
            currentSelection,
            answerQuality = 0.3,
            claimCoverage = 0.3,
        )

        assertEquals(null, reescalation)
    }

    @Test
    fun `에스컬레이션 비활성화 시 항상 SMALL 모델`() {
        val disabledEscalator = ModelEscalator(
            enabled = false,
            smallModel = "gemma3:1b",
            mediumModel = "qwen2.5:3b",
            largeModel = "qwen2.5:7b",
        )
        val profile = createProfile(complexity = 0.9, uncertainty = 0.9)

        val selection = disabledEscalator.selectModel(profile)

        assertEquals(ModelEscalator.ModelTier.SMALL, selection.tier)
    }

    private fun createProfile(
        complexity: Double,
        uncertainty: Double,
        isMultiHop: Boolean = false,
    ): QueryProfile {
        return QueryProfile(
            intent = QueryIntent.FACTUAL,
            complexity = complexity,
            uncertainty = uncertainty,
            requiredEvidence = setOf(EvidenceType.STRUCTURED_DATA),
            suggestedRoutes = listOf(Route.SQL),
            isMultiHop = isMultiHop,
            hasDependency = false,
        )
    }
}
