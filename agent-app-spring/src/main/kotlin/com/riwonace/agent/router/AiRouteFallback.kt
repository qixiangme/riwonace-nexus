package com.riwonace.agent.router

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 키워드가 없을 때만 소형 LLM에게 라우트 하나를 묻는다.
 * function-calling(도구 인자 생성)이 아니라 세 단어 중 하나만 고르는 좁은 분류라
 * 1B에도 상대적으로 안정적이지만, 확률적 판단이므로 파싱 실패 시 반드시 VECTOR로 떨어진다.
 */
@Component
@ConditionalOnProperty(name = ["agent.router.fallback"], havingValue = "ai")
class AiRouteFallback(private val chatClient: ChatClient) : RouteFallback {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun classify(question: String): List<Route> {
        val raw = try {
            chatClient.prompt()
                .system(
                    "질문을 SQL, VECTOR, GRAPH 중 하나로 분류한다. " +
                        "숫자 집계·개수·순위 질문이면 SQL, 개념 설명·방법·장애 대응 질문이면 VECTOR, " +
                        "누구·소속·관계·사용 여부 질문이면 GRAPH. " +
                        "다른 말 없이 SQL, VECTOR, GRAPH 중 한 단어만 출력한다.",
                )
                .user(
                    "예시1 — 이번 분기 들어온 돈이 얼마인지 알고 싶다 → SQL\n" +
                        "예시2 — 이 기능이 왜 이렇게 동작하는지 궁금하다 → VECTOR\n" +
                        "예시3 — 이 사람이 어느 팀에 속해 있는지 알고 싶다 → GRAPH\n\n" +
                        "질문: $question\n분류:",
                )
                .call()
                .content()
                .orEmpty()
        } catch (e: Exception) {
            log.warn("AI 라우팅 폴백 호출 실패, VECTOR로 대체: {}", e.message)
            ""
        }

        val resolved = Route.entries.firstOrNull { raw.uppercase().contains(it.name) }
        if (resolved == null) log.warn("AI 라우팅 폴백 출력 파싱 실패, VECTOR로 대체: {}", raw.take(100))
        return listOf(resolved ?: Route.VECTOR)
    }
}
