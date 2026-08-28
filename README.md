# Liwonace Nexus

[![CI](https://github.com/qixiangme/riwonace-nexus/actions/workflows/ci.yml/badge.svg)](https://github.com/qixiangme/riwonace-nexus/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

Liwonace Nexus는 사내 문서, 관계형 데이터, 지식 그래프를 자연어로 검색하는
온프레미스 AI 데이터 플랫폼입니다. 로컬 Ollama 모델을 사용하며, 데이터 접근 기능은
[Model Context Protocol(MCP)](https://modelcontextprotocol.io/) 도구로 제공합니다.

질문에 따라 문서 검색, NL2SQL, 지식 그래프 탐색을 선택하고 답변과 함께 사용한 도구,
근거 출처, 처리 시간을 반환합니다.

## 주요 기능

- pgvector 기반 문서 검색
- 스키마 기반 NL2SQL 및 읽기 전용 SQL 실행
- 엔티티 관계를 찾는 지식 그래프 검색
- 복합 질문을 위한 다중 도구 실행과 근거 선별
- 외부 AI API 없이 동작하는 로컬 추론 환경
- 실행 경로와 출처를 확인할 수 있는 응답 트레이스

## 구성

```text
React Client (:5173)
        │
        ▼
Agent App (:8080)
질문 분석 · 라우팅 · 답변 생성
        │ MCP/SSE
        ▼
MCP Server (:8081)
문서 검색 · SQL · 지식 그래프
        │
        ├── PostgreSQL + pgvector (:5433)
        └── Ollama (:11434)
```

기본 실행 경로는 Go로 작성된 `agent-app-go`와 `mcp-server-go`입니다. 대용량 데이터
적재 및 비교 구현으로 Kotlin/Spring AI 버전을, 별도 MCP 서버 구현으로 Node.js/AIR
버전을 제공합니다.

| 경로 | 역할 |
|---|---|
| `agent-app-go` | 기본 에이전트 및 HTTP API |
| `mcp-server-go` | 기본 MCP 데이터 서버 |
| `client` | React 웹 클라이언트 |
| `agent-app-spring`, `mcp-server-spring` | Kotlin/Spring AI 구현 및 최초 벡터 적재 |
| `mcp-server-air` | Node.js/AIR 기반 MCP 서버 구현 |
| `companyx-dataset-v1.0` | 예제 데이터셋 |
| `eval` | 평가 및 벤치마크 도구 |

지정과제 권장 모델 Gemma 4 E2B(`gemma4:e2b-it-qat`) 기준 302문항 동일 조건
실측에서 세 구현체 모두 라우팅 100.0%, 답변 정확도 100.0%(SQL·VECTOR·GRAPH
전 영역, 오류 0건)를 달성했으며, 리소스는 Go가 Spring AI 대비 약
11분의 1입니다. 세부 비교는
[구현체 비교](./docs/architecture/IMPLEMENTATIONS.md)를 참고하세요.

## 빠른 시작

### 준비 사항

- Docker 및 Docker Compose
- Go 1.21 이상
- Java 17 이상
- Node.js 및 npm — 웹 클라이언트를 실행할 경우

### 1. 인프라 실행

```bash
docker compose up -d
docker exec riwonace-ollama ollama pull gemma4:e2b-it-qat
docker exec riwonace-ollama ollama pull nomic-embed-text
```

### 2. 데이터 최초 적재

최초 한 번 Spring MCP 서버를 실행해 벡터 데이터를 적재합니다. 적재가 끝나면 서버를
종료합니다.

```bash
./gradlew :mcp-server-spring:bootRun
```

### 3. 기본 서버 실행

터미널을 두 개 열어 각각 실행합니다. 아래 환경 변수는 기본값과 같으므로 로컬 기본
구성에서는 생략할 수 있습니다.

```bash
# 터미널 1: MCP 서버
cd mcp-server-go
DATABASE_URL=postgres://riwonace:riwonace@localhost:5433/riwonace \
OLLAMA_BASE_URL=http://localhost:11434 \
SERVER_PORT=8081 \
go run ./...
```

```bash
# 터미널 2: 에이전트 앱
cd agent-app-go
MCP_SERVER_URL=http://localhost:8081 \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=gemma4:e2b-it-qat \
SERVER_PORT=8080 \
go run .
```

### 4. 웹 클라이언트 실행

```bash
cd client
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173`으로 접속합니다.

## API 사용

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"플랫폼팀 직원의 평균 급여는 얼마야?"}'
```

| 엔드포인트 | 설명 |
|---|---|
| `POST /api/chat` | 질문 처리 |
| `POST /api/chat/v2?trace=true` | 실행 트레이스를 포함한 질문 처리 |
| `GET /api/tools` | 연결된 MCP 도구 및 리소스 조회 |
| `GET /api/v2/status` | 에이전트 기능 상태 조회 |

MCP 서버는 `vector_search`, `run_sql`, `kg_search` 도구와 `db://schema` 리소스를
제공합니다. SQL 실행은 읽기 전용 쿼리로 제한됩니다.

## 테스트

```bash
(cd agent-app-go && go test ./...)
(cd mcp-server-go && go test ./...)
(cd client && npm ci && npm run build)
./gradlew test
```

## 문서

- [아키텍처](./ARCHITECTURE.md)
- [MCP 도구 계약](./docs/architecture/MCP_CONTRACT.md)
- [구현체 비교](./docs/architecture/IMPLEMENTATIONS.md)
- [벤치마크](./BENCHMARK.md)
- [기여 가이드](./CONTRIBUTING.md)
- [보안 정책](./SECURITY.md)

## 라이선스

[MIT License](./LICENSE)
