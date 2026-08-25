package com.riwonace.agent.mcp

/** 에이전트가 사용하는 데이터 계약. 테스트에서는 장애를 주입하는 구현으로 교체할 수 있다. */
interface DataToolGateway {
    fun vectorSearch(query: String, topK: Int = 4): String
    fun runSql(sql: String): String
    fun kgSearch(query: String): String
    fun schema(): String
}
