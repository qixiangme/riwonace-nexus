package com.riwonace.agent

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 기동 시 워밍업 호출 1회 — 첫 사용자 질문이 모델 로딩(콜드 스타트 ~45초)을
 * 맞지 않게 한다. 실패해도 기동은 막지 않는다 (Ollama가 늦게 떠도 무방).
 */
@Component
class ModelWarmup(private val chatClient: ChatClient) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        Thread {
            try {
                chatClient.prompt()
                    .user("hi")
                    .options(OllamaOptions.builder().numPredict(1).build())
                    .call()
                    .content()
                log.info("모델 워밍업 완료")
            } catch (e: Exception) {
                log.warn("모델 워밍업 실패 (Ollama 미기동일 수 있음): {}", e.message)
            }
        }.apply { isDaemon = true }.start()
    }
}
