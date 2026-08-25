package com.riwonace.agent.router

import org.springframework.stereotype.Component

enum class Route { SQL, VECTOR, GRAPH }

/**
 * 규칙 기반 도구 라우터 (MCP Parallel 패턴).
 *
 * 소형 LLM의 불안정한 function-calling 대신 결정적 규칙으로 도구를 선택한다.
 * 복수 규칙에 걸리면 해당 도구들을 병렬 호출하고 결과를 통합한다.
 * 어떤 규칙에도 걸리지 않으면 `fallback`이 있으면 그쪽에 위임하고, 없으면 vector_search가 기본값이다.
 */
@Component
class RuleBasedRouter(private val fallback: RouteFallback? = null) {

    private val sqlKeywords = listOf(
        "몇", "개수", "수는", "평균", "합계", "총", "최대", "최소", "가장 비싼", "가장 싼",
        "가장 많은", "가장 높은", "순위", "상위", "하위", "목록", "리스트", "재고", "급여",
        "연봉", "매출", "주문", "계약", "티켓", "우선순위", "분기", "금액", "등록된",
        "가격", "출시 상태", "부서별", "언제 입사", "입사일", "모두 알려줘", "이름을 모두",
        "count", "average", "sum", "max", "min", "how many", "list", "top",
    )

    private val graphKeywords = listOf(
        "관계", "연관", "연결", "관련이", "관련된", "의존", "누가 만들", "누가 개발",
        "무엇을 개발", "어디서 만들", "개발사", "라이선스", "무슨 사이",
        "사용 중인", "사용하는", "사용 고객", "이용 고객", "실제 사용", "실제 이용",
        "이용하는", "사용자 고객",
        "소속", "담당", "팀장", "부서장", "이끄", "이슈",
        "relation", "related", "depends", "who made", "who developed",
    )

    private val vectorKeywords = listOf(
        "무엇", "뭐야", "뭔가요", "설명", "소개", "개념", "원리", "어떻게 동작", "왜",
        "장점", "단점", "차이", "정책", "가이드", "방법",
        "장애", "사례", "내용", "취약점", "미팅", "논의", "제안", "매뉴얼", "인증",
        "설치", "배포", "운영", "보관", "구동", "튜닝", "cpu", "hpa", "컨테이너",
        "what is", "explain", "describe", "how does", "why",
    )

    fun route(question: String): List<Route> {
        val q = question.lowercase()
        // "인프라운영팀"처럼 부서/조직명 안에 vectorKeywords 단어("운영" 등)가 우연히
        // 포함된 경우, 그 매칭만으로 VECTOR를 확정하면 안 된다 — 부서명 토큰 자체를
        // 지우고 나머지 텍스트로만 vectorKeywords를 재검사한다.
        val qWithoutDeptNames = DEPARTMENT_LIKE.replace(q, " ")
        val routes = buildList {
            if (sqlKeywords.any { q.contains(it) } || PRODUCT_NUMERIC.containsMatchIn(q)) add(Route.SQL)
            if (graphKeywords.any { q.contains(it) }) add(Route.GRAPH)
            if (vectorKeywords.any { qWithoutDeptNames.contains(it) }) add(Route.VECTOR)
        }
        if (routes.isEmpty()) return fallback?.classify(question) ?: listOf(Route.VECTOR)

        // 결정 규칙 하나만 잡혔더라도 질문이 여러 요구를 접속하면 의미 분류로 누락 경로만 보강한다.
        // 이미 두 경로 이상이 명확하면 추가 LLM 호출 없이 결정 결과를 유지한다.
        if (routes.size == 1 && fallback != null && COMPOSITION_CUES.any(q::contains)) {
            return (routes + fallback.classify(question)).distinct()
        }
        return routes
    }

    companion object {
        private val COMPOSITION_CUES = listOf("함께", "같이", "동시에", "한 번에", "구분", "그리고", " 및 ", "와 ", "과 ")
        private val PRODUCT_NUMERIC = Regex("(?<![a-z0-9_-])\\d[\\d,]*(?:\\.\\d+)?(?:원|만원|%|인|개|건|명)?[^.?!]{0,20}(?:제품|product)")
        private val DEPARTMENT_LIKE = Regex("[가-힣a-z0-9]+(?:사업부|부서|팀)")
    }
}
