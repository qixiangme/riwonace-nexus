package com.riwonace.agent

import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * HTTP 에이전트 애플리케이션의 구성 진입점.
 *
 * 모델 연결 설정은 Spring AI 자동 구성에 맡기고, 서비스들이 공유할 [ChatClient]만 Bean으로 노출한다.
 */
@SpringBootApplication
class AgentApplication {

    /** Spring AI가 구성한 모델·옵션을 사용하는 공용 대화 클라이언트를 만든다. */
    @Bean
    fun chatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}

fun main(args: Array<String>) {
    runApplication<AgentApplication>(*args)
}
