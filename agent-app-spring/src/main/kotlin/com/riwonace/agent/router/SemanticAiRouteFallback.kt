package com.riwonace.agent.router

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * 도구의 데이터 형태와 연산 경계를 설명하는 의미 기반 분류 폴백.
 *
 * 평가 문항이나 정답을 프롬프트에 넣지 않고 실제 MCP 데이터 계약과 균형 예시를 사용한다.
 * 모델 출력은 정확한 영문 라벨만 허용하며, 호출/파싱 실패는 VECTOR로 안전하게 복구한다.
 */
@Component
@ConditionalOnProperty(name = ["agent.router.fallback"], havingValue = "semantic-ai")
class SemanticAiRouteFallback(private val chatClient: ChatClient) : RouteFallback {

    private val log = LoggerFactory.getLogger(javaClass)
    private val template = ClassPathResource("router/semantic-ai-prompt.txt")
        .inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun classify(question: String): List<Route> {
        val raw = try {
            chatClient.prompt()
                .user(template.replace("{{question}}", question))
                .call()
                .content()
                .orEmpty()
        } catch (e: Exception) {
            log.warn("의미 라우팅 호출 실패, VECTOR로 대체: {}", e.message)
            ""
        }

        val resolved = parseExactRoutes(raw)
        if (resolved == null) {
            log.warn("의미 라우팅 출력 파싱 실패, VECTOR로 대체: {}", raw.take(100))
        }
        return resolved ?: listOf(Route.VECTOR)
    }
}

internal fun parseExactRoutes(raw: String): List<Route>? {
    val labels = raw.trim().uppercase().split(',').map(String::trim)
    if (labels.isEmpty() || labels.any(String::isEmpty)) return null
    val routes = labels.map { label -> Route.entries.firstOrNull { it.name == label } ?: return null }
    return routes.distinct().takeIf { it.isNotEmpty() }
}
