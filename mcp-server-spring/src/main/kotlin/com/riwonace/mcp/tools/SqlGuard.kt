package com.riwonace.mcp.tools

/**
 * NL2SQL로 생성된 SQL을 실행 전에 검증하는 읽기 전용 가드.
 * LLM이 생성한 SQL은 신뢰할 수 없으므로 SELECT 단일 문장만 허용한다.
 *
 * 보안 강화:
 * - 정규식 대신 토큰화 기반 파싱으로 우회 공격 방어
 * - 위험 함수 호출 차단 (pg_sleep, pg_read_file 등)
 * - 대소문자/주석 삽입 우회 차단
 */
object SqlGuard {

    private val FORBIDDEN_KEYWORDS = setOf(
        "insert", "update", "delete", "drop", "truncate", "alter", "create",
        "grant", "revoke", "copy", "vacuum", "do", "call", "execute",
        "merge", "set", "pg_sleep", "into"
    )

    private val DANGEROUS_FUNCTIONS = setOf(
        "pg_sleep", "pg_stat_file", "pg_read_file", "pg_ls_dir",
        "lo_import", "lo_export", "dblink", "dblink_exec",
        "pg_terminate_backend", "pg_cancel_backend"
    )

    private val ALLOWED_STARTERS = setOf("select", "with")
    private const val DEFAULT_LIMIT = 50

    /**
     * 검증에 성공하면 실행 가능한 SQL(LIMIT 보강 포함)을 반환하고,
     * 실패하면 IllegalArgumentException을 던진다.
     */
    fun sanitize(rawSql: String): String {
        var sql = rawSql.trim().removeSuffix(";").trim()

        require(sql.isNotBlank()) { "SQL이 비어 있습니다." }
        require(!sql.contains(";")) { "복수 문장 SQL은 허용되지 않습니다." }

        // 주석 차단 (정규식 우회 방지)
        require(!sql.contains("--")) { "주석(--) 포함 SQL은 허용되지 않습니다." }
        require(!sql.contains("/*")) { "주석(/*) 포함 SQL은 허용되지 않습니다." }

        // 토큰화 기반 검증
        val tokens = tokenize(sql)
        require(tokens.isNotEmpty()) { "SQL 토큰이 비어 있습니다." }

        // SELECT/WITH로 시작하는지 확인
        val firstToken = tokens.first().lowercase()
        require(firstToken in ALLOWED_STARTERS) {
            "SELECT 또는 WITH ... SELECT 문만 실행할 수 있습니다."
        }

        // 금지 키워드 검사 (토큰 단위로 정확히 매칭)
        for (token in tokens) {
            val lower = token.lowercase()
            require(lower !in FORBIDDEN_KEYWORDS) {
                "읽기 전용 정책상 '$token' 키워드는 허용되지 않습니다."
            }
        }

        // 위험 함수 호출 검사
        val sqlUpper = sql.uppercase()
        for (func in DANGEROUS_FUNCTIONS) {
            require(!sqlUpper.contains("${func.uppercase()}(")) {
                "'$func' 함수는 실행할 수 없습니다."
            }
        }

        // LIMIT 자동 추가
        if (!tokens.any { it.lowercase() == "limit" }) {
            sql = "$sql LIMIT $DEFAULT_LIMIT"
        }

        return sql
    }

    /**
     * SQL을 토큰으로 분리한다.
     * 공백, 괄호, 쉼표, 연산자를 구분자로 사용.
     */
    private fun tokenize(sql: String): List<String> {
        return sql.split(Regex("[\\s(),=<>!+\\-*/|&^~]+"))
            .filter { it.isNotBlank() }
    }
}
