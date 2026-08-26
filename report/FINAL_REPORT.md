# Riwonace Nexus 최종 결과 보고서

작성일: 2026-08-26 · 기준 커밋: `9bf02eb` (main) · 저장소: https://github.com/qixiangme/riwonace-nexus

## 1. 프로젝트 개요

Riwonace Nexus는 사내 문서, 관계형 테이블, 지식 그래프를 자연어 질문 하나로
조회하는 온프레미스 AI 데이터 검색 플랫폼이다. 외부 AI API 대신 로컬 Ollama
모델을 사용한다.

이 시스템의 핵심은 **질문에 맞는 데이터 도구를 골라 근거를 찾는 것**이다.
MCP 서버는 3개의 검색 도구와 SQL 생성을 돕는 1개의 스키마 리소스를 제공한다.

### 1.1 세 가지 도구가 처리하는 질문

1. **`vector_search` — 문서의 의미를 검색한다.**
   규정, 정책, 제품 설명처럼 문장으로 작성된 자료에서 질문과 의미가 가까운
   부분을 찾는다. 예를 들어 사용자가 “재택근무 신청은 며칠 전에 해야 해?”라고
   물으면 관련 사내 규정 문단을 찾아 답변 근거로 사용한다.

2. **`run_sql` — 정확한 수치와 목록을 조회한다.**
   직원 수, 평균 급여, 계약 건수처럼 계산이 필요한 질문을 SQL로 바꿔 관계형
   테이블에서 조회한다. 예를 들어 “플랫폼팀 직원의 평균 급여는 얼마야?”라는
   질문에는 먼저 `db://schema`에서 테이블과 열 구조를 확인하고, 안전한 읽기 전용
   SQL을 만든 뒤 `run_sql`로 실행한다.

3. **`kg_search` — 데이터 사이의 관계를 탐색한다.**
   사람, 부서, 제품, 프로젝트가 어떻게 연결되어 있는지 지식 그래프에서 찾는다.
   예를 들어 “AIR를 개발한 사람은 누구야?”라는 질문에는 `개발자 → 개발 → AIR`
   관계를 탐색해 답한다.

여러 종류의 근거가 필요한 질문에는 도구를 함께 사용한다. 예를 들어 “플랫폼팀이
담당하는 제품과 관련 계약 수를 알려줘”라는 질문은 `kg_search`로 팀과 제품의
관계를 찾고 `run_sql`로 계약 수를 계산한 뒤, 두 결과를 하나의 답변으로 합친다.

모든 답변에는 선택한 경로, 호출한 도구, 근거 출처, 처리 시간을 함께 제공하므로
사용자는 결과가 어디에서 나왔는지 확인할 수 있다.

## 2. 시스템 아키텍처

![사용자 질문이 세 가지 MCP 도구를 거쳐 답변이 되는 과정](./architecture-diagram.svg)

사용자 질문은 `agent-app-go`에 들어온다. 에이전트는 질문의 의도를 분석해 세 도구
중 하나 또는 여러 개를 선택한다. `mcp-server-go`는 PostgreSQL과 pgvector에서
근거를 조회하고, 에이전트는 조회 결과가 질문에 답하기 충분한지 검사한다. 마지막으로
Ollama가 선택된 근거만 사용해 자연어 답변을 만든다.

에이전트와 MCP 서버 사이의 경계는 특정 언어·프레임워크가 아니라 이
도구 계약 자체다. 같은 계약을 세 가지 구현체로 만들어 실측 비교했다
(3절).

## 3. 구현체 3-way 비교와 최종 결정

### 3.1 비교 대상

| 구현체 | 구성 | 위치 |
|---|---|---|
| **Go** (기본) | `agent-app-go` + `mcp-server-go` | 표준 실행 경로 |
| Spring AI | `agent-app-spring` + `mcp-server-spring` | 벡터 데이터 최초 적재, 대용량 데이터용 |
| AIR | `agent-app-spring` + `mcp-server-air`(Node.js) | agent-app은 Spring AI 유지, mcp-server만 경량화 |

### 3.2 실측 조건

