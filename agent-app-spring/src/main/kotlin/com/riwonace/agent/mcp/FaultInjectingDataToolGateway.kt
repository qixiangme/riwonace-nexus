package com.riwonace.agent.mcp

import com.riwonace.agent.router.Route
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

enum class FaultMode { EXCEPTION, MALFORMED, EMPTY }

/**
 * 평가 프로필에서만 활성화되는 비HTTP 장애 주입기.
 * 운영 공격면을 추가하지 않고 특정 데이터 경로의 예외·손상 응답·빈 결과를 재현한다.
 */
@Primary
@Component
@ConditionalOnProperty(name = ["agent.evaluation.fault.enabled"], havingValue = "true")
class FaultInjectingDataToolGateway(
    @Qualifier("mcpGateway") private val delegate: DataToolGateway,
    @Value("\${agent.evaluation.fault.routes:}") routeNames: String,
    @Value("\${agent.evaluation.fault.mode:EXCEPTION}") modeName: String,
) : DataToolGateway {
    private val routes = routeNames.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { Route.valueOf(it.uppercase()) }
        .toSet()
    private val mode = FaultMode.valueOf(modeName.trim().uppercase())

    override fun vectorSearch(query: String, topK: Int): String =
        inject(Route.VECTOR, "[]") { delegate.vectorSearch(query, topK) }

    override fun runSql(sql: String): String =
        inject(Route.SQL, "{\"rows\":[]}") { delegate.runSql(sql) }

    override fun kgSearch(query: String): String =
        inject(Route.GRAPH, "[]") { delegate.kgSearch(query) }

    override fun schema(): String = delegate.schema()

    private fun inject(route: Route, emptyValue: String, call: () -> String): String {
        if (route !in routes) return call()
        return when (mode) {
            FaultMode.EXCEPTION -> error("evaluation fault injected for $route")
            FaultMode.MALFORMED -> "{malformed"
            FaultMode.EMPTY -> emptyValue
        }
    }
}
