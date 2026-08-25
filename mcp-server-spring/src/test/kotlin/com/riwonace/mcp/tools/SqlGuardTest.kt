package com.riwonace.mcp.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlGuardTest {

    @Test
    fun `단순 SELECT는 LIMIT이 보강되어 통과한다`() {
        val sql = SqlGuard.sanitize("SELECT name, salary FROM employees WHERE dept = '플랫폼팀'")
        assertTrue(sql.endsWith("LIMIT 50"))
    }

    @Test
    fun `이미 LIMIT이 있으면 그대로 통과한다`() {
        val sql = SqlGuard.sanitize("select * from products order by price desc limit 5;")
        assertEquals("select * from products order by price desc limit 5", sql)
    }

    @Test
    fun `WITH 절 SELECT도 허용한다`() {
        val sql = SqlGuard.sanitize("WITH t AS (SELECT dept FROM employees) SELECT dept FROM t")
        assertTrue(sql.startsWith("WITH"))
    }

    @Test
    fun `DML과 DDL은 거부한다`() {
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("DELETE FROM employees") }
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("DROP TABLE products") }
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("UPDATE products SET price = 0") }
    }

    @Test
    fun `다중 문장과 주석 인젝션은 거부한다`() {
        assertThrows<IllegalArgumentException> {
            SqlGuard.sanitize("SELECT 1; DROP TABLE employees")
        }
        assertThrows<IllegalArgumentException> {
            SqlGuard.sanitize("SELECT * FROM employees -- hidden")
        }
    }

    @Test
    fun `SELECT로 위장한 위험 함수는 거부한다`() {
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("SELECT pg_sleep(10)") }
    }

    @Test
    fun `대소문자 섞은 우회 시도 차단`() {
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("sElEcT DeLeTe FROM users") }
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("SELECT InSeRt FROM users") }
    }

    @Test
    fun `위험 함수 우회 시도 차단`() {
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("SELECT PG_SLEEP(5)") }
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("SELECT pg_read_file('/etc/passwd')") }
        assertThrows<IllegalArgumentException> { SqlGuard.sanitize("SELECT lo_import('/etc/passwd')") }
    }

    @Test
    fun `서브쿼리 내 위험 함수 차단`() {
        assertThrows<IllegalArgumentException> {
            SqlGuard.sanitize("SELECT * FROM (SELECT pg_sleep(1)) t")
        }
    }

    @Test
    fun `INTO 키워드 차단`() {
        assertThrows<IllegalArgumentException> {
            SqlGuard.sanitize("SELECT * INTO new_table FROM employees")
        }
    }
}
