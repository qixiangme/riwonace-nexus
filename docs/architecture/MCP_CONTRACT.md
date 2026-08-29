# MCP 도구 계약

이 프로젝트는 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/)로
에이전트(`agent-app`)와 데이터 도구 서버(`mcp-server`) 사이의 통신을 표준화한다.
이 문서는 그 계약의 통신 흐름, 도구별 입출력, 보안 계층을 정리한다. 대체 구현
(AIR, Go)이 이 계약을 어떻게 재현하는지는
[IMPLEMENTATIONS.md](./IMPLEMENTATIONS.md)를 참고한다.

## MCP 통신 흐름

```text
┌─────────────────────────────────────────────────────────────────────┐
│                         agent-app (:8080)                           │
│  ┌──────────────┐    ┌──────────────┐    ┌───────────────────────┐  │
│  │ ChatController│───▶│ AgentService │───▶│ McpGateway            │  │
│  │ POST /api/chat│    │ 라우팅/NL2SQL │    │ MCP 클라이언트 래퍼   │  │
│  └──────────────┘    └──────────────┘    └───────────┬───────────┘  │
└──────────────────────────────────────────────────────┼──────────────┘
                                                       │ MCP/SSE
                                                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        mcp-server (:8081)                           │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      RetrievalTools                          │   │
│  │  ┌─────────────┐ ┌───────────┐ ┌─────────┐ ┌───────────────┐ │   │
│  │  │vector_search│ │get_schema │ │ run_sql │ │   kg_search   │ │   │
│  │  │ 문서 검색   │ │ 스키마조회│ │ SQL실행 │ │ 그래프 탐색   │ │   │
│  │  └──────┬──────┘ └─────┬─────┘ └────┬────┘ └───────┬───────┘ │   │
│  └─────────┼──────────────┼────────────┼──────────────┼─────────┘   │
│            │              │            │              │             │
│            ▼              ▼            ▼              ▼             │
│  ┌──────────────┐  ┌────────────┐  ┌─────────┐  ┌────────────┐      │
│  │  VectorStore │  │JdbcTemplate│  │SqlGuard │  │JdbcTemplate│      │
│  │  (pgvector)  │  │  (schema)  │  │(보안검증)│  │ (kg_triples)│     │
│  └──────┬───────┘  └─────┬──────┘  └────┬────┘  └─────┬──────┘      │
└─────────┼────────────────┼──────────────┼─────────────┼─────────────┘
          │                │              │             │
          ▼                ▼              ▼             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    PostgreSQL 16 + pgvector                         │
│  ┌────────────────┐  ┌─────────────┐  ┌────────────────────────┐    │
│  │  vector_store  │  │ employees   │  │      kg_triples        │    │
│  │  (문서 임베딩) │  │ departments │  │  (subject, predicate,  │    │
│  │                │  │ projects    │  │   object)              │    │
│  └────────────────┘  └─────────────┘  └────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

`get_schema`는 실행 Tool이 아니라 `db://schema` MCP **Resource**다. 스키마 조회는
"실행 동작"이 아니라 "지식(Knowledge)"이므로 Tool 3종(`vector_search`, `run_sql`,
`kg_search`)과 경계를 분리했다 — 자세한 배경은
[`RetrievalToolsContractTest`](../../mcp-server-spring/src/test/kotlin)와
[CONTEST_ALIGNMENT.md](../CONTEST_ALIGNMENT.md)를 참고한다.

## MCP 서버 설정

`mcp-server`는 Spring AI MCP Server로 구현되어 있으며, 다음과 같이 설정된다.

```yaml
# mcp-server/src/main/resources/application.yml
spring:
  ai:
    mcp:
      server:
        name: riwonace-data-platform
        version: 1.0.0
        type: SYNC    # 동기 MCP 서버
```

## MCP 도구 상세

| 도구/리소스 | 입력 | 출력 | 보안 |
|---|---|---|---|
| `vector_search` (Tool) | `query: String`, `topK?: Int` | 유사 문서 목록 (source, score, text) | 출력 크기 제한 (4KB) |
| `db://schema` (Resource) | - | 테이블/컬럼 정보 + 외래키 + 값 힌트 | 출력 크기 제한 (8KB) |
| `run_sql` (Tool) | `sql: String` | 실행 결과 JSON (rows) | SqlGuard 검증, SELECT만 허용 |
| `kg_search` (Tool) | `query: String` | 관계 트리플 목록 | 2홉 확장, 40개 제한 |

## MCP 클라이언트 (McpGateway)

`agent-app`에서 MCP 서버를 호출하는 게이트웨이:

```kotlin
// agent-app/src/main/kotlin/com/riwonace/agent/mcp/McpGateway.kt
@Component
class McpGateway(private val clients: List<McpSyncClient>) : DataToolGateway {

    override fun vectorSearch(query: String, topK: Int): String =
        callTool("vector_search", mapOf("query" to query, "topK" to topK))

    override fun runSql(sql: String): String =
        callTool("run_sql", mapOf("sql" to sql))

    override fun kgSearch(query: String): String =
        callTool("kg_search", mapOf("query" to query))

    override fun schema(): String =  // db://schema Resource, 프로세스 수명 동안 캐시
        cachedSchema.get() ?: readTextResource(SCHEMA_URI).also { cachedSchema.set(it) }
}
```

`McpSessionGuard`가 모든 호출을 하나의 락으로 직렬화한다. `McpSyncClient` 하나를
여러 코루틴/스레드에서 동시에 호출하면 요청·응답 ID가 어긋나는 문제가 있었기
때문이다 — 근본 원인과 재현 절차는 GitHub 이슈 #57에 기록되어 있다.

## 보안 계층

MCP 도구는 다음 보안 계층을 거친다.

1. **SqlGuard**: 토큰화 기반 SQL 검증
   - SELECT/WITH만 허용, DML/DDL 차단
   - 위험 함수 차단 (`pg_sleep`, `pg_read_file` 등)
   - LIMIT 자동 추가 (기본 50)

2. **ToolResponseEncoder**: 출력 보호
   - 최대 출력 크기 제한
   - 에러 메시지 일반화 (내부 정보 노출 방지)

3. **경로 검증** (`IngestController`)
   - 상위 디렉토리 탈출 방지
   - 심볼릭 링크 검사

> **알려진 한계**: 위 계층은 애플리케이션 로직 수준의 방어이며, 인증/인가
> 미들웨어·DB 계정 분리·요청 rate limiting 같은 인프라 수준 보안은 아직
> 적용되지 않았다. 남은 조치 항목은 GitHub 이슈 #83에 정리되어 있다. 이
> 저장소를 인터넷에 노출된 환경에 그대로 배포하지 말 것을 권장한다.
