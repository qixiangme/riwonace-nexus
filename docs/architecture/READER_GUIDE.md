# 시스템 읽기 안내

이 문서는 저장소를 처음 읽을 때 필요한 흐름만 설명합니다. 구현 세부사항은
[`ARCHITECTURE.md`](../../ARCHITECTURE.md), 재현 수치는
[`CONTEST_FINAL_BENCHMARK.md`](../research/CONTEST_FINAL_BENCHMARK.md)를 참고하세요.

## 한 문장으로

사용자 질문을 로컬 소형 모델에 한 번에 맡기지 않고, 질문을 분석해 필요한 MCP 도구만
실행한 뒤 데이터베이스 근거를 선별해 답하는 온프레미스 검색 시스템입니다.

## 요청이 처리되는 순서

```text
질문
  ↓
QueryProfiler ── 질문 유형·복잡도·불확실성 파악
  ↓
RuleBasedRouter ── SQL / VECTOR / GRAPH 결정
  ↓
ExecutionPlanner ── 독립 작업 병렬화, 의존 작업 순서화
  ↓
MCP Gateway ── 표준 도구 호출
  ├─ vector_search ─ 문서·정책·장애 보고서
  ├─ run_sql       ─ 관계형 집계·목록
  ├─ kg_search     ─ 사람·제품·고객 관계
  └─ db://schema   ─ NL2SQL 스키마 Resource
  ↓
EvidenceOptimizer / ContextCurator ── 예산 안에서 필요한 근거만 선택
  ↓
AnswerabilityGate ── 근거가 부족한 주장을 차단
  ↓
로컬 Ollama 모델 ── 답변과 출처 반환
```

## 왜 이 구조인가

| 문제 | 이 프로젝트의 대응 |
|---|---|
| 작은 모델이 도구를 잘못 선택함 | 결정적 라우터를 먼저 적용하고 애매한 경우에만 폴백 |
| 스키마를 모른 채 SQL을 생성함 | `db://schema`·SchemaLinker·결정적 고신뢰 SQL 계획 |
| 검색 결과가 너무 많아짐 | TACC와 증거 예산으로 관련 근거만 유지 |
| 구조화 결과를 모델이 다시 쓰며 숫자를 잃음 | SQL·그래프 결과는 구조화 근거를 보존하고 단일 `count`는 질문의 명·개·건·곳 단위로 표현 |
| 한 경로 장애가 전체 답변을 망침 | 실행 계획·복구 정책·부분 실패 `DEGRADED` 응답 |
| 프레임워크 교체 비용이 큼 | 에이전트와 서버 사이를 MCP 도구 계약으로 고정 |

## 실행 모듈

| 모듈 | 책임 | 기본 실행 |
|---|---|---|
| `agent-app-go` | 질문 분석, 라우팅, 계획, 답변 | `:8080` |
| `mcp-server-go` | Go MCP 도구·리소스, PostgreSQL 접근 | `:8081` |
| `agent-app-spring` / `mcp-server-spring` | 벡터 데이터 최초 적재, 대용량 데이터용 Kotlin/Spring AI 구현 | `:8080` / `:8081` |
| `mcp-server-air` | agent-app-spring과 짝을 이루는 Node/AIR 구현, 중간 성능대 | `:8082` |
| PostgreSQL + pgvector | 관계형·벡터·그래프 저장 | `:5433` |
| Ollama | 로컬 답변·임베딩 모델 | `:11434` |

기본 경로는 `agent-app-go → mcp-server-go`입니다. 세 구현체의 실측 비교는
[IMPLEMENTATIONS.md](./IMPLEMENTATIONS.md)를 참고합니다.

## 결과를 읽는 법

정확도 하나만 보지 않습니다.

1. **라우팅 적중률**: 필요한 도구를 선택했는가
2. **답변 정확도**: 선택한 근거로 정답을 반환했는가
3. **지연시간**: 평균보다 p50·p95를 우선 확인
4. **근거·오류**: 출처가 남고 부분 장애가 숨겨지지 않는가

공식 평가와 복합 평가의 원시 결과는 `eval/results/`에 있으며, 기준선과 후보는 같은
모델·데이터·반복 조건으로 비교합니다.

## 시작하기

```bash
docker compose up -d
docker exec riwonace-ollama ollama pull gemma4:e2b-it-qat
docker exec riwonace-ollama ollama pull nomic-embed-text

# 벡터 데이터 최초 적재(Spring AI mcp-server만 이 경로를 가진다, 최초 1회)
./gradlew :mcp-server-spring:bootRun
# 적재 완료 후 Ctrl+C로 종료

# 기본 Go 구현 실행
cd mcp-server-go && DATABASE_URL=postgres://riwonace:riwonace@localhost:5433/riwonace \
  OLLAMA_BASE_URL=http://localhost:11434 SERVER_PORT=8081 go run ./...
# 다른 터미널
cd agent-app-go && MCP_SERVER_URL=http://localhost:8081 \
  OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=gemma4:e2b-it-qat SERVER_PORT=8080 \
  ROUTER_FALLBACK=semantic-ai go run .
```

간단한 확인:

```bash
curl -s http://localhost:8080/api/tools
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"플랫폼팀 평균 급여는 얼마야?"}'
```
