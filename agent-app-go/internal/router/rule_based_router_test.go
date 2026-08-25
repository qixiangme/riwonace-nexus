package router

import (
	"reflect"
	"testing"
)

// Test names mirror RuleBasedRouterTest.kt cases 1:1.

func contains(routes []Route, r Route) bool {
	for _, x := range routes {
		if x == r {
			return true
		}
	}
	return false
}

func TestRuleBasedRouter_AggregationQuestionRoutesToSQL(t *testing.T) {
	router := &RuleBasedRouter{}
	if !contains(router.Route("플랫폼팀 직원의 평균 급여는 얼마야?"), RouteSQL) {
		t.Fatal("expected SQL route")
	}
	if !contains(router.Route("가장 비싼 제품이 뭐야?"), RouteSQL) {
		t.Fatal("expected SQL route")
	}
}

func TestRuleBasedRouter_ConceptQuestionRoutesToVector(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("MCP가 뭔가요? 설명해줘")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_RelationQuestionRoutesToGraph(t *testing.T) {
	router := &RuleBasedRouter{}
	if !contains(router.Route("air는 누가 개발했어?"), RouteGraph) {
		t.Fatal("expected GRAPH route")
	}
	if !contains(router.Route("MCP와 RAG는 무슨 사이야?"), RouteGraph) {
		t.Fatal("expected GRAPH route")
	}
}

func TestRuleBasedRouter_CompoundQuestionParallelRoutes(t *testing.T) {
	router := &RuleBasedRouter{}
	routes := router.Route("pgvector 개념을 설명하고 관련된 제품 목록도 알려줘")
	if !contains(routes, RouteSQL) || !contains(routes, RouteVector) {
		t.Fatalf("got %v", routes)
	}
}

func TestRuleBasedRouter_PriceAndInstallSelectsSqlAndVector(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("월 가격과 설치에 필요한 컨테이너 도구를 함께 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_PriceAndActualUsageClientSelectsSqlAndGraph(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("월 가격과 실제 이용 고객을 함께 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_ProductNameAloneDoesNotOverselectSQL(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("Product-C1 설치 방식과 이 제품을 실제 사용하는 고객사를 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteGraph, RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_NumericConditionProductWithDocsAndUsageSelectsThreeRoutes(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("월 120인 cloud 제품 중 CPU 62%이며 Client-A가 사용하는 것은?")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteGraph, RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_ReleaseStatusAndBackupSelectsSqlAndVector(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("출시 상태와 백업 실행 시각 및 보관일을 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_NoRuleMatchDefaultsToVector(t *testing.T) {
	router := &RuleBasedRouter{}
	got := router.Route("안녕하세요")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

type fallbackFunc func(question string) []Route

func (f fallbackFunc) Classify(question string) []Route { return f(question) }

func TestRuleBasedRouter_FallbackDelegatesOnNoKeywordMatch(t *testing.T) {
	router := &RuleBasedRouter{Fallback: fallbackFunc(func(string) []Route { return []Route{RouteGraph} })}
	got := router.Route("키워드가 하나도 안 걸리는 질문")
	if !reflect.DeepEqual(got, []Route{RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_FallbackNotCalledWhenKeywordMatches(t *testing.T) {
	called := false
	router := &RuleBasedRouter{Fallback: fallbackFunc(func(string) []Route {
		called = true
		return []Route{RouteGraph}
	})}
	router.Route("가장 비싼 제품이 뭐야?")
	if called {
		t.Fatal("fallback should not have been called")
	}
}

func TestRuleBasedRouter_CompositionCueAugmentsSingleRuleWithFallbackMultiRoute(t *testing.T) {
	router := &RuleBasedRouter{Fallback: fallbackFunc(func(string) []Route { return []Route{RouteSQL, RouteGraph} })}
	got := router.Route("월 가격과 실제 이용 고객을 함께 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

// 아래는 302문항/홀드아웃 검증 과정에서 발견된 baseline(Kotlin) 회귀 수정을
// Go 포팅에도 반영한 테스트다.

func TestRuleBasedRouter_EmployeeKeywordRemovedDoesNotForceSqlOnGraphQuestion(t *testing.T) {
	// "직원"은 SQL/GRAPH 양쪽에 다 등장하는 모호한 키워드라 sqlKeywords에서 뺐다.
	// 이 질문은 실제로는 GRAPH(부서 소속 관계)이고, "직원" 하나만으로 SQL이
	// 확정되어 semantic-ai fallback이 막히면 안 된다.
	called := false
	router := &RuleBasedRouter{Fallback: fallbackFunc(func(string) []Route {
		called = true
		return []Route{RouteGraph}
	})}
	got := router.Route("경영지원팀에서 일하는 직원들은 누구입니까?")
	if !called {
		t.Fatal("expected fallback to be consulted since 직원 alone must not force SQL")
	}
	if !reflect.DeepEqual(got, []Route{RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

func TestRuleBasedRouter_DepartmentHeadKeywordRoutesToGraph(t *testing.T) {
	if !contains((&RuleBasedRouter{}).Route("클라우드사업부 부서장은 누구인가요?"), RouteGraph) {
		t.Fatal("expected GRAPH route for 부서장 keyword")
	}
}

func TestRuleBasedRouter_DepartmentNameOverlapWithVectorKeywordDoesNotForceVector(t *testing.T) {
	// "인프라운영팀"의 "운영"이 vectorKeywords와 우연히 겹치는 케이스. GRAPH 키워드
	// ("담당")가 이미 있으므로 라우팅 결과에 VECTOR가 섞여 들어가면 안 된다.
	got := (&RuleBasedRouter{}).Route("인프라운영팀 소속 직원이 담당하는 고객사는?")
	if !contains(got, RouteGraph) {
		t.Fatalf("expected GRAPH route, got %v", got)
	}
	if contains(got, RouteVector) {
		t.Fatalf("department name token '운영' must not force VECTOR, got %v", got)
	}
}
