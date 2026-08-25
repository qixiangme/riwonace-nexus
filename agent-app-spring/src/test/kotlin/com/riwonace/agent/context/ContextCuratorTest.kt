package com.riwonace.agent.context

import com.riwonace.agent.router.Route
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextCuratorTest {

    private val curator = ContextCurator(budgetChars = 300)

    @Test
    fun `문자 예산을 초과하는 항목은 제외된다`() {
        val items = listOf(
            ContextItem("a", "x".repeat(200), 0.9),
            ContextItem("b", "y".repeat(200), 0.8),
            ContextItem("c", "z".repeat(50), 0.7),
        )
        val curated = curator.curate(items, listOf(Route.VECTOR))
        assertTrue(curated.sumOf { it.text.length } <= 300)
        assertEquals("a", curated.first().source)
    }

    @Test
    fun `첫 항목이 예산보다 크면 경계에서 잘라 상한을 지킨다`() {
        val curated = curator.curate(
            listOf(ContextItem("oversize", "긴 문장입니다. ".repeat(80), 0.9)),
            listOf(Route.VECTOR),
        )

        assertTrue(curated.single().text.length <= 300)
        assertTrue(curated.single().text.endsWith("…(truncated)"))
    }

    @Test
    fun `SQL 라우트에서는 sql 소스가 가중되어 최우선 배치된다`() {
        val items = listOf(
            ContextItem("doc", "문서 내용", 0.9),
            ContextItem("sql", "SQL 결과", 0.7),
        )
        val curated = curator.curate(items, listOf(Route.SQL))
        assertEquals("sql", curated.first().source)
    }

    @Test
    fun `3개 이상이면 차상위 항목이 맨 뒤로 배치된다 (Lost in the Middle 완화)`() {
        val items = listOf(
            ContextItem("1st", "a", 0.9),
            ContextItem("2nd", "b", 0.8),
            ContextItem("3rd", "c", 0.7),
        )
        val curated = curator.curate(items, listOf(Route.VECTOR))
        assertEquals("1st", curated.first().source)
        assertEquals("2nd", curated.last().source)
    }

    @Test
    fun `관련도 하한선 미달 항목은 예산이 남아도 버려진다`() {
        val items = listOf(
            ContextItem("relevant", "관련 문서", 0.8),
            ContextItem("noise", "무관한 문서", 0.1),
        )
        val curated = curator.curate(items, listOf(Route.VECTOR))
        assertEquals(listOf("relevant"), curated.map { it.source })
    }

    @Test
    fun `SQL 단독 라우트는 절반 예산으로 줄어든다`() {
        val items = listOf(
            ContextItem("sql", "s".repeat(140), 1.0),
            ContextItem("doc", "d".repeat(140), 0.9),
        )
        // 전체 예산 300, SQL 단독이면 150 → 두 번째 항목은 탈락 (sql이 가중되어 선두)
        val curated = curator.curate(items, listOf(Route.SQL))
        assertEquals(listOf("sql"), curated.map { it.source })
        assertTrue(curated.sumOf { it.text.length } <= 150)
    }

    @Test
    fun `0 이하 예산 설정은 시작 시 거부한다`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { ContextCurator(budgetChars = 0) }
    }

    @Test
    fun `중복 컨텍스트는 제거된다`() {
        val dup = "같은 내용의 문서입니다. ".repeat(10)
        val items = listOf(
            ContextItem("a", dup, 0.9),
            ContextItem("b", dup, 0.8),
        )
        assertEquals(1, curator.curate(items, listOf(Route.VECTOR)).size)
    }

    @Test
    fun `ALL 정책은 점수 하한과 중복 제거 없이 검색 순서를 보존한다`() {
        val all = ContextCurator(300, "ALL_WITH_HARD_CAP")
        val duplicate = "같은 문서"
        val selected = all.curate(
            "질문",
            listOf(
                ContextItem("first", duplicate, 0.1),
                ContextItem("second", duplicate, 0.1),
            ),
            listOf(Route.VECTOR),
        )

        assertEquals(listOf("first", "second"), selected.map { it.source })
    }

    @Test
    fun `COVERAGE 정책은 질문 엔티티를 새로 포함하는 근거를 중복 근거보다 우선한다`() {
        val coverage = ContextCurator(500, "COVERAGE")
        val selected = coverage.curate(
            "Product-C1 재시작 절차와 담당 팀",
            listOf(
                ContextItem("doc-a", "Product-C1 재시작 절차는 rollout restart다", 0.9),
                ContextItem("doc-duplicate", "Product-C1 재시작 절차는 rollout restart다", 0.89),
                ContextItem("knowledge-graph", "Product-C1 담당 팀은 플랫폼팀이다", 0.8),
            ),
            listOf(Route.VECTOR, Route.GRAPH),
        )

        assertEquals(listOf("doc-a", "knowledge-graph"), selected.map { it.source })
    }

    @Test
    fun `COVERAGE 복합 질문은 경로별 최고 효용 근거 하나로 잡음을 제한한다`() {
        val coverage = ContextCurator(1000, "COVERAGE")
        val selected = coverage.curate(
            "Product-C1 가격과 설치 도구",
            listOf(
                ContextItem("sql", "Product-C1 price_monthly=350", 1.0),
                ContextItem("DOC-install", "Product-C1 설치에는 Docker가 필요하다", 0.9),
                ContextItem("DOC-proposal", "Product-C1 제안 비용은 8000이다", 0.8),
            ),
            listOf(Route.SQL, Route.VECTOR),
        )

        assertEquals(listOf("sql", "DOC-install"), selected.map { it.source })
    }

    @Test
    fun `알 수 없는 평가 정책은 시작 시 거부한다`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { ContextCurator(300, "invented") }
    }
}
