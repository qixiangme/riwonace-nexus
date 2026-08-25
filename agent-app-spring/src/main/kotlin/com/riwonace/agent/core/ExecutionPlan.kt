package com.riwonace.agent.core

import com.riwonace.agent.router.Route

/**
 * 실행 계획: 도구 호출의 의존 관계와 조건을 표현하는 DAG 구조.
 *
 * 기존 "라우팅 → 병렬 호출"에서
 * "프로파일링 → 계획 생성 → 조건부/순차 실행"으로 전환한다.
 */
data class ExecutionPlan(
    /** 원본 질문 */
    val question: String,
    /** 실행할 노드들 (위상 정렬 순서) */
    val nodes: List<PlanNode>,
    /** 질문 프로파일 */
    val profile: QueryProfile,
    /** 선택된 모델 */
    val selectedModel: String,
    /** 계획 생성 시간 (ms) */
    val planningTimeMs: Long,
)

/**
 * 실행 계획의 단일 노드 (도구 호출 단위)
 */
data class PlanNode(
    /** 노드 고유 ID */
    val id: String,
    /** 호출할 도구/라우트 */
    val route: Route,
    /** 입력 템플릿 (변수 치환 가능) */
    val inputTemplate: String,
    /** 의존하는 노드 ID 목록 (빈 목록이면 즉시 실행 가능) */
    val dependsOn: List<String> = emptyList(),
    /** 실행 조건 (null이면 항상 실행) */
    val condition: ExecutionCondition? = null,
    /** 결과를 바인딩할 변수명 */
    val outputBinding: String? = null,
    /** 노드 실행 상태 */
    var status: NodeStatus = NodeStatus.PENDING,
    /** 실행 결과 */
    var result: NodeResult? = null,
)

/**
 * 실행 조건: 이전 노드의 결과에 따라 실행 여부를 결정
 */
data class ExecutionCondition(
    /** 조건 유형 */
    val type: ConditionType,
    /** 참조할 변수명 */
    val variable: String,
    /** 비교 연산자 */
    val operator: ConditionOperator,
    /** 비교 값 */
    val value: Any?,
)

enum class ConditionType {
    /** 이전 결과가 비어있지 않으면 실행 */
    NOT_EMPTY,
    /** 이전 결과에 특정 값이 포함되면 실행 */
    CONTAINS,
    /** 이전 결과가 임계값 이상이면 실행 */
    THRESHOLD,
    /** 이전 노드가 실패했을 때 실행 (폴백) */
    ON_FAILURE,
    /** 항상 실행 */
    ALWAYS,
}

enum class ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    CONTAINS,
    NOT_EMPTY,
}

enum class NodeStatus {
    /** 실행 대기 */
    PENDING,
    /** 실행 중 */
    RUNNING,
    /** 성공 */
    SUCCESS,
    /** 실패 */
    FAILED,
    /** 조건 미충족으로 스킵 */
    SKIPPED,
}

/**
 * 노드 실행 결과
 */
data class NodeResult(
    /** 실행 성공 여부 */
    val success: Boolean,
    /** 결과 데이터 */
    val data: Any?,
    /** 오류 메시지 (실패 시) */
    val error: String? = null,
    /** 실행 시간 (ms) */
    val durationMs: Long,
    /** 실패 분류 (실패 시) */
    val failureType: FailureType? = null,
)

/**
 * 실패 유형 분류 (Recovery Policy에서 활용)
 */
enum class FailureType {
    /** 라우트 미매칭 */
    ROUTE_MISS,
    /** 검색 결과 없음 */
    RETRIEVAL_EMPTY,
    /** SQL 스키마 오류 */
    SQL_SCHEMA_ERROR,
    /** SQL 문법 오류 */
    SQL_SYNTAX_ERROR,
    /** 증거 충돌 */
    EVIDENCE_CONFLICT,
    /** MCP 서버 타임아웃 */
    MCP_TIMEOUT,
    /** MCP 서버 연결 실패 */
    MCP_CONNECTION_ERROR,
    /** 알 수 없는 오류 */
    UNKNOWN,
}

/**
 * 전체 실행 추적 정보 (UI 표시용)
 */
data class ExecutionTrace(
    /** 실행 계획 */
    val plan: ExecutionPlan,
    /** 노드별 실행 이력 */
    val nodeTraces: List<NodeTrace>,
    /** 전체 실행 시간 (ms) */
    val totalDurationMs: Long,
    /** 최종 결과 */
    val finalAnswer: String?,
    /** 사용된 증거 소스 */
    val evidenceSources: List<String>,
    /** 클레임 커버리지 */
    val claimCoverage: Double,
)

/**
 * 개별 노드 실행 추적
 */
data class NodeTrace(
    /** 노드 ID */
    val nodeId: String,
    /** 라우트 */
    val route: Route,
    /** 실행 상태 */
    val status: NodeStatus,
    /** 시작 시간 (epoch ms) */
    val startedAt: Long,
    /** 종료 시간 (epoch ms) */
    val endedAt: Long?,
    /** 결과 요약 (긴 결과는 잘라서) */
    val resultSummary: String?,
    /** 재시도 횟수 */
    val retryCount: Int = 0,
)
