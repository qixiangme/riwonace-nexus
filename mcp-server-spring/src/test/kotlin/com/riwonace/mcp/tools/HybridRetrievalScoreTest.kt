package com.riwonace.mcp.tools

import kotlin.test.Test
import kotlin.test.assertTrue

class HybridRetrievalScoreTest {
    @Test
    fun `같은 벡터 점수면 제품과 설치 단서를 더 포함한 문서를 우선한다`() {
        val query = "Product-C1의 설치에 필요한 컨테이너 도구"
        val install = lexicalCoverage(query, "Product-C1 설치에는 Docker 컨테이너 도구가 필요하다")
        val incident = lexicalCoverage(query, "Product-C1 장애 보고서와 복구 시간")
        assertTrue(install > incident)
    }

    @Test
    fun `한국어 조사가 달라도 백업 단서를 일치시킨다`() {
        val relevant = lexicalCoverage("백업하고 보관 기간", "백업이 실행되며 보관은 26일이다")
        val noise = lexicalCoverage("백업하고 보관 기간", "회의 일정과 참석자")
        assertTrue(relevant > noise)
    }
}
