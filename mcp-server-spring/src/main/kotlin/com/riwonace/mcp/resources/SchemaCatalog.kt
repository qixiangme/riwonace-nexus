package com.riwonace.mcp.resources

/** NL2SQL이 사용할 데이터베이스 스키마를 MCP Resource용 JSON으로 제공한다. */
fun interface SchemaCatalog {
    fun readJson(): String
}
