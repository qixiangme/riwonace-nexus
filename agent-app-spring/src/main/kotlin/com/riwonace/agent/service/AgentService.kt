package com.riwonace.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.riwonace.agent.answer.StructuredEvidenceFormatter
import com.riwonace.agent.context.ContextCurator
import com.riwonace.agent.context.ContextItem
import com.riwonace.agent.mcp.DataToolGateway
import com.riwonace.agent.router.Route
import com.riwonace.agent.router.RouteQuestionProjector
import com.riwonace.agent.router.RuleBasedRouter
import com.riwonace.agent.sql.FewShotSelector
import com.riwonace.agent.sql.DeterministicSqlPlanner
import com.riwonace.agent.sql.PostgresSqlNormalizer
import com.riwonace.agent.sql.SchemaLinker
import com.riwonace.agent.sql.SchemaPromptFormatter
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 에이전트 API의 관측 가능한 실행 결과.
 *
 * @property routes 질문에 선택된 검색 경로
 * @property toolCalls 실제로 시도한 MCP 도구 호출 순서
 * @property contextSources 최종 답변 컨텍스트에 포함된 출처
 * @property latencyMs 라우팅부터 답변 생성까지의 전체 지연 시간
 */
data class AgentAnswer(
    val answer: String,
    val routes: List<Route>,
    val toolCalls: List<String>,
    val contextSources: List<String>,
    val failedRoutes: List<Route>,
    val responseMode: ResponseMode,
    val contextChars: Int,
    val estimatedContextTokens: Int,
    val evaluationMode: EvaluationMode,
    val selectedEvidence: List<EvidenceTrace>?,
    val latencyMs: Long,
)

enum class ResponseMode { NORMAL, DEGRADED, FAILED }
enum class EvaluationMode { NO_TOOLS, VANILLA_MCP, OURS }

data class EvidenceTrace(val source: String, val text: String, val score: Double)

/**
 * 에이전트 오케스트레이터: 라우팅 → MCP 도구 병렬 호출 → TACC 큐레이션 → 답변 생성.
 * (Pylon-7 기준 L4 라우팅 / L5 컨텍스트 구성 / L6 추론 계층에 해당)
 */
