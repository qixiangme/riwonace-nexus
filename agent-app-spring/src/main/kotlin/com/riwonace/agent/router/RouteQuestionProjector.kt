package com.riwonace.agent.router

import org.springframework.stereotype.Component

/**
 * 복합 질문을 MCP 도구의 데이터 경계별 하위 질문으로 투영한다.
 *
 * 평가 문항이나 정답을 열거하지 않는다. 일반적인 접속 경계, 정형 값·문서 내용·개체 관계라는
 * MCP 계약만 사용하며 분해 근거가 약하면 원문을 그대로 보존한다.
 */
@Component
class RouteQuestionProjector {

    fun project(question: String, route: Route): String {
        val fragments = question.split(BOUNDARY).map(String::trim).filter(String::isNotEmpty)
        if (fragments.size < 2) return question

        val cues = CUES.getValue(route)
        val selected = fragments.filter { fragment -> cues.any(fragment.lowercase()::contains) }
        if (selected.isEmpty() || selected.size == fragments.size) return question

        val projected = selected.joinToString(" 그리고 ")
        val entityAnchors = ENTITY_ANCHOR.findAll(question)
            .map { it.value }
            .filter { route != Route.VECTOR || it.startsWith("Product-", ignoreCase = true) }
            .toList()
        val categoryAnchors = if (route == Route.SQL) CATEGORY.findAll(question).map { it.value }.toList() else emptyList()
        val anchors = (entityAnchors + categoryAnchors).distinct()
        val missingAnchors = anchors.filterNot { projected.contains(it, ignoreCase = true) }
        return (missingAnchors + projected)
            .joinToString(" ")
            .trim()
            .trimEnd('.', '?', '!')
            .removeSuffix("알려줘")
            .removeSuffix("설명해줘")
            .removeSuffix("확인해줘")
            .removeSuffix("정리해줘")
            .trim()
            .plus(" 알려줘")
    }

    companion object {
        private val BOUNDARY = Regex(
            "\\s*(?:,(?=\\s)|그리고|함께|같이|동시에|구분하고|나눠\\s*말하고|한\\s*번에|(?:하며|이며|하고|쓰고|늘어나며)\\s+|\\s및\\s|(?<=[가-힣A-Za-z0-9])(?:과|와)\\s+)\\s*",
        )
        private val ENTITY_ANCHOR = Regex(
            "(?i)(?:Product|Client)-[A-Z0-9]+|[가-힣A-Za-z0-9]+(?:팀|부|부서)",
        )
        private val CATEGORY = Regex("(?i)(?:cloud|data|security|consulting)")
        private val CUES = mapOf(
            Route.SQL to listOf(
                "가격", "금액", "매출", "급여", "연봉", "평균", "합계", "총 ", "건", "개수",
                "계약", "출시 상태", "상태", "순위", "상위", "하위", "재고", "직원", "월 ",
                "price", "amount", "sales", "salary", "average", "sum", "count", "status",
            ),
            Route.VECTOR to listOf(
                "설치", "배포", "백업", "보관", "구동", "인증", "bearer", "api", "cpu", "hpa", "제안",
                "문서", "매뉴얼", "장애", "원인", "조치", "절차", "방법", "도구", "대상 고객",
                "install", "deploy", "backup", "guide", "manual", "incident",
            ),
            Route.GRAPH to listOf(
                "사용 고객", "이용 고객", "사용하는 고객", "쓰는 고객", "사용하는", "이용하는", "쓰는", "실제 사용", "실제 이용",
                "현재 사용", "현재 이용", "담당 직원", "담당자", "팀장", "이끄는 사람", "누구",
                "관계", "소속", "개발사", "라이선스", "using client", "owner", "relation",
            ),
        )
    }
}
