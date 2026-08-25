# Riwonace Nexus

> MCP-based on-premise intelligent data search platform

[![CI](https://github.com/qixiangme/riwonace-nexus/actions/workflows/ci.yml/badge.svg)](https://github.com/qixiangme/riwonace-nexus/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

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
        agent-app (Go)
        프로파일링 · 실행계획 · 라우팅 · NL2SQL · 답변 생성
              │ MCP/SSE
              ▼
        mcp-server :8081 (Go)
        검색·SQL·그래프·스키마 도구, 데이터 적재
              │
              ▼
 PostgreSQL 16 + pgvector ── Ollama
 관계형·벡터·그래프 저장       로컬 추론·임베딩
```

기본 실행 경로는 `agent-app`(폴더: `agent-app-go`) → `mcp-server`(폴더:
`mcp-server-go`)입니다. 이 둘이 이 프로젝트의 표준 구현입니다. 302문항
동일 조건 실측 기준 답변 정확도 73.8%로 Kotlin/Spring AI 구현과 동급이면서,
자원 사용량(idle 대비 부하 시 peak RSS)은 43MB로 Spring AI(487MB)의
11분의 1입니다. 벡터 데이터 최초 적재와 대용량 데이터 처리에는
Kotlin/Spring AI 구현(`agent-app-spring`, `mcp-server-spring`)을 사용합니다.

같은 MCP 도구 계약을 Node.js(AIR 프레임워크)로 재현한 `mcp-server-air`도
있습니다 — agent-app은 Spring AI를 그대로 쓰고 mcp-server만 교체하는
중간 성능대의 구성입니다.

세 구현체의 배경과 실측 비교는
[IMPLEMENTATIONS.md](./docs/architecture/IMPLEMENTATIONS.md)에
정리했습니다.

현재 에이전트의 핵심 흐름은 `QueryProfiler → ExecutionPlanner → MCP Gateway →
EvidenceOptimizer / ContextCurator → AnswerabilityGate → Ollama`입니다. 단순 질문은
결정적 경로를 유지하고 복합·실패 가능성이 높은 질문만 실행 계획과 복구 정책을 사용합니다.

## 기술 스택

| 영역 | 기술 | 사용 목적 |
|---|---|---|
| 언어·런타임 | Go 1.21+, 공식 MCP SDK | 에이전트와 기본 MCP 서버 |
| 표준 프로토콜 | MCP, SSE | 에이전트와 데이터 도구의 구현 분리 |
| 데이터베이스 | PostgreSQL 16, pgvector | 관계형 데이터, 문서 벡터, 지식 그래프 통합 저장 |
| 로컬 AI | Ollama, `gemma3:1b`, `nomic-embed-text` | 답변·NL2SQL 생성과 문서 임베딩 |
| 대용량 처리용 구현체 | Kotlin 2.1, Java 17+, Spring Boot 3.5, Spring AI 1.0 | 벡터 데이터 최초 적재, 대용량 데이터·복잡한 트랜잭션 |
| 중간 성능 구현체 | Node.js(`@airmcp-dev/core`) | agent-app은 Spring AI 유지, mcp-server만 경량화 |
| 웹 클라이언트 | React 19, TypeScript 5.7, Vite 6 | 선택형 대화 UI |
| 빌드·검증 | Gradle Kotlin DSL, Go, npm, JUnit 5, Docker Compose | 빌드, 테스트, 로컬 인프라 실행 |

저사양 PC의 기본 모델 용량은 약 1.1GB입니다. 환경에 따라
`OLLAMA_MODEL=qwen2.5:3b`처럼 모델만 교체할 수 있습니다. 설계 원칙은
[ARCHITECTURE.md](./ARCHITECTURE.md)를 참고하세요.

## 모듈

기본 구현(표준 실행 경로)은 다음 두 모듈입니다.

| 모듈 | 폴더 | 포트 | 역할 |
|---|---|---:|---|
| `agent-app` | `agent-app-go` | 8080 | 질문 프로파일링·실행계획, 라우팅, NL2SQL, 근거 검증, 답변 생성, HTTP API |
| `mcp-server` | `mcp-server-go` | 8081 | 검색·SQL·그래프·스키마 도구와 데이터 적재 제공 |

같은 MCP 도구 계약을 재현한 다른 구현체입니다(각 구현체 상세는
[IMPLEMENTATIONS.md](./docs/architecture/IMPLEMENTATIONS.md) 참고).

| 폴더 | 포트 | 설명 |
|---|---:|---|
| `agent-app-spring` | 8080 | 대용량 데이터·복잡한 트랜잭션용 Kotlin/Spring AI 구현체, 벡터 데이터 최초 적재 담당 |
| `mcp-server-spring` | 8081 | 위와 동일한 목적의 Kotlin/Spring AI MCP 서버(동시 실행 시 포트 변경 필요) |
| `mcp-server-air` | 8082 | agent-app-spring과 짝을 이루는 Node.js(AIR 프레임워크) MCP 서버, 중간 성능대 |

그 외 모듈입니다.

| 모듈 | 포트 | 역할 |
|---|---:|---|
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

## MCP 아키텍처와 구현체

에이전트와 데이터 도구 서버는 [Model Context Protocol](https://modelcontextprotocol.io/)로
통신한다. 통신 흐름·도구별 입출력·보안 계층 같은 상세 계약은
[MCP_CONTRACT.md](./docs/architecture/MCP_CONTRACT.md)에 정리했다.

기본 구현(Go, `mcp-server-go`/`agent-app-go`) 외에 같은 계약을 대용량 데이터용
언어(Kotlin/Spring AI, `mcp-server-spring`/`agent-app-spring`)와 중간 성능대
프레임워크(Node.js AIR, `mcp-server-air`)로 재현한 구현체가 있다. 각 구현체를
선택하는 기준과 실측 비교는
[IMPLEMENTATIONS.md](./docs/architecture/IMPLEMENTATIONS.md)에
정리했다.

## 빠른 시작

필수 조건은 Docker 엔진, Go 1.21+, Java 17+(최초 적재용)입니다.

```bash
# 1. PostgreSQL과 Ollama 실행
docker compose up -d

# 2. 모델 다운로드(최초 1회)
docker exec riwonace-ollama ollama pull gemma3:1b
docker exec riwonace-ollama ollama pull nomic-embed-text

# 3. 벡터 데이터 최초 적재(Spring AI mcp-server만 이 적재 경로를 가진다, 최초 1회)
./gradlew :mcp-server-spring:bootRun
# 적재 완료 후 Ctrl+C로 종료

# 4. 기본 Go MCP 서버 실행
cd mcp-server-go && DATABASE_URL=postgres://riwonace:riwonace@localhost:5433/riwonace \
  OLLAMA_BASE_URL=http://localhost:11434 SERVER_PORT=8081 go run ./...

# 5. 별도 터미널에서 Go 에이전트 실행
cd agent-app-go && MCP_SERVER_URL=http://localhost:8081 \
  OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=gemma3:1b SERVER_PORT=8080 \
  ROUTER_FALLBACK=semantic-ai go run .
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

# 시드 문서 재적재(Spring AI mcp-server에만 있는 경로)
curl -s -X POST http://localhost:8081/admin/ingest
```

## 테스트

```bash
# Go 전체 테스트
cd agent-app-go && go test ./...
cd mcp-server-go && go test ./...

# 웹 클라이언트 빌드
cd client
npm ci
npm run build
```

벤치마크를 변경했다면 같은 데이터셋·모델·설정·반복 횟수로 기준선과 후보를 모두 측정하고,
문항별 원시 JSON 결과를 보존해야 합니다.

CI(`./gradlew test`, `npm run build`, `eval/` 파이썬 테스트)는 벡터 데이터 적재 경로인
`agent-app-spring`/`mcp-server-spring`/`client`/`eval`을 검증합니다. 기본 구현인
`agent-app-go`/`mcp-server-go`는 각자의 `go test ./...`로, `mcp-server-air`는
자체 `npm test`로 별도 검증합니다.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [최종 결과 보고서](./report/FINAL_REPORT.md) | 프로젝트 개요, 3-way 구현체 비교, 정확도 개선 이력, SBOM, 알려진 한계를 종합한 최종 보고서 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 전체 구조, 요청 처리 흐름, 주요 설계 결정 |
| [MCP_CONTRACT.md](./docs/architecture/MCP_CONTRACT.md) | MCP 통신 흐름, 도구별 입출력, 보안 계층 |
| [IMPLEMENTATIONS.md](./docs/architecture/IMPLEMENTATIONS.md) | Go/Spring AI/AIR 구현체별 배경, 실행 방법, 실측 비교, 선택 기준 |
| [BENCHMARK.md](./BENCHMARK.md) | 벤치마크 결과 및 재현 방법 |
| [최종 재현 벤치마크](./docs/research/CONTEST_FINAL_BENCHMARK.md) | 복합 질문·TACC·AIR/Spring AI·장애 주입 판정 |
| [실험 원시 결과 아카이브 안내](./eval/results/README.md) | 개별 실험 원시 JSON과 관련 연구 문서 매핑 |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | 기여자가 먼저 확인할 핵심 규칙 |
| [이슈와 PR 운영 절차](./docs/contributing/WORKFLOW.md) | 작업 유형, 브랜치, 리뷰 게이트, PR 절차 |
| [벤치마크 무결성 정책](./docs/contributing/BENCHMARK_POLICY.md) | 데이터 누수 방지, 재현 조건, 최소 통과 기준 |
| [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) | 커뮤니티 행동 강령 |
| [SECURITY.md](./SECURITY.md) | 취약점 제보 절차, 알려진 보안 한계 |
| [LICENSE](./LICENSE) | MIT 라이선스 |

## 기여하기

기여를 시작하기 전에 [CONTRIBUTING.md](./CONTRIBUTING.md)를 읽어 주세요. 이슈 등록,
브랜치 작성, 테스트와 벤치마크, PR 템플릿, Draft·Ready·Close 판정 기준을 한곳에
정리했습니다. 이슈와 PR 템플릿은 기준 브랜치, 현재 문제 증거, 전후 검증 방법,
위험과 되돌리기, 주·보조 평가항목을 필수로 받습니다 — 사람과 자동화가 같은 근거
수준으로 작성하도록 [운영 절차](./docs/contributing/WORKFLOW.md)와 같은 계약을 씁니다. 이 프로젝트에 참여하는 모든 사람은 [행동 강령](./CODE_OF_CONDUCT.md)을
따라야 합니다. 보안 취약점은 [SECURITY.md](./SECURITY.md)의 절차에 따라 제보해 주세요.
