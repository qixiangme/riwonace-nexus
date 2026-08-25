# 대체 구현: AIR(Node.js)와 Go 포팅

이 프로젝트의 핵심 경계는 특정 프레임워크나 언어가 아니라
[MCP 도구 계약](./MCP_CONTRACT.md)이다. 그 계약이 실제로 프레임워크·언어에
독립적인지 검증하기 위해 두 종류의 대체 구현을 만들었다. 둘 다 **기본 실행
경로가 아니며**, `agent-app`(Spring AI) → `mcp-server`(Spring AI)가 여전히
기본이고 자동화 테스트·데이터 적재를 담당한다.

| 실험 | 무엇을 바꿨나 | 무엇을 유지했나 | 검증한 것 |
|---|---|---|---|
| AIR (`mcp-server-air`) | 프레임워크(Spring AI MCP → Node.js AIR MCP) | 언어(TypeScript 아님, 순수 JS), MCP 도구 계약 | 서버 URL만 바꿔도 `agent-app` 코드 변경 없이 교체 가능한가 |
| Go (`mcp-server-go`, `agent-app-go`) | 언어·런타임 전체(Kotlin/JVM → Go) | 라우팅·NL2SQL·SQL 검증·caching 정책·MCP 도구 계약 | 다른 언어로 1:1 포팅했을 때 성능·자원 사용량이 실제로 어떻게 다른가 |

## AIR (`mcp-server-air`)

Node.js의 AIR MCP 프레임워크로 `mcp-server`와 같은 도구 이름(`vector_search`,
`run_sql`, `kg_search`)과 `db://schema` Resource를 노출한다.

### 실행

AIR는 벡터 데이터를 스스로 적재하지 않는다 — Spring AI `mcp-server`로 먼저
적재한 뒤 전환한다.

```bash
npm ci --prefix mcp-server-air
npm --prefix mcp-server-air start

# 별도 터미널
MCP_SERVER_URL=http://localhost:8082 ./gradlew :agent-app-spring:bootRun
```

기본 Spring AI 구현으로 돌아가려면 `MCP_SERVER_URL`을 생략하거나
`http://localhost:8081`로 설정한다.

### 권장 사항

- 서버 교체 가능성을 실증하는 참고 구현으로 유지하는 것을 권장한다. 실제로
  프로덕션에 투입하기보다는, "MCP 계약이 프레임워크에 종속적이지 않다"는 것을
  보여주는 회귀 검증용으로 계속 굴리는 편이 낫다.
- 출력 크기 제한(`MAX_OUTPUT_CHARS`)이나 스키마 응답의 `foreignKeys` 포함 여부를
  Spring AI와 맞추는 작업은 GitHub 이슈 #49에 남아 있다 — AIR로 전환해 실제
  운영 트래픽을 받게 할 계획이 있다면 이 격차부터 해소해야 한다.

### 위험 / 트레이드오프

- **계약 불일치**: 이슈 #49에서 확인된 대로, AIR의 `get_schema` 응답은
  `foreignKeys`를 누락하고 모든 도구 출력을 4,000자로 제한한다(Spring AI는
  8,000자). 스키마 정보가 부족한 채 NL2SQL을 태우면 조인 오류가 늘어날 수 있다.
- **에러 처리**: JSON 문자열을 임의 위치에서 자를 수 있어(문자 수 기준 truncate)
  잘린 JSON이 파싱 실패로 이어질 가능성이 있고, 원본 DB 예외 메시지를 그대로
  반환해 내부 구조가 노출될 수 있다.
- **CI 미포함**: `mcp-server-air`는 루트 CI(`agent-app`/`mcp-server`/`client`/`eval`)
  대상이 아니다. 자체 `npm test`(`mcp-server-air/server.test.mjs`)로만 검증되므로,
  Spring AI 쪽 회귀가 AIR에도 동일하게 적용됐는지는 수동 확인이 필요하다.

키워드가 전혀 걸리지 않는 질문에서 라우팅 정확도를 끌어올린 설정(참고용, AIR
전환과는 무관하게 baseline에도 적용 가능):

```bash
OLLAMA_MODEL=gemma3:4b ROUTER_FALLBACK=semantic-ai ./gradlew :agent-app-spring:bootRun
```

공개셋 93.3%, 키워드 무교집합 보류셋 96.7%이며 100%는 아니다. 평가 범위와 원시
결과는 [키워드 없는 라우팅 실험 결과](../research/KEYWORDLESS_ROUTING_RESULTS.md)를
참고한다. Company-X 전체 스택에서는 공식 원문 답변 66.7%, 키워드 제거 답변
50.0%를 측정했다.

## Go 포팅 (`mcp-server-go`, `agent-app-go`)

