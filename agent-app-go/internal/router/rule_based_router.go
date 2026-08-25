package router

import (
	"regexp"
	"strings"
)

// sqlKeywords mirrors RuleBasedRouter.kt's sqlKeywords list, in the same order
// (order doesn't affect the `any` match, but is kept for side-by-side diffing).
var sqlKeywords = []string{
	"몇", "개수", "수는", "평균", "합계", "총", "최대", "최소", "가장 비싼", "가장 싼",
	"가장 많은", "가장 높은", "순위", "상위", "하위", "목록", "리스트", "재고", "급여",
	"연봉", "매출", "주문", "계약", "티켓", "우선순위", "분기", "금액", "등록된",
	"가격", "출시 상태", "부서별", "언제 입사", "입사일", "모두 알려줘", "이름을 모두",
	"count", "average", "sum", "max", "min", "how many", "list", "top",
}

var graphKeywords = []string{
	"관계", "연관", "연결", "관련이", "관련된", "의존", "누가 만들", "누가 개발",
	"무엇을 개발", "어디서 만들", "개발사", "라이선스", "무슨 사이",
	"사용 중인", "사용하는", "사용 고객", "이용 고객", "실제 사용", "실제 이용",
	"이용하는", "사용자 고객",
	"소속", "담당", "팀장", "부서장", "이끄", "이슈",
	"relation", "related", "depends", "who made", "who developed",
}

var vectorKeywords = []string{
	"무엇", "뭐야", "뭔가요", "설명", "소개", "개념", "원리", "어떻게 동작", "왜",
	"장점", "단점", "차이", "정책", "가이드", "방법",
	"장애", "사례", "내용", "취약점", "미팅", "논의", "제안", "매뉴얼", "인증",
	"설치", "배포", "운영", "보관", "구동", "튜닝", "cpu", "hpa", "컨테이너",
	"what is", "explain", "describe", "how does", "why",
}

var compositionCues = []string{"함께", "같이", "동시에", "한 번에", "구분", "그리고", " 및 ", "와 ", "과 "}

// productNumericCore mirrors PRODUCT_NUMERIC in RuleBasedRouter.kt, minus the
// `(?<![a-z0-9_-])` negative lookbehind that Go's RE2 engine cannot express directly.
// matchesProductNumeric below re-implements that lookbehind by checking the character
// immediately preceding each candidate match.
var productNumericCore = regexp.MustCompile(`(?i)\d[\d,]*(?:\.\d+)?(?:원|만원|%|인|개|건|명)?[^.?!]{0,20}(?:제품|product)`)

var lookbehindExcluded = regexp.MustCompile(`(?i)[a-z0-9_-]`)

// departmentLike mirrors DEPARTMENT_LIKE in RuleBasedRouter.kt: department/organization
// name tokens that can accidentally contain a vectorKeywords substring (e.g. "운영" inside
// "인프라운영팀"). Route() strips these before re-checking vectorKeywords so that overlap
// alone doesn't force VECTOR.
var departmentLike = regexp.MustCompile(`[\x{AC00}-\x{D7A3}a-z0-9]+(?:사업부|부서|팀)`)

// matchesProductNumeric mirrors PRODUCT_NUMERIC.containsMatchIn(q), including the
// `(?<![a-z0-9_-])` guard before the leading digit.
func matchesProductNumeric(q string) bool {
	locs := productNumericCore.FindAllStringIndex(q, -1)
	for _, loc := range locs {
		start := loc[0]
		if start == 0 {
			return true
		}
		prevChar := q[start-1 : start]
		if !lookbehindExcluded.MatchString(prevChar) {
			return true
		}
	}
	return false
}

// RouteFallback mirrors the RouteFallback fun interface: classify a question when no
// deterministic keyword rule matched.
type RouteFallback interface {
	Classify(question string) []Route
}

// RuleBasedRouter ports RuleBasedRouter.kt 1:1: deterministic keyword/regex routing,
// with an optional fallback for the no-match and single-match-composition cases.
type RuleBasedRouter struct {
	Fallback RouteFallback
}

func containsAny(q string, keywords []string) bool {
	for _, k := range keywords {
		if strings.Contains(q, k) {
			return true
		}
	}
	return false
}

// Route mirrors fun route(question: String): List<Route>.
func (r *RuleBasedRouter) Route(question string) []Route {
	q := strings.ToLower(question)
	// "인프라운영팀"처럼 부서/조직명 안에 vectorKeywords 단어("운영" 등)가 우연히
	// 포함된 경우, 그 매칭만으로 VECTOR를 확정하면 안 된다 — 부서명 토큰 자체를
	// 지우고 나머지 텍스트로만 vectorKeywords를 재검사한다.
	qWithoutDeptNames := departmentLike.ReplaceAllString(q, " ")

	var routes []Route
	if containsAny(q, sqlKeywords) || matchesProductNumeric(q) {
		routes = append(routes, RouteSQL)
	}
	if containsAny(q, graphKeywords) {
		routes = append(routes, RouteGraph)
	}
	if containsAny(qWithoutDeptNames, vectorKeywords) {
		routes = append(routes, RouteVector)
	}

	if len(routes) == 0 {
		if r.Fallback != nil {
			return r.Fallback.Classify(question)
		}
		return []Route{RouteVector}
	}

	if len(routes) == 1 && r.Fallback != nil && containsAny(q, compositionCues) {
		return dedupRoutes(append(routes, r.Fallback.Classify(question)...))
	}

	return routes
}

func dedupRoutes(routes []Route) []Route {
	seen := make(map[Route]bool)
	var out []Route
	for _, r := range routes {
		if !seen[r] {
			seen[r] = true
			out = append(out, r)
		}
	}
	return out
}
