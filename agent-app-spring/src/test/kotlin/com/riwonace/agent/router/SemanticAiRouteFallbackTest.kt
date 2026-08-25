package com.riwonace.agent.router

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SemanticAiRouteFallbackTest {

    @Test
    fun `정확한 라벨과 주변 공백만 허용한다`() {
        assertEquals(listOf(Route.SQL), parseExactRoutes(" SQL\n"))
        assertEquals(listOf(Route.VECTOR), parseExactRoutes("vector"))
        assertEquals(listOf(Route.GRAPH), parseExactRoutes("GRAPH"))
    }

    @Test
    fun `쉼표로 구분한 다중 라벨을 허용하고 중복을 제거한다`() {
        assertEquals(listOf(Route.SQL, Route.VECTOR), parseExactRoutes("SQL, VECTOR"))
        assertEquals(listOf(Route.GRAPH), parseExactRoutes("GRAPH,GRAPH"))
    }

    @Test
    fun `설명이나 허용되지 않은 출력은 거부한다`() {
        assertNull(parseExactRoutes("분류: SQL"))
        assertNull(parseExactRoutes("SQL 또는 GRAPH"))
        assertNull(parseExactRoutes("벡터"))
        assertNull(parseExactRoutes(""))
    }
}
