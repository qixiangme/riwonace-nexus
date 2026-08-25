package core

type ModelTier string

const (
	TierSmall  ModelTier = "SMALL"
	TierMedium ModelTier = "MEDIUM"
	TierLarge  ModelTier = "LARGE"
)

var tierOrder = []ModelTier{TierSmall, TierMedium, TierLarge}

func nextTier(t ModelTier) ModelTier {
	for i, tier := range tierOrder {
		if tier == t && i+1 < len(tierOrder) {
			return tierOrder[i+1]
		}
	}
	return t
}

// ModelSelection mirrors data class ModelSelection.
type ModelSelection struct {
	Model             string
	Tier              ModelTier
	Reason            string
	LatencyMultiplier float64
}

// ModelEscalator ports ModelEscalator.kt 1:1.
type ModelEscalator struct {
	Enabled     bool
	SmallModel  string
	MediumModel string
	LargeModel  string
}

func (e *ModelEscalator) modelForTier(tier ModelTier) (string, float64) {
	switch tier {
	case TierSmall:
		return e.SmallModel, 1.0
	case TierMedium:
		return e.MediumModel, 2.5
	default:
		return e.LargeModel, 5.0
	}
}

func (e *ModelEscalator) SelectModel(profile QueryProfile) ModelSelection {
	if !e.Enabled {
		return ModelSelection{Model: e.SmallModel, Tier: TierSmall, Reason: "에스컬레이션 비활성화 (고정 모델)", LatencyMultiplier: 1.0}
	}

	complexity := profile.Complexity
	uncertainty := profile.Uncertainty
	isMultiHop := profile.IsMultiHop

	var tier ModelTier
	switch {
	case complexity >= ComplexityThresholdHigh:
		tier = TierLarge
	case complexity >= ComplexityThresholdMedium:
		tier = TierMedium
	default:
		tier = TierSmall
	}

	if uncertainty >= UncertaintyThreshold && tier != TierLarge {
		tier = nextTier(tier)
	}

	if isMultiHop && tier == TierSmall {
		tier = TierMedium
	}

	model, latencyMultiplier := e.modelForTier(tier)

	return ModelSelection{
		Model:             model,
		Tier:              tier,
		Reason:            buildEscalationReason(complexity, uncertainty, isMultiHop, tier),
		LatencyMultiplier: latencyMultiplier,
	}
}

// ShouldReescalate mirrors shouldReescalate: returns nil when no reescalation is needed.
func (e *ModelEscalator) ShouldReescalate(current ModelSelection, answerQuality, claimCoverage float64) *ModelSelection {
	if !e.Enabled {
		return nil
	}

	if current.Tier == TierLarge {
		return nil
	}

	if !(answerQuality < 0.6 || claimCoverage < 0.7) {
		return nil
	}

	next := nextTier(current.Tier)
	model, latencyMultiplier := e.modelForTier(next)

	return &ModelSelection{
		Model:             model,
		Tier:              next,
		Reason:            "품질 또는 커버리지 미달로 재에스컬레이션",
		LatencyMultiplier: latencyMultiplier,
	}
}

func buildEscalationReason(complexity, uncertainty float64, isMultiHop bool, tier ModelTier) string {
	factors := ""
	switch {
	case complexity >= ComplexityThresholdHigh:
		factors = "복잡도 높음"
	case complexity >= ComplexityThresholdMedium:
		factors = "복잡도 중간"
	default:
		factors = "복잡도 낮음"
	}
	if uncertainty >= UncertaintyThreshold {
		factors += ", 불확실성 높음"
	}
	if isMultiHop {
		factors += ", 다단계 추론"
	}
	return factors + " → " + string(tier) + " 모델 선택"
}