공식 [Go MCP SDK](https://github.com/modelcontextprotocol/go-sdk)로
`mcp-server`/`agent-app`를 1:1 포팅했다. 라우팅·NL2SQL·SQL 검증·caching 정책·
MCP 도구 계약을 임의로 개선하지 않고 동일하게 재현하는 것이 목표였다.

### 빌드와 실행

Go 1.21+ 가 필요하다.

```bash
# 현재 플랫폼용 바이너리를 bin/에 생성
./scripts/build-go.sh

# macOS/Linux/Windows 크로스 컴파일 (bin/에 8개 바이너리 생성)
./scripts/build-go.sh --all

# 또는 go run으로 바로 실행 (mcp-server 먼저, agent-app 나중)
cd mcp-server-go && DATABASE_URL=postgres://riwonace:riwonace@localhost:5433/riwonace \
  OLLAMA_BASE_URL=http://localhost:11434 SERVER_PORT=8081 go run ./...

# 별도 터미널
cd agent-app-go && MCP_SERVER_URL=http://localhost:8081 \
  OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=gemma3:1b SERVER_PORT=8080 go run .
```

기본 Spring AI 구현과 마찬가지로 `docker-compose.yml`의 `postgres`(5433)·
`ollama`(11434)에 연결한다. 벡터 데이터는 Spring AI `mcp-server`로 먼저 적재해야
한다(AIR와 동일한 제약). 세부 포팅 노트는
[`mcp-server-go/README.md`](../../mcp-server-go/README.md),
[`agent-app-go/README.md`](../../agent-app-go/README.md)를 참고한다.

### 실측 비교와 결론

startup, RSS, latency, 동시성, 답변 정확도를 실측해 비교했다. 요약하면:

| 지표 | Spring AI (baseline) | Go |
|---|---:|---:|
| mcp-server startup | 2.53s | 0.51s |
| mcp-server RSS | 312MB | 24MB |
| 답변 정확도 (302문항, 버그 수정 후) | 100% | 검증 안 함(baseline만 수정 반영) |

- **자원 효율은 Go가 명백히 우세**하다. startup 5배, RSS 13배 차이는 재현 가능한
  실측이며, 다중 인스턴스 스케일 아웃이나 서버리스 콜드스타트가 중요한 배포
  환경이라면 이 차이가 실질적 이득이 된다.
- **latency 차이는 언어 문제가 아니다.** 초기 실험에서 Go가 baseline보다 4~5배
  느리게 보였으나, `net/http/pprof` CPU 프로파일(Go 프로세스가 요청 처리 중 CPU를
  0.055%만 사용, 나머지는 `runtime.netpoll` 대기)과 애플리케이션을 완전히
  배제한 대조 실험(순수 curl→Ollama도 동일하게 불안정)으로, 원인이 로컬
  macOS + Docker Desktop VM + CPU 추론 환경의 Ollama 처리량 변동성임을 확정했다.
  이 결론은 로컬 환경에 한정되며 서버급 GPU 추론 인프라에서는 재검증이 필요하다.
- **baseline의 답변 정확도 버그 수정은 Go/Ktor 포팅에는 아직 반영되지 않았다.**
  gemma3:4b 기준 버그 수정 전 상태에서 Go/Ktor도 baseline과 동일하게
  60.0%/90.0%였다 — 즉 버그가 세 구현 모두에
  동일하게 존재했을 가능성이 높다. baseline에서 고친 것(AnswerabilityGate 오판,
  DeterministicSqlPlanner v2 미연결, 라우팅 키워드 누락 등)을 Go/Ktor 소스에도
  반영하는 작업은 아직 하지 않았다.

### 권장 사항

- **자원 제약이 실제 운영 요구사항이 아니라면(예: 서버리스, 대량 동시
  인스턴스), Spring AI를 기본으로 유지하는 것을 권장한다.** 답변 정확도 버그
  수정이 아직 Go 쪽에 반영되지 않아 두 구현의 정확도를 동일 조건에서 비교할
  근거가 없고, 이 프로젝트는 현재 로컬 단일 인스턴스로 운영되므로 startup/RSS
  이득이 실질적이지 않다.
- Go로 전환을 검토한다면, 먼저 baseline에서 확정된 버그 수정 8건(fix/
  answerability-gate-and-kg-search-chaining 브랜치 참고)을 Go 소스에 반영하고
  동일한 홀드아웃 검증(302문항 + 완전히 다른 데이터셋 298문항)을 다시 통과시킨
  뒤 판단해야 한다.

### 위험 / 트레이드오프

- **CI 미포함**: Go 모듈은 루트 Gradle CI 대상이 아니다. `agent-app-go`/
  `mcp-server-go` 각각의 `go test ./...`로만 검증된다.
- **정확도 회귀 미확인**: 위에서 설명한 대로 baseline 버그 수정이 Go에 반영
  안 됐으므로, 지금 Go로 전환하면 이미 baseline에서 고친 문제(예: 부서장 질의
  라우팅 실패, 카테고리별 매출 집계 오류)를 그대로 다시 겪을 수 있다.
- **표현 다양성 검증 부족**: Go 포팅 자체는 1:1 포팅으로 설계됐지만, baseline
  만큼 대규모(302문항+홀드아웃) 표현 다양성 검증을 거치지 않았다.
