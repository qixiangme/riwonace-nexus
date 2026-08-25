package com.riwonace.agent.router

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class RouteFallbackConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(RouterConfiguration::class.java)

    @Test
    fun `fallback 설정이 비어 있으면 TF-IDF bean을 만들지 않는다`() {
        contextRunner.run { context ->
            assertThat(context).doesNotHaveBean(TfIdfRouter::class.java)
            assertThat(context).hasSingleBean(RuleBasedRouter::class.java)
            assertThat(context.getBean(RuleBasedRouter::class.java).route("안녕하세요"))
                .containsExactly(Route.VECTOR)
        }
    }

    @Test
    fun `fallback이 tfidf일 때만 TF-IDF bean을 주입한다`() {
        contextRunner
            .withPropertyValues("agent.router.fallback=tfidf")
            .run { context ->
                assertThat(context).hasSingleBean(TfIdfRouter::class.java)
                assertThat(context).hasSingleBean(RouteFallback::class.java)
                assertThat(context).hasSingleBean(RuleBasedRouter::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RuleBasedRouter::class, TfIdfRouter::class)
    private class RouterConfiguration
}
