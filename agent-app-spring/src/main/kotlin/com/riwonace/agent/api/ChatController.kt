package com.riwonace.agent.api

import com.riwonace.agent.core.ExecutionTrace
import com.riwonace.agent.mcp.McpGateway
import com.riwonace.agent.router.Route
import com.riwonace.agent.service.AgentService
import com.riwonace.agent.service.AgentServiceV2
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * POST `/api/chat` 요청 본문.
 *
 * 보안 강화:
 * - 빈 문자열 거부 (@NotBlank)
 * - 최대 길이 제한으로 DoS 방지 (@Size)
 */
data class ChatRequest(
    @field:NotBlank(message = "질문은 공백일 수 없습니다.")
    @field:Size(min = 2, max = 2000, message = "질문은 2자 이상 2000자 이하여야 합니다.")
    val question: String,
)

/**
 * v2 응답 형식 (실행 추적 포함)
 */
data class ChatResponseV2(
    val answer: String,
    val routes: List<Route>,
    val toolCalls: List<String>,
    val contextSources: List<String>,
    val latencyMs: Long,
    /** 선택된 모델 */
    val selectedModel: String?,
    /** 클레임 커버리지 (0.0 ~ 1.0) */
    val claimCoverage: Double?,
    /** 모델 에스컬레이션 발생 여부 */
    val wasEscalated: Boolean,
    /** 실행 추적 (trace=true일 때만 포함) */
    val trace: ExecutionTraceSummary?,
)

/**
 * 실행 추적 요약 (UI 표시용)
 */
data class ExecutionTraceSummary(
    /** 총 노드 수 */
    val totalNodes: Int,
    /** 성공 노드 수 */
    val successNodes: Int,
    /** 실패 노드 수 */
    val failedNodes: Int,
    /** 스킵 노드 수 */
    val skippedNodes: Int,
    /** 노드별 실행 정보 */
    val nodes: List<NodeTraceSummary>,
    /** 계획 생성 시간 (ms) */
    val planningTimeMs: Long,
    /** 질문 의도 */
    val intent: String,
    /** 복잡도 */
    val complexity: Double,
)

data class NodeTraceSummary(
    val id: String,
    val route: String,
    val status: String,
    val durationMs: Long,
    val resultSummary: String?,
)

/**
 * 에이전트 오케스트레이션을 HTTP API로 노출하는 어댑터.
 *
 * v1: 기존 AgentService (라우팅 → 병렬 호출 → 큐레이션)
 * v2: AgentServiceV2 (프로파일링 → DAG → 최적화 → 검증)
 */
@RestController
@RequestMapping("/api")
class ChatController(
    private val agentService: AgentService,
    private val agentServiceV2: AgentServiceV2,
    private val gateway: McpGateway,
    @Value("\${agent.v2.enabled:true}")
    private val v2Enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * v1 엔드포인트: 기존 호환성 유지
     */
    @PostMapping("/chat")
    fun chat(@Valid @RequestBody request: ChatRequest): Any {
        val question = sanitizeInput(request.question)
        return if (v2Enabled) {
            val result = agentServiceV2.chat(question)
            ChatResponseV2(
                answer = result.answer,
                routes = result.routes,
                toolCalls = result.toolCalls,
                contextSources = result.contextSources,
                latencyMs = result.latencyMs,
                selectedModel = result.selectedModel,
                claimCoverage = result.claimCoverage,
                wasEscalated = result.wasEscalated,
                trace = null, // 기본 엔드포인트에서는 추적 미포함
            )
        } else {
            agentService.chat(question)
        }
    }

    /**
     * v2 엔드포인트: 실행 추적 포함
     */
    @PostMapping("/chat/v2")
    fun chatV2(
        @Valid @RequestBody request: ChatRequest,
        @RequestParam(defaultValue = "false") trace: Boolean,
    ): ChatResponseV2 {
        val question = sanitizeInput(request.question)
        val result = agentServiceV2.chat(question)

        return ChatResponseV2(
            answer = result.answer,
            routes = result.routes,
            toolCalls = result.toolCalls,
            contextSources = result.contextSources,
            latencyMs = result.latencyMs,
            selectedModel = result.selectedModel,
            claimCoverage = result.claimCoverage,
            wasEscalated = result.wasEscalated,
            trace = if (trace && result.trace != null) toTraceSummary(result.trace) else null,
        )
    }

    /** MCP 연결 상태와 K/A 분리를 진단할 수 있도록 실행 도구와 지식 리소스를 반환한다. */
    @GetMapping("/tools")
    fun tools(): Map<String, Any> =
        mapOf(
            "mcpTools" to gateway.listToolNames(),
            "mcpResources" to gateway.listResourceUris(),
        )

    /**
     * v2 상태 확인
     */
    @GetMapping("/v2/status")
    fun v2Status(): Map<String, Any> = mapOf(
        "v2Enabled" to v2Enabled,
        "features" to listOf(
            "Adaptive Model Escalation",
            "Execution DAG",
            "Evidence Budget Optimizer",
            "Answerability Gate",
            "Recovery Policy",
        ),
    )

    private fun toTraceSummary(trace: ExecutionTrace): ExecutionTraceSummary {
        return ExecutionTraceSummary(
            totalNodes = trace.plan.nodes.size,
            successNodes = trace.nodeTraces.count { it.status.name == "SUCCESS" },
            failedNodes = trace.nodeTraces.count { it.status.name == "FAILED" },
            skippedNodes = trace.nodeTraces.count { it.status.name == "SKIPPED" },
            nodes = trace.nodeTraces.map { node ->
                NodeTraceSummary(
                    id = node.nodeId,
                    route = node.route.name,
                    status = node.status.name,
                    durationMs = (node.endedAt ?: System.currentTimeMillis()) - node.startedAt,
                    resultSummary = node.resultSummary,
                )
            },
            planningTimeMs = trace.plan.planningTimeMs,
            intent = trace.plan.profile.intent.name,
            complexity = trace.plan.profile.complexity,
        )
    }

    /**
     * 입력값을 정제한다.
     * - 앞뒤 공백 제거
     * - 제어 문자 제거
     */
    private fun sanitizeInput(input: String): String {
        return input.trim()
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "") // 제어 문자 제거
    }
}
