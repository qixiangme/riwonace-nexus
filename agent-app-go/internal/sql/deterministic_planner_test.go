package sql

import (
	"strings"
	"testing"
)

// Test names mirror DeterministicSqlPlannerTest.kt cases 1:1.

func mustPlan(t *testing.T, p *DeterministicSqlPlanner, question string) string {
	t.Helper()
	got := p.Plan(question)
	if got == nil {
		t.Fatalf("expected non-nil plan for %q", question)
	}
	return *got
}

func TestPlan_SimpleProductAttributeAndActiveContractAggregation(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if got := mustPlan(t, p, "Product-C1의 월 가격 알려줘"); got != "SELECT price_monthly FROM products WHERE name = 'Product-C1'" {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "Product-S1 활성 계약 수 알려줘"); !strings.Contains(got, "count(*)") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "Product-D1 활성 계약 금액 합계 알려줘"); !strings.Contains(got, "sum(c.amount)") {
		t.Fatalf("got %q", got)
	}
}

func TestPlan_DepartmentAverageAndClientSalesUseCorrectForeignKeyJoins(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if got := mustPlan(t, p, "기술지원팀 평균 급여"); !strings.Contains(got, "e.dept_id = d.id") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "Client-Q 총 매출"); !strings.Contains(got, "s.client_id = c.id") {
		t.Fatalf("got %q", got)
	}
}

func TestPlan_AmbiguousCompoundNumericProductConditions(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if got := mustPlan(t, p, "월 120인 cloud 제품 중 CPU 기준을 만족하는 것은?"); !strings.Contains(got, "price_monthly = 120") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "활성 계약 금액이 22,000인 data 제품"); !strings.Contains(got, "HAVING sum(c.amount) = 22000") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "활성 계약이 6건인 security 제품"); !strings.Contains(got, "HAVING count(*) = 6") {
		t.Fatalf("got %q", got)
	}
}

func TestPlan_OutsideHighConfidencePatternReturnsNilForLLMFallback(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if got := p.Plan("최근 복잡한 프로젝트 상태를 분석해줘"); got != nil {
		t.Fatalf("expected nil, got %q", *got)
	}
}

func TestPlan_GeneralAggregationQuestionsCompileToSchemaBasedSelect(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if got := mustPlan(t, p, "서울 지역 매출 상위 5개 고객사를 알려줘"); !strings.Contains(got, "ORDER BY total_sales DESC") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "2025년 3분기 총 매출액은 얼마야?"); !strings.Contains(got, "quarter = '2025-Q3'") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "보안 솔루션 카테고리 제품들의 월 평균 매출은?"); !strings.Contains(got, "p.category = 'security'") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "현재 활성 상태인 계약 수는 몇 개야?"); !strings.Contains(got, "status = 'active'") {
		t.Fatalf("got %q", got)
	}
	if got := mustPlan(t, p, "평균 연봉이 가장 높은 부서는 어디야?"); !strings.Contains(got, "ORDER BY average_salary DESC") {
		t.Fatalf("got %q", got)
	}
}

func TestPlan_UnresolvedCriticalTicketsAggregatesActualStoredValues(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "아직 해결되지 않은 Critical 티켓은 몇 건이야?")
	if !strings.Contains(sql, "priority = 'critical'") {
		t.Fatalf("got %q", sql)
	}
	if !strings.Contains(sql, "status IN ('open', 'in_progress')") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_RegisteredClientsWithYearBuildsYearBoundary(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "2024년에 등록된 고객사는 몇 곳이야?")
	if !strings.Contains(sql, "registered_at >= '2024-01-01'") {
		t.Fatalf("got %q", sql)
	}
	if !strings.Contains(sql, "registered_at < '2025-01-01'") {
		t.Fatalf("got %q", sql)
	}
}

// 아래는 baseline(Kotlin)에서 302문항 재검증과 홀드아웃(완전히 다른 데이터셋)
// 검증 과정에서 추가된 패턴을 Go 포팅에도 반영한 회귀 테스트다.

func TestPlan_LowestDepartmentSalaryDoesNotMatchHighestPattern(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "평균 연봉이 가장 낮은 부서는 어디야?")
	if !strings.Contains(sql, "ORDER BY average_salary ASC") {
		t.Fatalf("got %q, want ASC order (not the highest-salary DESC pattern)", sql)
	}
}

func TestPlan_DepartmentHeadcountDoesNotCollideWithEmployeeListPattern(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "경영지원팀 인원수 알려줘")
	if !strings.Contains(sql, "count(*)") {
		t.Fatalf("got %q", sql)
	}
	if !strings.Contains(sql, "e.dept_id = d.id") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_MostCancelledContractProduct(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "해지된 계약이 가장 많은 제품은?")
	if !strings.Contains(sql, "status = 'cancelled'") {
		t.Fatalf("got %q", sql)
	}
	if !strings.Contains(sql, "ORDER BY cancelled_count DESC") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_CategoryRegionCompanySizeTotalSales(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	if sql := mustPlan(t, p, "클라우드 카테고리 제품의 총 매출은 얼마야?"); !strings.Contains(sql, "p.category = 'cloud'") {
		t.Fatalf("got %q", sql)
	}
	if sql := mustPlan(t, p, "대구 지역 매출 총액은 얼마야?"); !strings.Contains(sql, "region = '대구'") {
		t.Fatalf("got %q", sql)
	}
	if sql := mustPlan(t, p, "enterprise 규모 고객사들의 총 매출은 얼마야?"); !strings.Contains(sql, "company_size = 'enterprise'") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_SupportTicketCountAcceptsShortNumberWordNotJustNumberEun(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "Product-C1 티켓 수 알려줘")
	if !strings.Contains(sql, "FROM support_tickets t JOIN products p") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_GenericCodePatternRecognizesNonCompanyXNamingScheme(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	// "Nova-B2"는 Product-/Client- 리터럴 접두사가 없는 임의 코드형 명칭이다 —
	// 홀드아웃(완전히 다른 회사 데이터) 검증에서 이 패턴이 인식되지 않던 버그를 고쳤다.
	sql := mustPlan(t, p, "Nova-B2 티켓 수 알려줘")
	if !strings.Contains(sql, "p.name = 'Nova-B2'") {
		t.Fatalf("got %q", sql)
	}
}

func TestPlan_GenericCodeWithClientHintWordResolvesAsClientNotProduct(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	sql := mustPlan(t, p, "Nova-Client-9 고객사 총 매출")
	if !strings.Contains(sql, "s.client_id = c.id") {
		t.Fatalf("got %q, want client-side join for a name matched via a client hint word", sql)
	}
}

func TestPlan_LiteralClientPrefixIsNotOverriddenByGenericCodeFallback(t *testing.T) {
	p := &DeterministicSqlPlanner{}
	// 회귀 방지: literalClient가 이미 잡히면 genericCode fallback이 product를
	// 잘못 채워 이 케이스가 product 분기로 새면 안 된다(과거 실제로 발생했던 버그).
	sql := mustPlan(t, p, "Client-Q 총 매출")
	if !strings.Contains(sql, "s.client_id = c.id") {
		t.Fatalf("got %q", sql)
	}
}
