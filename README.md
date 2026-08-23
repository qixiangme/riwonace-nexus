# MCP 지능형 데이터 플랫폼

사내의 문서, 관계형 테이블, 지식 그래프를 한곳에서 조회하고 일상어 질문에 근거 있는 답을
제공하는 **온프레미스 AI 데이터 검색 플랫폼**입니다. 외부 AI API 대신 로컬 Ollama 모델을
사용하고, 데이터 접근은 MCP(Model Context Protocol) 도구로 표준화합니다.

질문은 성격에 따라 다음 경로로 처리됩니다.

- 개념·정책·문서 질문 → pgvector 기반 `vector_search`
- 통계·집계·목록 질문 → 스키마 기반 NL2SQL → 읽기 전용 `run_sql`
- 사람·제품·프로젝트 사이의 관계 질문 → 지식 그래프 `kg_search`
- 복합 질문 → 필요한 도구를 병렬 호출한 뒤 근거를 선별하여 답변 생성

답변에는 선택한 라우트, 호출한 MCP 도구, 컨텍스트 출처, 지연 시간을 함께 제공하므로
어떤 데이터로 답했는지 확인할 수 있습니다.

처음 읽는다면 [시스템 읽기 안내](./docs/architecture/READER_GUIDE.md)에서 요청 흐름과
각 모듈의 책임을 먼저 확인하세요. 구현 세부사항은 [아키텍처 설계서](./ARCHITECTURE.md),
재현 가능한 수치는 [최종 벤치마크](./docs/research/CONTEST_FINAL_BENCHMARK.md)에 정리했습니다.

## 전체 구조

```text
사용자 / React 웹 클라이언트
              │ HTTP :8080
              ▼
       agent-app (Spring AI)
       프로파일링 · 실행계획 · 라우팅 · NL2SQL · 답변 생성
              │ MCP/SSE
       ┌──────┴────────────────────┐
       ▼                           ▼
mcp-server :8081             air-server :8082
기본 Spring AI 구현           선택형 AIR 구현
       └──────┬────────────────────┘
              ▼
 PostgreSQL 16 + pgvector ── Ollama
 관계형·벡터·그래프 저장       로컬 추론·임베딩
```

기본 실행 경로는 `agent-app` → `mcp-server`입니다. `air-server`는 기본 서버를 동시에
실행하기 위한 모듈이 아니라, 같은 MCP 도구 계약을 다른 프레임워크로 구현해 서버를
교체할 수 있음을 검증하기 위한 선택형 구현입니다. `mcp-server-go`/`agent-app-go`도
같은 방식의 선택형 구현으로, 전체 스택을 Go + 공식 MCP SDK로 1:1 포팅해 언어·런타임
차이에 따른 성능·자원 사용량을 비교합니다.

현재 에이전트의 핵심 흐름은 `QueryProfiler → ExecutionPlanner → MCP Gateway →
EvidenceOptimizer / ContextCurator → AnswerabilityGate → Ollama`입니다. 단순 질문은
결정적 경로를 유지하고 복합·실패 가능성이 높은 질문만 실행 계획과 복구 정책을 사용합니다.

## 기술 스택

| 영역 | 기술 | 사용 목적 |
|---|---|---|
| 언어·런타임 | Kotlin 2.1, Java 17+ | 에이전트와 기본 MCP 서버 |
| 애플리케이션 | Spring Boot 3.5, Spring AI 1.0 | Ollama, MCP 서버·클라이언트, pgvector 연동 |
| 표준 프로토콜 | MCP, SSE | 에이전트와 데이터 도구의 구현 분리 |
| 데이터베이스 | PostgreSQL 16, pgvector | 관계형 데이터, 문서 벡터, 지식 그래프 통합 저장 |
| 로컬 AI | Ollama, `gemma3:1b`, `nomic-embed-text` | 답변·NL2SQL 생성과 문서 임베딩 |
| 대체 MCP 구현 | Node.js, `@airmcp-dev/core`, `pg` | AIR 호환성 및 서버 교체 가능성 검증 |
| 웹 클라이언트 | React 19, TypeScript 5.7, Vite 6 | 선택형 대화 UI |
| 빌드·검증 | Gradle Kotlin DSL, npm, JUnit 5, Docker Compose | 빌드, 테스트, 로컬 인프라 실행 |

저사양 PC의 기본 모델 용량은 약 1.1GB입니다. 환경에 따라
`OLLAMA_MODEL=qwen2.5:3b`처럼 모델만 교체할 수 있습니다. 설계 원칙은
[ARCHITECTURE.md](./ARCHITECTURE.md)를 참고하세요.

## 모듈

| 모듈 | 포트 | 역할 |
|---|---:|---|
| `agent-app` | 8080 | 질문 프로파일링·실행계획, 라우팅, NL2SQL, 근거 검증, 답변 생성, HTTP API |
| `mcp-server` | 8081 | 기본 MCP 서버. 검색·SQL·그래프·스키마 도구와 데이터 적재 제공 |
| `air-server` | 8082 | 동일한 도구 이름과 안전 정책을 제공하는 선택형 Node.js MCP 서버 |
| `agent-app-go` | 8080 | `agent-app`의 선택형 Go 포팅 (동시 실행 시 포트 변경 필요) |
| `mcp-server-go` | 8081 | `mcp-server`의 선택형 Go 포팅 (동시 실행 시 포트 변경 필요) |
| `client` | 5173 | 선택형 React 웹 클라이언트 |
| PostgreSQL + pgvector | 5433 | 문서 벡터, 관계형 데이터, 지식 그래프 저장 |
| Ollama | 11434 | 로컬 대화 모델과 임베딩 모델 실행 |

MCP 서버가 제공하는 도구는 다음과 같습니다.