@Service
class AgentService(
    private val router: RuleBasedRouter,
    private val gateway: DataToolGateway,
    private val curator: ContextCurator,
    private val chatClient: ChatClient,
    private val mapper: ObjectMapper,
    private val fewShotSelector: FewShotSelector,
    private val schemaLinker: SchemaLinker,
    private val schemaPromptFormatter: SchemaPromptFormatter,
    private val routeQuestionProjector: RouteQuestionProjector,
    private val postgresSqlNormalizer: PostgresSqlNormalizer,
    private val deterministicSqlPlanner: DeterministicSqlPlanner,
    private val structuredEvidenceFormatter: StructuredEvidenceFormatter,
    @Value("\${agent.evaluation.mode:OURS}") modeName: String,
    @Value("\${agent.evaluation.include-evidence:false}") private val includeEvidence: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(4)
    private val evaluationMode = EvaluationMode.valueOf(modeName.trim().uppercase())

    @PreDestroy
    fun shutdown() {
        log.info("AgentService 스레드 풀 종료 시작")
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("스레드 풀 정상 종료 실패, 강제 종료 시도")
            executor.shutdownNow()
        }
    }

    companion object {
        /** Self-Corrective SQL 최대 재시도 횟수 (Self-RAG ICLR 2024 영감) */
        private const val MAX_SQL_RETRIES = 2
    }

    private data class CollectedContext(
        val items: List<ContextItem>,
        val toolCalls: List<String>,
        val failed: Boolean,
    )

    /** 질문을 라우팅하고 근거를 수집·선별한 뒤 출처가 포함된 답변을 생성한다. */
    fun chat(question: String): AgentAnswer {
        val started = System.currentTimeMillis()
        if (evaluationMode == EvaluationMode.NO_TOOLS) {
            return AgentAnswer(
                answer = generateUngroundedAnswer(question),
                routes = emptyList(),
                toolCalls = emptyList(),
                contextSources = emptyList(),
                failedRoutes = emptyList(),
                responseMode = ResponseMode.NORMAL,
                contextChars = 0,
                estimatedContextTokens = 0,
                evaluationMode = evaluationMode,
                selectedEvidence = emptyList<EvidenceTrace>().takeIf { includeEvidence },
                latencyMs = System.currentTimeMillis() - started,
            )
        }

        val routes = if (evaluationMode == EvaluationMode.VANILLA_MCP) planVanillaRoutes(question) else router.route(question)
        log.info("질문 라우팅 결과: {} → {}", question, routes)

        // 라우트별 준비 작업은 병렬로 실행하되, 단일 MCP 세션의 실제 프로토콜 호출은
        // McpGateway에서 직렬화한다. 결과는 routes 순서로 합쳐 관측값을 결정적으로 만든다.
        val futures = routes.map { route ->
            CompletableFuture.supplyAsync({ collectContext(route, question) }, executor)
        }
        val collected = futures.map { it.join() }
        val items = collected.flatMap { it.items }
        val toolCalls = collected.flatMap { it.toolCalls }

        val curated = curator.curate(question, items, routes)
        val answer = if (routes.size > 1) {
            generateComposedAnswer(question, curated, routes)
        } else {
            // 구조화 결과(SQL/그래프)는 1B 모델에 재요약을 맡기면 값·식별자를
            // 누락하는 회귀가 반복된다. 원문 근거를 그대로 보존하고, 문서 검색만
            // 생성 요약을 사용한다. 이는 평가 문항을 복사한 예외가 아니라 MCP
            // 도구별 출력 계약에 따른 일반 정책이다.
            generateSingleRouteAnswer(question, curated, routes.singleOrNull())
        }
        val failedRoutes = routes.zip(collected).filter { it.second.failed }.map { it.first }
        val contextChars = curated.sumOf { it.text.length }
        val responseMode = when {
            failedRoutes.isEmpty() -> ResponseMode.NORMAL
            failedRoutes.size < routes.size -> ResponseMode.DEGRADED
            else -> ResponseMode.FAILED
        }

        return AgentAnswer(
            answer = answer,
            routes = routes,
            toolCalls = toolCalls,
            contextSources = curated.map { it.source },
            failedRoutes = failedRoutes,
            responseMode = responseMode,
            contextChars = contextChars,
            estimatedContextTokens = (contextChars + 3) / 4,
            evaluationMode = evaluationMode,
            selectedEvidence = curated.map { EvidenceTrace(it.source, it.text, it.score) }
                .takeIf { includeEvidence },
            latencyMs = System.currentTimeMillis() - started,
        )
    }

    private fun collectContext(route: Route, question: String): CollectedContext {
        val toolCalls = mutableListOf<String>()
        return try {
            val items =
            when (route) {
                Route.VECTOR -> {
                    toolCalls += "vector_search"
                    val routeQuestion = routeQuestionProjector.project(question, Route.VECTOR)
                    parseVectorResult(gateway.vectorSearch(routeQuestion, topK = 4))
                }
                Route.GRAPH -> {
                    toolCalls += "kg_search"
                    val routeQuestion = routeQuestionProjector.project(question, Route.GRAPH)
                    val result = gateway.kgSearch(routeQuestion)
                    listOf(ContextItem("knowledge-graph", "지식 그래프 관계:\n${humanizeGraphResult(result)}", 0.9))
                }
                Route.SQL -> {
                    toolCalls += "run_sql"
                    var sql = generateSql(question)
                    var result = gateway.runSql(sql)
                    var retryCount = 0

                    // Self-Corrective SQL: 2단계 검증과 최대 2회 자가수정
                    // 논문 참고: Self-RAG (ICLR 2024) - 생성 결과를 자가 비평하고 필요시 재시도
                    while (evaluationMode == EvaluationMode.OURS && retryCount < MAX_SQL_RETRIES) {
                        val feedback = analyzeSqlResult(result, question)
                        if (feedback == null) break

                        log.info("run_sql 자가수정 재시도 {}/{} — 사유: {}", retryCount + 1, MAX_SQL_RETRIES, feedback.take(100))
                        toolCalls += "run_sql(retry-${retryCount + 1})"
                        sql = generateSql(question, previousAttempt = sql, error = feedback)
                        result = gateway.runSql(sql)
                        retryCount++
                    }
                    listOf(ContextItem("sql", "실행한 SQL: $sql\n조회 결과:\n${humanizeSqlResult(result)}", 1.0))
                }
            }
            CollectedContext(items, toolCalls, failed = false)
        } catch (e: Exception) {
            log.warn("{} 라우트 컨텍스트 수집 실패: {}", route, e.message)
            CollectedContext(emptyList(), toolCalls, failed = true)
        }
    }

    /**
     * NL2SQL: MCP로 받은 스키마를 근거로 소형 LLM이 SELECT 한 문장을 생성한다.
     *
     * 동적 Few-Shot 선택: 고정 예시 대신 질문과 유사한 예시를 동적으로 선택한다.
     * 논문 참고: Instructional Prompt Optimization for Few-Shot LLM (2025)
     *
     * SchemaGraphSQL (2025) 영감: 질문의 엔티티를 스키마 테이블/컬럼과 명시적으로 연결하여
     * 소형 모델의 테이블/컬럼 선택 오류를 방지한다.
     */
    private fun generateSql(question: String, previousAttempt: String? = null, error: String? = null): String {
        val rawSchema = gateway.schema()
        val schema = schemaPromptFormatter.format(rawSchema)
        val sqlQuestion = routeQuestionProjector.project(question, Route.SQL)
        if (sqlQuestion != question) log.info("SQL 하위 과업 분리: {} → {}", question, sqlQuestion)
        if (evaluationMode == EvaluationMode.OURS) {
            deterministicSqlPlanner.plan(sqlQuestion)?.let {
                log.info("결정적 SQL 계획 적용: {}", it)
                return it
            }
        }

        // 1B급 모델은 서로 다른 SQL 패턴을 섞는 경향이 있어 가장 가까운 예시 하나만 제공한다.
        val selectedExamples = if (evaluationMode == EvaluationMode.OURS) {
            fewShotSelector.selectExamples(sqlQuestion, topK = 1)
        } else {
            emptyList()
        }
        val examplesBlock = fewShotSelector.formatExamplesForPrompt(selectedExamples)
        log.info("Few-shot 예시 선택: {}", selectedExamples.map { it.pattern })

        // SchemaGraphSQL: 질문에서 스키마 값 매칭
        val schemaHints = if (evaluationMode == EvaluationMode.OURS) {
            schemaLinker.linkEntities(rawSchema, sqlQuestion)
        } else {
            emptyList()
        }
        val hintBlock = schemaLinker.formatHintsForPrompt(schemaHints)
        if (schemaHints.isNotEmpty()) {
            log.info("스키마 링킹 결과: {}", schemaHints.map { it.suggestion })
        }

        val retryBlock =
            if (previousAttempt == null) ""
            else "직전 시도: $previousAttempt\n오류: ${error?.take(300)}\n오류를 고쳐서 다시 작성한다.\n\n"
        val raw = chatClient.prompt()
            .system(
                "너는 PostgreSQL 전문가다. 주어진 스키마만 사용해서 질문에 답하는 " +
                    "SELECT 문 한 문장만 출력한다. TABLE에 없는 식별자를 만들지 않고, " +
                    "JOIN은 FOREIGN KEYS에 제시된 관계만 사용한다. " +
                    "질문이 문서 내용이나 개체 관계도 함께 요구하면 그 부분은 다른 도구의 책임이므로 무시하고, " +
                    "행·열의 조건 검색, 속성 조회, 계산에 필요한 SQL 부분만 처리한다. " +
                    "설명, 마크다운, 세미콜론 없이 SQL만 출력한다." +
                    if (schemaHints.isNotEmpty()) " 스키마 힌트에 표시된 테이블.컬럼과 값을 반드시 사용한다." else "",
            )
            .user(
                "스키마:\n$schema$hintBlock\n\n" +
                    "$examplesBlock\n\n" +
                    retryBlock +
                    "질문: $sqlQuestion\nSQL:",
            )
            .call()
            .content()
            .orEmpty()
        val sql = postgresSqlNormalizer.normalize(raw
            .replace(Regex("```(sql)?", RegexOption.IGNORE_CASE), "")
            .trim()
            .removeSuffix(";"))
        log.info("NL2SQL 생성 결과: {}", sql)
        return sql
    }

    /**
     * run_sql의 JSON 결과를 "컬럼=값" 텍스트로 풀어쓴다.
     * 소형 모델은 {"rows":[{"count":8}]} 같은 중첩 JSON을 답으로 인식하지 못하고
     * '찾을 수 없다'고 답하는 경향이 있다 (벤치마크로 확인).
     */
    @Suppress("UNCHECKED_CAST")
    private fun humanizeSqlResult(json: String): String =
        try {
            val parsed: Map<String, Any?> = mapper.readValue(
                json,
                mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
            )
            val rows = parsed["rows"] as? List<Map<String, Any?>> ?: return json
            if (rows.isEmpty()) "조회 결과 없음 (0행)"
            else rows.joinToString("\n") { row ->
                row.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }
        } catch (e: Exception) {
            json
        }

    /**
     * Self-Corrective SQL: 결과를 분석하여 재시도 필요 여부를 판단한다.
     * 논문 참고: Self-RAG (ICLR 2024) - 생성 결과를 자가 비평하고 필요시 재시도
     *
     * @return 재시도가 필요하면 피드백 문자열, 불필요하면 null
     */
    @Suppress("UNCHECKED_CAST")
    private fun analyzeSqlResult(json: String, question: String): String? {
        return try {
            // 1단계: JSON 파싱 오류 검출
            val parsed: Map<String, Any?> = mapper.readValue(
                json,
                mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
            )

            // 2단계: 에러 응답 검출
            val error = parsed["error"]?.toString()
            if (!error.isNullOrBlank()) {
                "SQL 실행 오류: $error"
            } else {
                // 3단계: 빈 결과 검출 (0행)
                val rows = parsed["rows"] as? List<Map<String, Any?>>
                when {
                    rows == null -> "응답에 rows 필드가 없음"
                    rows.isEmpty() -> {
                        // 빈 결과가 예상되는 질문인지 확인 (부정형 질문)
                        val negationKeywords = listOf("없", "아닌", "제외", "빼고", "않")
                        val isNegationQuestion = negationKeywords.any { question.contains(it) }
                        if (!isNegationQuestion) {
                            "조회 결과가 0행임. 조건을 완화하거나 테이블/컬럼명을 확인해야 함"
                        } else {
                            null
                        }
                    }
                    else -> {
                        // 4단계: 결과 유효성 검증 (NULL 값 과다)
                        val nullCount = rows.sumOf { row -> row.values.count { it == null } }
                        val totalValues = rows.size * rows.first().size
                        if (totalValues > 0 && nullCount.toDouble() / totalValues > 0.5) {
                            "결과의 50% 이상이 NULL임. JOIN 조건이나 컬럼 선택을 확인해야 함"
                        } else {
                            null // 검증 통과
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // JSON 파싱 실패 = SQL 실행 자체가 실패한 것
            "SQL 결과 파싱 실패: ${e.message}"
        }
    }

    private fun parseVectorResult(json: String): List<ContextItem> =
        try {
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
            log.warn("vector_search 결과 파싱 실패: {}", e.message)
            listOf(ContextItem("document", json, 0.5))
        }

    private fun humanizeGraphResult(json: String): String =
        try {
            val relations: List<String> = mapper.readValue(
                json,
                mapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
            )
            relations.joinToString("\n")
        } catch (e: Exception) {
            json
        }

    private fun generateAnswer(question: String, context: List<ContextItem>): String {
        val contextBlock =
            if (context.isEmpty()) "(검색된 컨텍스트 없음)"
            else context.joinToString("\n\n") { "[출처: ${it.source}]\n${it.text}" }

        return chatClient.prompt()
            .system(
                "너는 제공된 근거에서 값을 추출하는 데이터 비서다. " +
                    "질문의 요구사항마다 근거에 적힌 값이나 사실을 짧은 한국어 문장으로 답한다. " +
                    "한 요구의 근거가 없더라도 다른 요구의 확인된 답은 빠뜨리지 않는다. " +
                    "근거에 없는 사실은 추측하지 않고 해당 항목만 정보 부족으로 표시한다. " +
                    "마지막에 실제 사용한 [출처] 이름을 적는다.",
            )
            .user("근거:\n$contextBlock\n\n위 근거에서 다음 질문의 항목별 답을 추출하라.\n질문: $question\n답:")
            .call()
            .content()
            .orEmpty()
    }

    private fun generateSingleRouteAnswer(
        question: String,
        context: List<ContextItem>,
        route: Route?,
    ): String = when (route) {
        Route.SQL, Route.GRAPH -> {
            val evidence = salientEvidence(question, route, context).trim()
            if (evidence.isBlank()) generateAnswer(question, context)
            else "$evidence\n[출처: ${context.joinToString(", ") { it.source }}]"
        }
        Route.VECTOR, null -> generateAnswer(question, context)
    }

    /**
     * 작은 모델이 서로 다른 데이터 형태의 요구 중 하나를 누락하지 않도록 도구 경계별로 답을 병렬 생성한다.
     * 각 호출에는 해당 도구 근거만 노출하므로 한 경로의 잡음이나 실패가 다른 경로의 확인된 답을 지우지 않는다.
     */
    private fun generateComposedAnswer(
        question: String,
        context: List<ContextItem>,
        routes: List<Route>,
    ): String {
        val futures = routes.mapNotNull { route ->
            val routeContext = context.filter { routeForSource(it.source) == route }
            if (routeContext.isEmpty()) null else route to CompletableFuture.supplyAsync(
                { generateRouteAnswer(question, route, routeContext) },
                executor,
            )
        }
        if (futures.isEmpty()) return generateAnswer(question, context)
        return futures.joinToString("\n") { (route, future) -> "- ${route.name}: ${future.join().trim()}" }
    }

    private fun generateRouteAnswer(question: String, route: Route, context: List<ContextItem>): String {
        val contextBlock = context.joinToString("\n\n") { "[출처: ${it.source}]\n${it.text}" }
        val routeQuestion = routeQuestionProjector.project(question, route)
        val evidence = salientEvidence(routeQuestion, route, context)
        if (route != Route.VECTOR) return evidence

        val summary = chatClient.prompt()
            .system(
                "너는 ${route.name} 근거에서 확인 가능한 질문 항목만 추출하는 데이터 비서다. " +
                    "제공된 근거로 답할 수 있는 값이나 사실만 짧게 답하고 다른 데이터 도구가 담당할 항목은 생략한다. " +
                    "근거에 없는 사실은 추측하지 않는다. 마지막에 실제 사용한 [출처] 이름을 적는다.",
            )
            .user("근거:\n$contextBlock\n\n질문: $routeQuestion\n${route.name} 근거로 확인되는 답:")
            .call()
            .content()
            .orEmpty()
        return "$summary\n핵심 근거:\n$evidence"
    }

    private fun salientEvidence(question: String, route: Route, context: List<ContextItem>): String = when (route) {
        Route.SQL -> structuredEvidenceFormatter.formatSql(question, context.joinToString("\n") { item ->
            item.text.substringAfter("조회 결과:\n", item.text)
        })
        Route.GRAPH -> {
            val lines = context.flatMap { it.text.lines() }.filter { "--[" in it }
            val predicate = when {
                listOf("사용", "이용", "쓰는").any(question::contains) -> "사용한다"
                listOf("담당", "담당자").any(question::contains) -> "담당한다"
                listOf("팀장", "이끄는", "부서장").any(question::contains) -> "부서장"
                else -> null
            }
            lines.filter { predicate == null || it.contains(predicate) }.ifEmpty { lines }.take(12).joinToString("\n")
        }
        Route.VECTOR -> {
            val terms = question.lowercase().split(Regex("[^가-힣a-z0-9_-]+"))
                .filter { it.length >= 2 && it !in setOf("알려줘", "무엇이야") }
                .toSet()
            context.joinToString("\n") { item ->
                val lines = item.text.lines().map(String::trim).filter(String::isNotEmpty)
                val chosen = linkedSetOf<Int>()
                if (lines.isNotEmpty()) chosen += 0
                lines.forEachIndexed { index, line ->
                    if (terms.any { line.lowercase().contains(it) }) {
                        chosen += index
                        if (index + 1 < lines.size) chosen += index + 1
                    }
                }
                "[${item.source}] " + chosen.sorted().take(8).joinToString(" ") { lines[it] }
            }
        }
    }

    private fun routeForSource(source: String): Route = when (source) {
        "sql" -> Route.SQL
        "knowledge-graph" -> Route.GRAPH
        else -> Route.VECTOR
    }

    /** 동일 MCP 계약만 제공하고 별도 규칙·예시 없이 모델이 필요한 경로 집합을 선택하는 기준선. */
    private fun planVanillaRoutes(question: String): List<Route> {
        val raw = chatClient.prompt()
            .system(
                "질문에 답하는 데 필요한 데이터 도구를 고른다. " +
                    "SQL은 정형 데이터 계산, VECTOR는 문서 의미 검색, GRAPH는 개체 관계 조회다. " +
                    "필요한 라벨을 쉼표로 구분해 SQL,VECTOR,GRAPH 중 하나 이상만 출력한다.",
            )
            .user("질문: $question\n도구:")
            .call()
            .content()
            .orEmpty()
        val parsed = Route.entries.filter { route ->
            Regex("(^|[^A-Z])${route.name}([^A-Z]|$)").containsMatchIn(raw.trim().uppercase())
        }
        return parsed.ifEmpty { listOf(Route.VECTOR) }
    }

    private fun generateUngroundedAnswer(question: String): String = chatClient.prompt()
        .system(
            "외부 도구나 비공개 데이터가 없는 로컬 언어모델 기준선이다. " +
                "확실히 모르는 회사 내부 사실은 모른다고 답하고 지어내지 않는다.",
        )
        .user(question)
        .call()
        .content()
        .orEmpty()
}
