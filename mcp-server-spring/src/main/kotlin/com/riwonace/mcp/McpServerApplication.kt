package com.riwonace.mcp

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import com.riwonace.mcp.tools.RetrievalTools

/** MCP 검색·SQL 도구 서버의 Spring Boot 구성 진입점. */
@SpringBootApplication
class McpServerApplication {

    /** [RetrievalTools]의 `@Tool` 메서드만 MCP 호출 가능한 도구로 등록한다. */
    @Bean
    fun riwonaceTools(retrievalTools: RetrievalTools): ToolCallbackProvider =
        MethodToolCallbackProvider.builder()
            .toolObjects(retrievalTools)
            .build()
}

fun main(args: Array<String>) {
    runApplication<McpServerApplication>(*args)
}