| 도구 | 역할 |
|---|---|
| `vector_search` | 질문과 의미적으로 가까운 문서 검색 |
| `get_schema` | SQL 생성에 필요한 테이블·열·값 힌트 조회 |
| `run_sql` | 검증을 통과한 읽기 전용 SELECT/WITH 실행 |
| `kg_search` | 주어–관계–목적어 형태의 지식 그래프 탐색 |

## MCP 아키텍처와 대체 구현

에이전트와 데이터 도구 서버는 [Model Context Protocol](https://modelcontextprotocol.io/)로
통신한다. 통신 흐름·도구별 입출력·보안 계층 같은 상세 계약은
[MCP_CONTRACT.md](./docs/architecture/MCP_CONTRACT.md)에 정리했다.

기본 구현(Spring AI) 외에 같은 계약을 다른 프레임워크(Node.js AIR, `air-server`)와
다른 언어(Go, `mcp-server-go`/`agent-app-go`)로 재현한 선택형 비교 구현이 있다.
왜 두 실험을 만들었는지, 실측 비교 결과와 권장 사항은
[ALTERNATIVE_IMPLEMENTATIONS.md](./docs/architecture/ALTERNATIVE_IMPLEMENTATIONS.md)에
정리했다.

## 빠른 시작

필수 조건은 Docker 엔진과 Java 17+입니다.

```bash
# 1. PostgreSQL과 Ollama 실행
docker compose up -d

# 2. 모델 다운로드(최초 1회)
docker exec riwonace-ollama ollama pull gemma3:1b
docker exec riwonace-ollama ollama pull nomic-embed-text

# 3. 기본 Spring AI MCP 서버 실행
./gradlew :mcp-server:bootRun

# 4. 별도 터미널에서 에이전트 실행
./gradlew :agent-app:bootRun
```

웹 UI를 사용하려면 별도 터미널에서 다음 명령을 실행합니다.

```bash
cd client
npm ci
npm run dev
```

### 사용 예시

```bash
# 개념 질문 → vector_search
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"question": "MCP가 기존 RAG보다 뭐가 좋아?"}'

# 집계 질문 → NL2SQL + run_sql
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"question": "플랫폼팀 직원의 평균 급여는 얼마야?"}'

# 관계 질문 → kg_search
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"question": "AIR는 누가 개발했어?"}'

# MCP 연결 상태와 노출 도구 확인
curl -s http://localhost:8080/api/tools

# Ollama를 나중에 실행한 경우 시드 문서 재적재
curl -s -X POST http://localhost:8081/admin/ingest
```

## 테스트

```bash
# Kotlin 전체 테스트
./gradlew test

# 웹 클라이언트 빌드
cd client
npm ci
npm run build
```

벤치마크를 변경했다면 같은 데이터셋·모델·설정·반복 횟수로 기준선과 후보를 모두 측정하고,
문항별 원시 JSON 결과를 보존해야 합니다.

CI(`./gradlew test`, `npm run build`, `eval/` 파이썬 테스트)는 기본 실행 경로인
`agent-app`/`mcp-server`/`client`/`eval`만 검증합니다. `air-server`,
`agent-app-go`/`mcp-server-go`는 선택형 비교 구현이라 CI 대상이 아니며, 각자의
오라클 테스트(`agent-app-go test`, `air-server`의 `npm test`)로 별도 검증합니다.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 전체 구조, 요청 처리 흐름, 주요 설계 결정 |
| [MCP_CONTRACT.md](./docs/architecture/MCP_CONTRACT.md) | MCP 통신 흐름, 도구별 입출력, 보안 계층 |
| [ALTERNATIVE_IMPLEMENTATIONS.md](./docs/architecture/ALTERNATIVE_IMPLEMENTATIONS.md) | AIR/Go 대체 구현의 배경, 실행 방법, 실측 비교, 권장 사항·위험 |
| [BENCHMARK.md](./BENCHMARK.md) | 벤치마크 결과 및 재현 방법 |
| [최종 재현 벤치마크](./docs/research/CONTEST_FINAL_BENCHMARK.md) | 복합 질문·TACC·AIR/Spring AI·장애 주입 판정 |
| [AIR 프레임워크 피드백](./docs/research/AIR_FRAMEWORK_FEEDBACK.md) | AIR 비교 결과와 운영 피드백 |
| [3-way 벤치마크·baseline 정확도 개선](./eval/bench-results/README.md) | Go/Ktor 성능 비교, baseline 답변 정확도 40%→90%대 개선과 30→93→302→홀드아웃 일반화 검증 |
| [실험 원시 결과 아카이브 안내](./eval/results/README.md) | 개별 실험 원시 JSON과 관련 연구 문서 매핑 |
| [레드팀 보안 검토](./docs/security/RED_TEAM_REVIEW_2026-08-13.md) | 애플리케이션 보안 검토와 차단 항목 |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | 기여자가 먼저 확인할 핵심 규칙 |
| [이슈와 PR 운영 절차](./docs/contributing/WORKFLOW.md) | 작업 유형, 브랜치, 리뷰 게이트, PR 절차 |
| [벤치마크 무결성 정책](./docs/contributing/BENCHMARK_POLICY.md) | 데이터 누수 방지, 재현 조건, 최소 통과 기준 |

## 기여하기

기여를 시작하기 전에 [CONTRIBUTING.md](./CONTRIBUTING.md)를 읽어 주세요. 이슈 등록,
브랜치 작성, 테스트와 벤치마크, PR 템플릿, Draft·Ready·Close 판정 기준을 한곳에
정리했습니다. 보안 취약점은 공개 이슈 대신 ichangmin380@gmail.com 으로 제보 부탁드립니다