지정과제 공지가 권장하는 모델은 **Gemma 4 E2B**(Ollama 태그
`gemma4:e2b-it-qat`)다. `eval/generalization-eval.json`(302문항) +
`eval/resource-check-100.json`(100문항 리소스 측정용 서브셋)으로, 동일
모델(`gemma4:e2b-it-qat`, 모델 에스컬레이션 비활성화), 동일 seed(42),
`ROUTER_FALLBACK=semantic-ai` 조건에서 세 구현체를 측정했다.

### 3.3 정확도 (302문항, Gemma 4 E2B, 최종)

| 지표 | Go | Spring AI | AIR |
|---|---:|---:|---:|
| 라우팅 정확도 | 100.0% | 100.0% | 100.0% |
| 답변 정확도 | 100.0% | 100.0% | 100.0% |
| SQL 답변 정확도 | 100.0% | 100.0% | 100.0% |
| VECTOR 답변 정확도 | 100.0% | 100.0% | 100.0% |
| GRAPH 답변 정확도 | 100.0% | 100.0% | 100.0% |
| 평균 지연 | 11,422ms | 11,283ms | 10,792ms |
| 오류 | 0/302 | 0/302 | 0/302 |

세 구현체 모두 302문항 전체(SQL/VECTOR/GRAPH)에서 라우팅·답변 정확도
100%를 달성했다. 초기 검증에 쓴 `gemma3:1b` + 에스컬레이션 조합(답변
정확도 73.5~73.8%) 대비 Gemma 4 E2B 단일 모델은 **26%p 이상 개선**됐다.
93%대에서 100%로 마지막 격차를 좁힌 것은 아키텍처 변경이 아니라 두 건의
정밀한 버그 수정이었다:

- **VECTOR 76.7%→100%**: 채점 스크립트가 "7,917만원"(모델 답변)과
  "7917만원"(정답 키워드)을 쉼표 차이로 오답 처리하던 버그. 답변은
  처음부터 정확했다.
- **SQL 90.5~91.1%→100%**: "OO 업종 고객사 계약 건수는?" 유형에서
  LLM이 `contracts` JOIN 없이 `clients` 테이블만 세던 문제.
  `DeterministicSqlPlanner`에 `isIndustryContractCount` 결정적 패턴을
  추가해 LLM 생성 이전에 올바른 쿼리로 우선 처리하도록 해결.

이 재검증 과정에서 `ModelEscalator`가 `enabled=false`로 꺼도 재에스컬레이션
로직이 이를 무시하던 baseline 버그, 그리고 세 구현체 모두
`ROUTER_FALLBACK=semantic-ai` 없이 실행하면 GRAPH 라우팅이 42%까지
떨어지는 공통 설계 함정도 함께 확인·문서화했다.

### 3.4 리소스 사용량 (100문항, idle 대비 부하 시 peak RSS, `gemma3:1b` 세대 실측)

| 구현체 | agent-app RSS | mcp-server RSS | 합계 |
|---|---:|---:|---:|
| **Go** | 20.5MB | 22.7MB | **43.2MB** |
| AIR | 201.8MB | 87.1MB | 293.9MB |
| Spring AI | 179.6MB | 307.8MB | 487.4MB |

Go는 Spring AI 대비 리소스를 약 11분의 1로 줄인다. 세 구현체 모두 부하를
걸어도(idle → 100문항 처리) peak RSS가 idle 대비 거의 증가하지 않았다 —
이 규모(직원 45명 등 Company-X 시드 데이터 수준)에서는 메모리가 요청량이
아니라 런타임 자체(Go vs JVM vs Node.js)로 결정된다.

### 3.5 latency 조사와 방법론적 발견

최초 측정에서 Go가 baseline보다 3~4배 느리게 나왔다. 이 원인을 추적하는
과정에서 다음을 확인했다.

1. **로컬 macOS의 네이티브 `Ollama.app`과 Docker 컨테이너의 Ollama가
   포트 11434를 동시에 점유**(IPv4/IPv6 각각 바인딩)하고 있어, 요청이
   두 백엔드 중 예측 불가능한 쪽으로 갔다. Java(Spring AI)의 HTTP
   클라이언트는 커넥션을 재사용해 우연히 한산한 백엔드에 고정됐고, Go는
   매 요청 새 커넥션을 맺으며 그때그때 다른(때로는 바쁜) 백엔드로 튕겼다.
