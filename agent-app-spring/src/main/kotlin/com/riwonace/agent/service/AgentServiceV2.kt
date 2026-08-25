package com.riwonace.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.riwonace.agent.context.ContextItem
import com.riwonace.agent.core.*
import com.riwonace.agent.mcp.McpGateway
import com.riwonace.agent.router.Route
import com.riwonace.agent.sql.DeterministicSqlPlanner
import com.riwonace.agent.sql.FewShotSelector
import com.riwonace.agent.sql.SchemaLinker
import com.riwonace.agent.sql.SchemaPromptFormatter
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Architecture v2 에이전트 오케스트레이터.
 *
 * 기존 AgentService의 "라우팅 → 병렬 호출 → 큐레이션" 구조에서
 * "프로파일링 → 실행 계획 → DAG 실행 → 증거 최적화 → 검증 → 생성"으로 전환.
 *
 * 핵심 개선:
 * - Adaptive Model Escalation (1B → 4B → 7B)
 * - Execution DAG (의존 관계 기반 실행)
 * - Evidence Budget Optimizer (제약 최적화)
 * - Answerability Gate (클레임 커버리지 검증)
 * - Recovery Policy (실패별 복구 전략)
 */
@Service
class AgentServiceV2(
    private val planner: ExecutionPlanner,
    private val optimizer: EvidenceOptimizer,
    private val gate: AnswerabilityGate,
    private val recovery: RecoveryPolicy,
    private val escalator: ModelEscalator,
    private val gateway: McpGateway,
    private val chatClient: ChatClient,
    private val mapper: ObjectMapper,
    private val fewShotSelector: FewShotSelector,
    private val schemaLinker: SchemaLinker,
    private val schemaPromptFormatter: SchemaPromptFormatter,
    private val deterministicSqlPlanner: DeterministicSqlPlanner,
    @Value("\${agent.v2.enabled:true}")
    private val v2Enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(4)

    companion object {
        private const val MAX_SQL_RETRIES = 2
        private const val MAX_REESCALATION = 1
    }

    @PreDestroy
    fun shutdown() {
        log.info("AgentServiceV2 스레드 풀 종료")
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    /**
     * 확장된 응답 (실행 추적 포함)
     */
    data class AgentAnswerV2(
        val answer: String,
        val routes: List<Route>,
        val toolCalls: List<String>,
        val contextSources: List<String>,
        val latencyMs: Long,
        /** v2 전용: 실행 추적 정보 */
        val trace: ExecutionTrace?,
        /** v2 전용: 선택된 모델 */
        val selectedModel: String?,
        /** v2 전용: 클레임 커버리지 */
        val claimCoverage: Double?,
        /** v2 전용: 모델 에스컬레이션 여부 */
        val wasEscalated: Boolean,
    )

    /**
     * v2 파이프라인으로 질문 처리
     */
    fun chat(question: String): AgentAnswerV2 {
        val started = System.currentTimeMillis()
        val toolCalls = mutableListOf<String>()
        val nodeTraces = mutableListOf<NodeTrace>()

        // 1. 실행 계획 생성
        val plan = planner.plan(question)
        log.info("실행 계획 생성: {} 노드, 모델={}", plan.nodes.size, plan.selectedModel)

        // 2. DAG 실행
        val items = executePlan(plan, toolCalls, nodeTraces)

        // 3. 증거 최적화
        val optimized = optimizer.optimize(items, plan.profile.suggestedRoutes, plan.profile)
        log.info("증거 최적화: {} → {} 항목", items.size, optimized.selected.size)

        // 4. Answerability 검증
        val gateResult = gate.verify(question, optimized.selected, plan.profile)
        var finalEvidence = optimized.selected
        var wasEscalated = false

        // 5. 검증 실패 시 추가 조치
        when (gateResult.recommendedAction) {
            AnswerabilityGate.GateAction.SEARCH_MORE -> {
                log.info("추가 검색 필요: missing={}", gateResult.missingClaims)
                val additionalItems = searchAdditional(question, gateResult.missingClaims, toolCalls)
                val combined = optimizer.optimize(
                    items + additionalItems,
                    plan.profile.suggestedRoutes,
                    plan.profile,
                )
                finalEvidence = combined.selected
            }
            AnswerabilityGate.GateAction.DECLINE -> {
                return createDeclineResponse(question, plan, started, nodeTraces, toolCalls)
            }
            else -> { /* PROCEED or PARTIAL_ANSWER */ }
        }

        // 6. 답변 생성 (모델 에스컬레이션 포함)
        var modelSelection = escalator.selectModel(plan.profile)
        var answer = generateAnswer(question, finalEvidence, modelSelection.model)

        // 7. 품질 검증 및 재에스컬레이션
        val answerQuality = estimateAnswerQuality(answer, question)
        if (answerQuality < 0.6 || gateResult.claimCoverage < 0.7) {
            val reescalation = escalator.shouldReescalate(
                modelSelection,
                answerQuality,
                gateResult.claimCoverage,
            )
            if (reescalation != null) {
                log.info("모델 재에스컬레이션: {} → {}", modelSelection.model, reescalation.model)
                answer = generateAnswer(question, finalEvidence, reescalation.model)
                modelSelection = reescalation
                wasEscalated = true
            }
        }

        val totalDuration = System.currentTimeMillis() - started

        val trace = ExecutionTrace(
            plan = plan,
            nodeTraces = nodeTraces,
            totalDurationMs = totalDuration,
            finalAnswer = answer,
            evidenceSources = finalEvidence.map { it.source },
            claimCoverage = gateResult.claimCoverage,
        )

        return AgentAnswerV2(
            answer = answer,
            routes = plan.profile.suggestedRoutes,
            toolCalls = toolCalls,
            contextSources = finalEvidence.map { it.source },
            latencyMs = totalDuration,
            trace = trace,
            selectedModel = modelSelection.model,
            claimCoverage = gateResult.claimCoverage,
            wasEscalated = wasEscalated,
        )
    }

    /**
     * 실행 계획(DAG) 실행
     */
    private fun executePlan(
        plan: ExecutionPlan,
        toolCalls: MutableList<String>,
        nodeTraces: MutableList<NodeTrace>,
    ): List<ContextItem> {
        val results = mutableMapOf<String, Any?>()
        val items = mutableListOf<ContextItem>()

        // 의존성 기반 그룹화 (위상 정렬)
        val levels = groupByDependencyLevel(plan.nodes)

        for (level in levels) {
            // 같은 레벨은 병렬 실행
            val futures = level.map { node ->
                CompletableFuture.supplyAsync({
                    executeNode(node, results, plan.question, toolCalls, nodeTraces)
                }, executor)
            }

            // 레벨 완료 대기
            futures.forEach { future ->
                val nodeItems = future.join()
                items.addAll(nodeItems)
            }
        }

        return items
    }

    /**
     * 단일 노드 실행
     */
    private fun executeNode(
        node: PlanNode,
        results: MutableMap<String, Any?>,
        question: String,
        toolCalls: MutableList<String>,
        nodeTraces: MutableList<NodeTrace>,
    ): List<ContextItem> {
        val startedAt = System.currentTimeMillis()
        node.status = NodeStatus.RUNNING

        // 조건 확인
        if (node.condition != null && !evaluateCondition(node.condition, results)) {
            node.status = NodeStatus.SKIPPED
            nodeTraces.add(createNodeTrace(node, startedAt, "조건 미충족으로 스킵"))
            return emptyList()
        }

        // 입력 템플릿 변수 치환
        val input = resolveTemplate(node.inputTemplate, results)

        try {
            val items = collectContext(node.route, input, toolCalls)

            node.status = NodeStatus.SUCCESS
            node.result = NodeResult(
                success = true,
                data = items,
                durationMs = System.currentTimeMillis() - startedAt,
            )

            // 결과 바인딩
            if (node.outputBinding != null) {
                results[node.outputBinding] = items
            }

            nodeTraces.add(createNodeTrace(node, startedAt, "성공: ${items.size}개 항목"))
            return items
        } catch (e: Exception) {
            log.warn("노드 {} 실행 실패: {}", node.id, e.message)

            // 실패 분류 및 복구 시도
            val classification = recovery.classifyFailure(e.message ?: "unknown", node.route)
            val strategy = recovery.determineStrategy(classification, node.route, 0, input)

            node.status = NodeStatus.FAILED
            node.result = NodeResult(
                success = false,
                data = null,
                error = e.message,
                durationMs = System.currentTimeMillis() - startedAt,
                failureType = classification.type,
            )

            nodeTraces.add(createNodeTrace(node, startedAt, "실패: ${e.message?.take(50)}"))

            // 폴백 라우트 시도
            if (strategy.fallbackRoutes.isNotEmpty()) {
                for (fallbackRoute in strategy.fallbackRoutes) {
                    try {
                        val fallbackItems = collectContext(fallbackRoute, input, toolCalls)
                        if (fallbackItems.isNotEmpty()) {
                            recovery.recordRecoveryAttempt(classification.type, strategy.type, true)
                            return fallbackItems
                        }
                    } catch (_: Exception) {
                        // 다음 폴백 시도
                    }
                }
            }

            recovery.recordRecoveryAttempt(classification.type, strategy.type, false)
            return emptyList()
        }
    }

    /**
     * 컨텍스트 수집 (기존 AgentService 로직 재사용)
     */
    private fun collectContext(
        route: Route,
        question: String,
        toolCalls: MutableList<String>,
    ): List<ContextItem> {
        return when (route) {
            Route.VECTOR -> {
                toolCalls += "vector_search"
                parseVectorResult(gateway.vectorSearch(question))
            }
            Route.GRAPH -> {
                toolCalls += "kg_search"
                val result = gateway.kgSearch(question)
                listOf(ContextItem("knowledge-graph", "지식 그래프 관계:\n$result", 0.9))
            }
            Route.SQL -> {
                toolCalls += "get_schema"
                toolCalls += "run_sql"
                var sql = generateSql(question)
                var result = gateway.runSql(sql)
                var retryCount = 0

                while (retryCount < MAX_SQL_RETRIES) {
                    val feedback = analyzeSqlResult(result, question)
                    if (feedback == null) break

                    log.info("SQL 자가수정 {}/{}: {}", retryCount + 1, MAX_SQL_RETRIES, feedback.take(100))
                    toolCalls += "run_sql(retry-${retryCount + 1})"
                    sql = generateSql(question, previousAttempt = sql, error = feedback)
                    result = gateway.runSql(sql)
                    retryCount++
                }
                listOf(ContextItem("sql", "실행한 SQL: $sql\n조회 결과:\n${humanizeSqlResult(result)}", 1.0))
            }
        }
    }

    /**
     * 추가 검색 (누락 클레임 기반)
     */
    private fun searchAdditional(
        question: String,
        missingClaims: List<String>,
        toolCalls: MutableList<String>,
    ): List<ContextItem> {
        val items = mutableListOf<ContextItem>()

        for (claim in missingClaims.take(2)) { // 최대 2개만
            try {
                when {
                    claim.contains("entity_") -> {
                        val entity = claim.removePrefix("entity_")
                        toolCalls += "vector_search(additional)"
                        items.addAll(parseVectorResult(gateway.vectorSearch(entity)))
                    }
                    claim in listOf("quantity_value", "aggregated_value") -> {
                        // SQL로 재시도
                        toolCalls += "run_sql(additional)"
                        val sql = generateSql(question)
                        val result = gateway.runSql(sql)
                        items.add(ContextItem("sql", humanizeSqlResult(result), 0.8))
                    }
                    else -> {
                        toolCalls += "vector_search(additional)"
                        items.addAll(parseVectorResult(gateway.vectorSearch(question)))
                    }
                }
            } catch (e: Exception) {
                log.warn("추가 검색 실패: {}", e.message)
            }
        }

        return items
    }

    /**
     * 답변 품질 추정 (간단한 휴리스틱)
     */
    private fun estimateAnswerQuality(answer: String, question: String): Double {
        var score = 0.5

        // 답변 길이
        if (answer.length >= 50) score += 0.1
        if (answer.length >= 100) score += 0.1

        // 출처 언급
        if (answer.contains("출처") || answer.contains("[")) score += 0.1

        // 부정 응답 감지
        if (answer.contains("찾을 수 없") || answer.contains("알 수 없")) score -= 0.2

        // 숫자가 필요한 질문에 숫자 포함
        if ((question.contains("몇") || question.contains("얼마")) && answer.contains(Regex("\\d+"))) {
            score += 0.15
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 답변 불가 응답 생성
     */
    private fun createDeclineResponse(
        question: String,
        plan: ExecutionPlan,
        started: Long,
        nodeTraces: List<NodeTrace>,
        toolCalls: List<String>,
    ): AgentAnswerV2 {
        val answer = "죄송합니다. '$question'에 대해 충분한 정보를 찾지 못했습니다. " +
            "질문을 더 구체적으로 해주시거나 다른 방식으로 질문해 주세요."

        return AgentAnswerV2(
            answer = answer,
            routes = plan.profile.suggestedRoutes,
            toolCalls = toolCalls,
            contextSources = emptyList(),
            latencyMs = System.currentTimeMillis() - started,
            trace = ExecutionTrace(
                plan = plan,
                nodeTraces = nodeTraces,
                totalDurationMs = System.currentTimeMillis() - started,
                finalAnswer = answer,
                evidenceSources = emptyList(),
                claimCoverage = 0.0,
            ),
            selectedModel = plan.selectedModel,
            claimCoverage = 0.0,
            wasEscalated = false,
        )
    }

    // === Helper Methods ===

    private fun groupByDependencyLevel(nodes: List<PlanNode>): List<List<PlanNode>> {
        val levels = mutableListOf<List<PlanNode>>()
        val remaining = nodes.toMutableList()
        val completed = mutableSetOf<String>()

        while (remaining.isNotEmpty()) {
            val currentLevel = remaining.filter { node ->
                node.dependsOn.all { it in completed }
            }

            if (currentLevel.isEmpty() && remaining.isNotEmpty()) {
                // 순환 의존성 방지 - 나머지 모두 현재 레벨로
                levels.add(remaining.toList())
                break
            }

            levels.add(currentLevel)
            completed.addAll(currentLevel.map { it.id })
            remaining.removeAll(currentLevel.toSet())
        }

        return levels
    }

    private fun evaluateCondition(condition: ExecutionCondition, results: Map<String, Any?>): Boolean {
        val value = results[condition.variable]

        return when (condition.type) {
            ConditionType.NOT_EMPTY -> {
                when (value) {
                    null -> false
                    is List<*> -> value.isNotEmpty()
                    is String -> value.isNotBlank()
                    else -> true
                }
            }
            ConditionType.ON_FAILURE -> value == null
            ConditionType.ALWAYS -> true
            else -> true
        }
    }

    private fun resolveTemplate(template: String, results: Map<String, Any?>): String {
        var resolved = template
        val pattern = Regex("\\$\\{([^}]+)}")

        pattern.findAll(template).forEach { match ->
            val varName = match.groupValues[1]
            val value = results[varName]
            val replacement = when (value) {
                is List<*> -> value.filterIsInstance<ContextItem>().joinToString(", ") { it.text.take(50) }
                else -> value?.toString() ?: ""
            }
            resolved = resolved.replace(match.value, replacement)
        }

        return resolved
    }

    private fun createNodeTrace(node: PlanNode, startedAt: Long, resultSummary: String): NodeTrace {
        return NodeTrace(
            nodeId = node.id,
            route = node.route,
            status = node.status,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            resultSummary = resultSummary,
        )
    }

    // === SQL/Vector 처리 (기존 AgentService에서 이동) ===

    private fun generateSql(question: String, previousAttempt: String? = null, error: String? = null): String {
        if (previousAttempt == null) {
            deterministicSqlPlanner.plan(question)?.let { return it }
        }
        val rawSchema = gateway.schema()
        val schema = schemaPromptFormatter.format(rawSchema)
        val selectedExamples = fewShotSelector.selectExamples(question, topK = 1)
        val examplesBlock = fewShotSelector.formatExamplesForPrompt(selectedExamples)
        val schemaHints = schemaLinker.linkEntities(rawSchema, question)
        val hintBlock = schemaLinker.formatHintsForPrompt(schemaHints)

        val retryBlock = if (previousAttempt == null) ""
        else "직전 시도: $previousAttempt\n오류: ${error?.take(300)}\n오류를 고쳐서 다시 작성한다.\n\n"

        val raw = chatClient.prompt()
            .system(
                "너는 PostgreSQL 전문가다. 주어진 스키마만 사용해서 질문에 답하는 " +
                    "SELECT 문 한 문장만 출력한다. TABLE에 없는 식별자를 만들지 않고, " +
                    "JOIN은 FOREIGN KEYS에 제시된 관계만 사용한다. " +
                    "설명, 마크다운, 세미콜론 없이 SQL만 출력한다." +
                    if (schemaHints.isNotEmpty()) " 스키마 힌트에 표시된 테이블.컬럼과 값을 반드시 사용한다." else "",
            )
            .user(
                "스키마:\n$schema$hintBlock\n\n$examplesBlock\n\n${retryBlock}질문: $question\nSQL:",
            )
            .call()
            .content()
            .orEmpty()

        return raw.replace(Regex("```(sql)?", RegexOption.IGNORE_CASE), "").trim().removeSuffix(";")
    }

    @Suppress("UNCHECKED_CAST")
    private fun humanizeSqlResult(json: String): String = try {
        val parsed: Map<String, Any?> = mapper.readValue(
            json,
            mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
        )
        val rows = parsed["rows"] as? List<Map<String, Any?>> ?: return json
        if (rows.isEmpty()) "조회 결과 없음 (0행)"
        else rows.joinToString("\n") { row -> row.entries.joinToString(", ") { "${it.key}=${it.value}" } }
    } catch (e: Exception) { json }

    @Suppress("UNCHECKED_CAST")
    private fun analyzeSqlResult(json: String, question: String): String? = try {
        val parsed: Map<String, Any?> = mapper.readValue(
            json,
            mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
        )
        val error = parsed["error"]?.toString()
        if (!error.isNullOrBlank()) "SQL 실행 오류: $error"
        else {
            val rows = parsed["rows"] as? List<Map<String, Any?>>
            when {
                rows == null -> "응답에 rows 필드가 없음"
                rows.isEmpty() -> {
                    val negationKeywords = listOf("없", "아닌", "제외", "빼고", "않")
                    if (!negationKeywords.any { question.contains(it) }) "조회 결과가 0행임" else null
                }
                else -> null
            }
        }
    } catch (e: Exception) { "SQL 결과 파싱 실패: ${e.message}" }

    private fun parseVectorResult(json: String): List<ContextItem> = try {
        val docs: List<Map<String, Any?>> = mapper.readValue(
            json,
            mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java),
        )
        docs.map {
            ContextItem(
                source = it["source"]?.toString() ?: "document",
                text = it["text"]?.toString() ?: "",
                score = (it["score"] as? Number)?.toDouble() ?: 0.5,
            )
        }
    } catch (e: Exception) {
        listOf(ContextItem("document", json, 0.5))
    }

    private fun generateAnswer(question: String, context: List<ContextItem>, model: String): String {
        val contextBlock = if (context.isEmpty()) "(검색된 컨텍스트 없음)"
        else context.joinToString("\n\n") { "[출처: ${it.source}]\n${it.text}" }

        // TODO: 모델 동적 전환 구현 (현재는 기본 모델 사용)
        log.debug("답변 생성: model={}, context_size={}", model, context.size)

        return chatClient.prompt()
            .system(
                "너는 리원에이스의 데이터 플랫폼 AI 비서다. " +
                    "아래 컨텍스트에서 질문과 관련된 정보를 찾아 한국어로 답한다. " +
                    "컨텍스트에 장애 보고서, 기술 문서, 회의록 등이 있으면 핵심 내용(고객사, 제품, 원인, 조치사항 등)을 요약한다. " +
                    "컨텍스트가 여러 출처([출처: sql], [출처: knowledge-graph] 등)로 나뉘어 있으면, " +
                    "질문의 엔티티(고객사/제품/부서/이름 등)와 실제로 일치하는 출처만 사용하고 " +
                    "무관한 출처의 결과는 답변에 섞지 않는다. " +
                    "답변 끝에 출처를 표기한다.",
            )
            .user("컨텍스트:\n$contextBlock\n\n질문: $question")
            .call()
            .content()
            .orEmpty()
    }
}
