package tools

import "testing"

// These cases exercise planKgSearch's pure token/predicate/WHERE-building logic,
// which has no dedicated Kotlin unit test in the baseline (kg_search is only covered
// end-to-end). Cases are derived directly from RetrievalTools.kgSearch's documented
// examples (graph/schema.md) and inline comments in RetrievalTools.kt.

func TestPlanKgSearch_NoTokensNoPredicatesIsEmpty(t *testing.T) {
	plan := planKgSearch("누구 무엇 알려줘")
	if !plan.Empty {
		t.Fatalf("expected empty plan, got %+v", plan)
	}
}

func TestPlanKgSearch_SpecificEntityAndPredicateAndCombine(t *testing.T) {
	plan := planKgSearch("Client-A가 사용하는 제품")
	if len(plan.EntityTokens) == 0 {
		t.Fatalf("expected entity token for 'Client-A', got %+v", plan)
	}
	if len(plan.MatchedPredicates) == 0 || plan.MatchedPredicates[0] != "사용한다" {
		t.Fatalf("expected matched predicate 사용한다, got %v", plan.MatchedPredicates)
	}
	if plan.Where[0] != '(' {
		t.Fatalf("expected AND-combined WHERE (entity AND predicate), got %q", plan.Where)
	}
}

func TestPlanKgSearch_GenericTokenWithPredicateOrCombines(t *testing.T) {
	plan := planKgSearch("진행 중인 프로젝트")
	if len(plan.EntityTokens) != 0 {
		t.Fatalf("expected no entity tokens, got %v", plan.EntityTokens)
	}
	if len(plan.MatchedPredicates) == 0 {
		t.Fatalf("expected matched predicate for 진행, got %v", plan.MatchedPredicates)
	}
	if plan.Where[0] == '(' {
		t.Fatalf("expected OR-combined WHERE (no specific entity), got %q", plan.Where)
	}
}

func TestPlanKgSearch_ParticleStripping(t *testing.T) {
	plan := planKgSearch("Product-C1을 사용하는 고객사는")
	found := false
	for _, tok := range plan.EntityTokens {
		if tok == "Product-C1" {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected particle-stripped 'Product-C1' entity token, got %v", plan.EntityTokens)
	}
}

func TestPlanKgSearch_ExpansionTriggerDetection(t *testing.T) {
	if !requiresExpansion("Product-D1과 2홉으로 연결된 프로젝트") {
		t.Fatal("expected expansion trigger to fire on '연결된 프로젝트'")
	}
	if requiresExpansion("Product-D1 사용 고객") {
		t.Fatal("expected no expansion trigger for a plain direct-lookup question")
	}
}

func TestPlanKgSearch_TokensCappedAtFour(t *testing.T) {
	plan := planKgSearch("팀A 팀B 팀C 팀D 팀E 팀F")
	if len(plan.Tokens) > 4 {
		t.Fatalf("expected at most 4 tokens, got %d: %v", len(plan.Tokens), plan.Tokens)
	}
}

// 아래는 홀드아웃(완전히 다른 데이터셋) 검증 과정에서 baseline(Kotlin)에 추가된
// predicate 체이닝/매핑 보정을 Go 포팅에도 반영한 회귀 테스트다.

func TestPlanKgSearch_DepartmentHeadKeywordMatchesPredicate(t *testing.T) {
	plan := planKgSearch("경영지원팀 부서장은 누구인가요?")
	found := false
	for _, p := range plan.MatchedPredicates {
		if p == "부서장" {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected 부서장 predicate to match, got %v", plan.MatchedPredicates)
	}
}

func TestPlanKgSearch_LeadsKeywordAfterDepartmentEntityMapsToDepartmentHead(t *testing.T) {
	// "~팀을 이끄는 사람"은 프로젝트 리드("이끈다")가 아니라 부서장을 묻는 질문이다 —
	// 엔티티가 부서 토큰(~팀)이면 leadsProjectKeywords 매칭이 부서장으로 재매핑되어야 한다.
	plan := planKgSearch("경영지원팀을 이끄는 사람은 누구입니까?")
	if len(plan.MatchedPredicates) != 1 || plan.MatchedPredicates[0] != "부서장" {
		t.Fatalf("expected sole matched predicate 부서장 for a department-entity 이끄는 question, got %v", plan.MatchedPredicates)
	}
}

func TestPlanKgSearch_LeadsKeywordAfterPersonEntityStaysProjectLead(t *testing.T) {
	// 엔티티가 부서 토큰이 아니면(사람 이름 등) "이끄는"은 여전히 "이끈다"(프로젝트 리드)다.
	plan := planKgSearch("조재원이 이끄는 프로젝트는 뭐야?")
	if len(plan.MatchedPredicates) != 1 || plan.MatchedPredicates[0] != "이끈다" {
		t.Fatalf("expected sole matched predicate 이끈다 for a non-department 이끄는 question, got %v", plan.MatchedPredicates)
	}
}

func TestPlanKgSearch_MultiplePredicateKeywordsMatchInOrderWithoutDuplication(t *testing.T) {
	// "부서장이 담당하는" 질문은 부서장(1홉)과 담당한다(2홉) 두 predicate가 순서대로
	// 매칭되어야 체이닝(KgSearch)이 올바른 hop 순서로 동작한다.
	plan := planKgSearch("클라우드사업부 부서장이 담당하는 고객사는 어디야?")
	if len(plan.MatchedPredicates) != 2 {
		t.Fatalf("expected exactly 2 matched predicates for chaining, got %v", plan.MatchedPredicates)
	}
	if plan.MatchedPredicates[0] != "부서장" || plan.MatchedPredicates[1] != "담당한다" {
		t.Fatalf("expected [부서장, 담당한다] in order, got %v", plan.MatchedPredicates)
	}
}