2. **macOS Spotlight 인덱싱**(`mds`/`mds_stores`)이 벤치마크와 무관하게
   CPU를 크게 잠식하고 있었다.
3. **`seed` 파라미터 부재**로 Ollama가 `temperature=0`이어도 매 요청 다른
   난수 상태로 샘플링해, 완전히 같은 프롬프트에도 출력 토큰 수가 크게
   요동쳤다(→ PR #118로 seed=42 고정 수정).

세 요인을 모두 제거한 뒤 재측정하니 Go(5,477ms)와 Spring AI(5,319ms)의
latency가 3% 차이로 수렴했다. **latency 차이는 언어 성능 문제가 아니라
로컬 개발 환경의 리소스 경합 문제였다.** 이 결론은 로컬 환경 기준이며,
서버급 GPU 추론 인프라에서는 재검증이 필요하다.

### 3.6 최종 결정: Go를 기본 구현으로 채택

정확도는 동급, 리소스는 11분의 1이라는 실측 근거로 **Go(`agent-app-go`,
`mcp-server-go`)를 기본 실행 경로로 채택**했다. Spring AI는 벡터 데이터
최초 적재와 대용량 데이터 처리용으로, AIR는 agent-app을 Spring AI로
유지하면서 mcp-server만 경량화하고 싶을 때의 중간 성능대 구성으로
위치를 재정의했다.

이 결정은 **소규모 데이터셋**(직원 45명, 수십~수백 row 규모) 실측
기준이다. 다음 조건에서는 Spring AI 전환을 검토해야 한다.

- 핵심 엔티티 규모가 수천~수만 건 이상으로 커지는 경우
- 쿼리 하나가 반환하는 row가 수천 건 이상인 경우
- 동시 접속자·동시 요청이 많아 커넥션 풀 관리가 중요해지는 경우

대용량 데이터에서 Spring AI(HikariCP 커넥션 풀, JDBC 스트리밍, GC 튜닝)가
유리하다는 것은 일반적으로 알려진 특성이며, 이 저장소에서 대용량 데이터로
직접 재현 측정한 결과는 아직 없다. 규모가 커지는 배포를 계획한다면 이
문서의 실측 방법론(`eval/resource-check-100.json` + `ps` 샘플링)을
대용량 시드 데이터로 반복해 전환 임계값을 확인해야 한다.

세부 실측 데이터와 실행 방법은
[`docs/architecture/IMPLEMENTATIONS.md`](../docs/architecture/IMPLEMENTATIONS.md)에
정리했다.

## 4. 정확도 개선 이력

라우팅·NL2SQL·답변 생성 로직은 여러 차례 반복 검증을 거쳤다.

| 단계 | 답변 정확도 | 비고 |
|---|---:|---|
| vanilla MCP(도구 선택을 LLM에 위임) | 30.0% | 과도한 도구 호출, 결과 병합 실패 |
| 결정적 라우팅 + 근거 선별 도입 | 93.3%(복합 30문항) | `RuleBasedRouter`, `EvidenceOptimizer` 도입 |
| baseline 버그 8건 수정 (PR #108) | 40%→90%대 | `AnswerabilityGate` 클레임 오판, `DeterministicSqlPlanner` v2 미연결, 라우팅 키워드 누락, `kg_search` predicate 체이닝 부재 등 |
| 302문항(문체 3~4배 변형) 재검증 | 91.4%→99.0% | 표현 다양성에서 드러난 추가 버그 3건 수정 |
| 완전히 다른 데이터셋(Nova-Tech) 홀드아웃 298문항 | 96.0%→99.7% | Company-X 고유값 암기 여부 배제, 하드코딩 2건 발견·수정 |
| Go/AIR에 baseline 수정 반영 (PR #113, #117) | 73.8%/73.5% | 3-way 동일 조건 재검증(3절), `gemma3:1b` + 에스컬레이션 기준 |
| 지정과제 권장 모델 Gemma 4 E2B로 전환 | 92.7~93.0% | 모델 에스컬레이션 없이 단일 모델로 재검증(3.3절), `ModelEscalator` enabled 플래그 무시 버그 발견·수정 |
| VECTOR 채점 버그 + SQL industry-count 패턴 수정 | 100.0% | 3-way 302문항 전 영역 만점(3.3절), 세 구현체 소수점까지 동일 |

핵심 교훈: **적은 문항 수(30개)의 개선이 큰 문항 수(93개, 302개)로
일반화된다고 가정하지 않고, 표현을 바꾸거나 완전히 다른 데이터로
재검증할 때마다 정확도가 실제로 하락하는지 확인했다.** 이 과정에서
Company-X 데이터셋 고유값에 암기된 하드코딩을 여러 차례 발견해 제거했다.

## 5. 소프트웨어 구성 목록 (SBOM)

| 구분 | 구성 요소 | 버전 | 용도 |
|---|---|---|---|
| 언어/런타임 | Go | 1.21+ | 기본 구현(agent-app-go, mcp-server-go) |
| 언어/런타임 | Kotlin | 2.1 | Spring AI 구현(적재·대용량용) |
| 언어/런타임 | Java | 17+ | Spring AI 실행 환경 |
| 언어/런타임 | Node.js | — | AIR 구현(mcp-server-air) |
| 프레임워크 | Spring Boot | 3.5 | Spring AI 구현 웹 프레임워크 |
| 프레임워크 | Spring AI | 1.0 | Ollama/MCP/pgvector 연동 |
| 프레임워크 | `@airmcp-dev/core` | ^0.3.0 | AIR MCP 서버 프레임워크 |
| MCP SDK | `github.com/modelcontextprotocol/go-sdk` | v1.7.0 | Go 구현체 MCP 클라이언트/서버 |
| DB 드라이버 | `github.com/jackc/pgx/v5` | v5.10.0 | Go의 PostgreSQL 드라이버 |
| DB 드라이버 | `github.com/pgvector/pgvector-go` | v0.4.1 | Go의 pgvector 연동 |
| DB 드라이버 | `pg` (npm) | ^8.13.0 | AIR의 PostgreSQL 드라이버 |
| 데이터베이스 | PostgreSQL | 16 | 관계형/벡터/그래프 통합 저장 |
| 데이터베이스 확장 | pgvector | — | 문서 임베딩 벡터 저장·검색 |
| 로컬 AI 런타임 | Ollama | — | 로컬 LLM 추론 서버 |
| LLM 모델 | `gemma4:e2b-it-qat` | — | 지정과제 권장 모델(Gemma 4 E2B), 기본 답변/NL2SQL 생성 |
| LLM 모델 | `gemma3:1b` | — | 초기 검증에 사용한 이전 세대 모델(현재는 비기본) |
| LLM 모델 | `qwen2.5:3b`/`qwen2.5:7b` | — | 모델 에스컬레이션 옵션(기본 비활성화) |
| 임베딩 모델 | `nomic-embed-text` | — | 문서 임베딩 |
| 웹 클라이언트 | React | 19 | 선택형 대화 UI |
| 웹 클라이언트 | TypeScript | 5.7 | 클라이언트 정적 타입 |
| 웹 클라이언트 | Vite | 6 | 클라이언트 빌드 도구 |
| 빌드/검증 | Gradle Kotlin DSL | — | Spring AI 구현 빌드 |
| 빌드/검증 | npm | — | 클라이언트/AIR 빌드 |
| 빌드/검증 | JUnit 5 | — | Kotlin 테스트 |
| 빌드/검증 | go test | — | Go 테스트 |
| 인프라 | Docker Compose | — | PostgreSQL/Ollama 로컬 실행 |
| CI | GitHub Actions | — | server-tests/client-build/eval-harness |

## 6. 테스트와 CI 체계

- **CI 대상**: `agent-app-spring`/`mcp-server-spring`(Gradle test),
  `client`(npm build), `eval/`(Python 테스트)를 GitHub Actions로 자동
  검증한다.
- **Go 구현체 검증**: `agent-app-go`/`mcp-server-go` 각각의
  `go test ./...`로 자체 검증한다(원본 Kotlin 오라클 테스트를 1:1로
  포팅한 테스트 스위트 포함).
- **AIR 검증**: `mcp-server-air`의 자체 `npm test` +
  `contract-smoke.mjs`(MCP 도구/리소스 계약 스모크 테스트)로 검증한다.
- **벤치마크 무결성**: 벤치마크를 변경하면 같은 데이터셋·모델·설정·반복
  횟수로 기준선과 후보를 모두 측정하고, 문항별 원시 JSON 결과를
  보존한다(`docs/contributing/BENCHMARK_POLICY.md`).

## 7. 개발 프로세스

- 총 44개 PR, 71개 커밋으로 진행했다. 초기 아키텍처 수립 →
  Spring AI 기반 벤치마크 최적화 → AIR/Go 대체 구현 실험 →
  Go 기본 구현 승격의 순서로 발전했다.
- 이슈·PR 템플릿은 기준 브랜치, 현재 문제 증거, 전후 검증 방법, 위험과
  되돌리기, 주·보조 평가항목을 필수 항목으로 요구한다
  (`docs/contributing/WORKFLOW.md`).
- 폴더명은 프레임워크/언어 접미사로 통일했다(`agent-app-spring`,
  `mcp-server-spring`, `agent-app-go`, `mcp-server-go`,
  `mcp-server-air`) — PR #116.
- 프로젝트명을 "Riwonace Nexus"로, 저장소를 `riwonace-nexus`로
  변경했다 — PR #114.

## 8. 알려진 한계와 향후 과제

- **대용량 데이터 미검증**: 이번 실측은 소규모 데이터셋(Company-X 시드,
  직원 45명 수준) 기준이다. 대용량 데이터에서 Go/Spring AI의 리소스·정확도
  격차가 어떻게 변하는지는 아직 실측하지 않았다.
- **AIR 스키마 계약 격차**: `get_schema` 응답의 `foreignKeys` 필드가
  누락되어 있다(이슈 #49). AIR로 실제 운영 트래픽을 받게 할 계획이라면
  이 부분부터 해소해야 한다.
- **AIR 에러 처리**: JSON 문자열을 문자 수 기준으로 truncate해 잘린
  JSON이 파싱 실패로 이어질 수 있고, 원본 DB 예외 메시지를 그대로 반환해
  내부 구조가 노출될 수 있다.
- **Go 표현 다양성 검증 범위**: Go 포팅은 baseline과 같은 302문항+홀드아웃
  검증을 통과했지만, 완전히 다른 데이터셋(별도 시드)으로는 아직 반복
  검증하지 않았다. 오타·복합 조건을 섞은 신규 100문항
  (`eval/hard-eval-100.json`)을 준비해뒀으며, 302문항 대비 일반화 여부를
  추가 검증할 예정이다.
- **GPU 추론 미검증**: 이번 latency 실측은 로컬 macOS + Docker
  Desktop(CPU 전용 Ollama 추론) 환경 기준이다. 서버급 GPU 추론
  인프라에서는 재검증이 필요하다.

## 9. 참고 문서

| 문서 | 내용 |
|---|---|
| [README.md](../README.md) | 빠른 시작, 전체 구조, 모듈 안내 |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | 전체 구조, 요청 처리 흐름, 주요 설계 결정 |
| [IMPLEMENTATIONS.md](../docs/architecture/IMPLEMENTATIONS.md) | Go/Spring AI/AIR 구현체별 배경, 실행 방법, 실측 비교 |
| [MCP_CONTRACT.md](../docs/architecture/MCP_CONTRACT.md) | MCP 통신 흐름, 도구별 입출력, 보안 계층 |
| [READER_GUIDE.md](../docs/architecture/READER_GUIDE.md) | 시스템을 처음 읽을 때의 안내 |
| [BENCHMARK.md](../BENCHMARK.md) | 벤치마크 결과 및 재현 방법 |
| [CONTEST_ALIGNMENT.md](../docs/CONTEST_ALIGNMENT.md) | 지정과제 정합성 설명 |
