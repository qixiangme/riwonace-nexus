package com.riwonace.agent.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresSqlNormalizerTest {
    private val normalizer = PostgresSqlNormalizer()

    @Test
    fun `MySQL 식별자 백틱을 PostgreSQL 큰따옴표로 바꾼다`() {
        assertEquals(
            "SELECT \"price_monthly\" FROM products WHERE name = 'Product-C1'",
            normalizer.normalize("SELECT `price_monthly` FROM products WHERE name = 'Product-C1'"),
        )
    }

    @Test
    fun `문자열 리터럴과 일반 PostgreSQL은 보존한다`() {
        val sql = "SELECT name FROM products WHERE name = 'Product-C1'"
        assertEquals(sql, normalizer.normalize(sql))
    }
}
