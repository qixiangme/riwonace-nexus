package com.riwonace.agent.mcp

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpSessionGuardTest {

    @Test
    fun `동시에 진입한 호출도 단일 MCP 세션에서는 하나씩 실행한다`() {
        val guard = McpSessionGuard()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        try {
            val futures = (1..2).map {
                executor.submit {
                    ready.countDown()
                    start.await()
                    guard.execute {
                        val current = active.incrementAndGet()
                        maximumActive.accumulateAndGet(current, ::maxOf)
                        Thread.sleep(50)
                        active.decrementAndGet()
                    }
                }
            }

            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, maximumActive.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
