package com.riwonace.agent.core

import com.riwonace.agent.router.Route
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 실행 계획 생성기: QueryProfile을 기반으로 실행 DAG를 생성한다.
 *
 * 전략:
 * 1. 단순 질문: 단일 노드 (병렬화 불필요)
 * 2. 복합 질문: 의존 관계에 따라 순차/병렬 결정
 * 3. 다단계 질문: 이전 결과를 다음 입력으로 연결
 */
@Component
class ExecutionPlanner(
    private val profiler: QueryProfiler,
    private val escalator: ModelEscalator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 질문에 대한 실행 계획을 생성한다.
     */
    fun plan(question: String): ExecutionPlan {
        val started = System.currentTimeMillis()

        // 1. 질문 프로파일링
        val profile = profiler.profile(question)

        // 2. 모델 선택
        val modelSelection = escalator.selectModel(profile)

        // 3. 실행 노드 생성
        val nodes = when {
            profile.hasDependency -> createDependentPlan(question, profile)
            profile.isMultiHop -> createMultiHopPlan(question, profile)
            profile.suggestedRoutes.size > 1 -> createParallelPlan(question, profile)
            else -> createSimplePlan(question, profile)
        }

        val planningTime = System.currentTimeMillis() - started

        log.info(
            "실행 계획 생성 완료: {} 노드, 의존성={}, 다단계={}, 계획시간={}ms",
            nodes.size, profile.hasDependency, profile.isMultiHop, planningTime,
        )

        return ExecutionPlan(
            question = question,
            nodes = nodes,
            profile = profile,
            selectedModel = modelSelection.model,
            planningTimeMs = planningTime,
        )
    }

    /**
     * 단순 계획: 단일 라우트 실행
     */
    private fun createSimplePlan(question: String, profile: QueryProfile): List<PlanNode> {
        val route = profile.suggestedRoutes.firstOrNull() ?: Route.VECTOR
        return listOf(
            PlanNode(
                id = "node-1",
                route = route,
                inputTemplate = question,
                outputBinding = "result_1",
            ),
        )
    }

    /**
     * 병렬 계획: 여러 라우트를 동시에 실행
     */
    private fun createParallelPlan(question: String, profile: QueryProfile): List<PlanNode> {
        return profile.suggestedRoutes.mapIndexed { index, route ->
            PlanNode(
                id = "node-${index + 1}",
                route = route,
                inputTemplate = question,
                outputBinding = "result_${index + 1}",
            )
        }
    }

    /**
     * 다단계 계획: 순차적으로 실행하며 이전 결과를 참조
     */
    private fun createMultiHopPlan(question: String, profile: QueryProfile): List<PlanNode> {
        val nodes = mutableListOf<PlanNode>()
        val routes = profile.suggestedRoutes.ifEmpty { listOf(Route.SQL, Route.VECTOR) }

        routes.forEachIndexed { index, route ->
            val isFirst = index == 0
            val previousNodeId = if (!isFirst) "node-$index" else null

            nodes += PlanNode(
                id = "node-${index + 1}",
                route = route,
                inputTemplate = if (isFirst) question else "$question (이전 결과: \${result_$index})",
                dependsOn = previousNodeId?.let { listOf(it) } ?: emptyList(),
                condition = if (!isFirst) {
                    ExecutionCondition(
                        type = ConditionType.NOT_EMPTY,
                        variable = "result_$index",
                        operator = ConditionOperator.NOT_EMPTY,
                        value = null,
                    )
                } else null,
                outputBinding = "result_${index + 1}",
            )
        }

        return nodes
    }

    /**
     * 의존 계획: SQL 결과를 Vector 검색 입력으로 사용 등
     */
    private fun createDependentPlan(question: String, profile: QueryProfile): List<PlanNode> {
        val nodes = mutableListOf<PlanNode>()

        // 패턴 분석: "상위 X의 Y 정보" → SQL로 상위 X 조회 → Vector로 Y 검색
        val hasStructuredFirst = profile.requiredEvidence.contains(EvidenceType.STRUCTURED_DATA)

        if (hasStructuredFirst) {
            // 1단계: SQL로 엔티티 식별
            nodes += PlanNode(
                id = "sql-identify",
                route = Route.SQL,
                inputTemplate = extractStructuredQuery(question),
                outputBinding = "entities",
            )

            // 2단계: 식별된 엔티티로 문서 검색
            nodes += PlanNode(
                id = "vector-detail",
                route = Route.VECTOR,
                inputTemplate = "\${entities}에 대한 상세 정보",
                dependsOn = listOf("sql-identify"),
                condition = ExecutionCondition(
                    type = ConditionType.NOT_EMPTY,
                    variable = "entities",
                    operator = ConditionOperator.NOT_EMPTY,
                    value = null,
                ),
                outputBinding = "details",
            )

            // 3단계: 폴백 - SQL 실패 시 Vector만으로 시도
            nodes += PlanNode(
                id = "vector-fallback",
                route = Route.VECTOR,
                inputTemplate = question,
                dependsOn = listOf("sql-identify"),
                condition = ExecutionCondition(
                    type = ConditionType.ON_FAILURE,
                    variable = "entities",
                    operator = ConditionOperator.EQUALS,
                    value = null,
                ),
                outputBinding = "fallback_result",
            )
        } else {
            // 문서 → SQL 순서
            nodes += PlanNode(
                id = "vector-context",
                route = Route.VECTOR,
                inputTemplate = question,
                outputBinding = "context",
            )

            nodes += PlanNode(
                id = "sql-verify",
                route = Route.SQL,
                inputTemplate = "\${context}를 검증하는 데이터 조회",
                dependsOn = listOf("vector-context"),
                condition = ExecutionCondition(
                    type = ConditionType.NOT_EMPTY,
                    variable = "context",
                    operator = ConditionOperator.NOT_EMPTY,
                    value = null,
                ),
                outputBinding = "verification",
            )
        }

        return nodes
    }

    /**
     * 질문에서 구조화된 쿼리 부분 추출
     */
    private fun extractStructuredQuery(question: String): String {
        // "상위 N개", "최근 N건" 등의 패턴 추출
        val patterns = listOf(
            Regex("(상위|하위|최근|처음)\\s*(\\d+)\\s*(개|건|명|개의|건의|명의)"),
            Regex("(가장|제일)\\s*(높은|낮은|많은|적은|큰|작은)"),
        )

        for (pattern in patterns) {
            val match = pattern.find(question)
            if (match != null) {
                // 패턴 앞뒤의 컨텍스트와 함께 반환
                val start = (match.range.first - 20).coerceAtLeast(0)
                val end = (match.range.last + 20).coerceAtMost(question.length)
                return question.substring(start, end)
            }
        }

        return question
    }

    /**
     * 실행 계획을 Mermaid 다이어그램으로 시각화
     */
    fun visualize(plan: ExecutionPlan): String {
        val sb = StringBuilder()
        sb.appendLine("graph TD")

        for (node in plan.nodes) {
            val label = "${node.id}[${node.route}: ${node.inputTemplate.take(30)}...]"
            sb.appendLine("    $label")

            for (dep in node.dependsOn) {
                val edgeLabel = node.condition?.let { " -->|${it.type}|" } ?: " -->"
                sb.appendLine("    $dep$edgeLabel ${node.id}")
            }
        }

        return sb.toString()
    }
}
