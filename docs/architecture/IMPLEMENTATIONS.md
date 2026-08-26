# 구현체 비교: Spring AI, Go, AIR

이 프로젝트의 핵심 경계는 특정 프레임워크나 언어가 아니라
[MCP 도구 계약](./MCP_CONTRACT.md)이다. 같은 계약을 세 가지 구현체
(Spring AI, Go, AIR)로 만들어 정확도와 자원 사용량을 실측했다.

`agent-app`과 `mcp-server`는 독립적으로 조합할 수 있다. 아래 실측은 각
언어 구현체를 agent-app/mcp-server 양쪽 다 같은 언어로 맞춘 조합
(Spring AI 풀스택, Go 풀스택)과, agent-app은 Spring AI를 그대로 두고
mcp-server만 Node.js로 교체한 AIR 조합을 비교한다.

| 구현체 | 구성 | 무엇을 검증하는가 |
|---|---|---|
| **Go** (`mcp-server-go`, `agent-app-go`) | 언어·런타임 전체(Go) | 소규모 배포에 최적화된 기본 구현 |
| Spring AI (`mcp-server-spring`, `agent-app-spring`) | Kotlin/Spring Boot 풀스택 | 대용량 데이터·복잡한 트랜잭션에 강한 참조 구현 |
| AIR (`mcp-server-air`) | agent-app-spring + Node.js MCP 서버 | mcp-server만 교체해도 agent-app이 그대로 동작하는지 |

## 실측 결과 (2026-08-26, Gemma 4 E2B 기준 3-way 동일 조건, 최종)

지정과제 공지가 권장하는 모델은 **Gemma 4 E2B**(Ollama 태그
`gemma4:e2b-it-qat`)다. `eval/generalization-eval.json`(302문항)으로,
동일 모델(`gemma4:e2b-it-qat`, 모델 에스컬레이션 비활성화), 동일 seed(42),
`ROUTER_FALLBACK=semantic-ai` 조건에서 세 구현체를 측정했다.

### 정확도 (302문항, Gemma 4 E2B)

| 지표 | Go | Spring AI | AIR |
|---|---:|---:|---:|
| 라우팅 정확도 | 100.0% | 100.0% | 100.0% |
| 답변 정확도 | 100.0% | 100.0% | 100.0% |
| SQL 답변 정확도 | 100.0% | 100.0% | 100.0% |
| VECTOR 답변 정확도 | 100.0% | 100.0% | 100.0% |
| GRAPH 답변 정확도 | 100.0% | 100.0% | 100.0% |
| 평균 지연 | 11,422ms | 11,283ms | 10,792ms |
| 오류 | 0/302 | 0/302 | 0/302 |

세 구현체 모두 302문항 전 항목(SQL/VECTOR/GRAPH)에서 라우팅·답변 정확도
100%를 달성했다. `gemma3:1b` + 에스컬레이션 조합(답변 정확도 73.5~73.8%)
대비 Gemma 4 E2B 단일 모델은 **26%p 이상 개선**됐다 — 이전 실패의 상당수가
라우팅·아키텍처 문제가 아니라 **모델 자체의 이해력 부족**과 채점/NL2SQL의
결정적 버그였다는 뜻이다. 100%에 도달하기까지 수정한 두 축은 다음과 같다:

- **VECTOR 76.7%→100%**: 채점 스크립트(`eval/run-full-eval-v2.py`)가
  "7,917만원"(모델 답변)과 "7917만원"(정답 키워드)을 쉼표 차이로 불일치
  처리하던 버그. 답변은 처음부터 정확했다 — 답변/키워드 양쪽에서 쉼표를
  제거해 비교하도록 수정.
- **SQL 90.5~91.1%→100%**: "OO 업종 고객사의 계약 건수는?" 유형 질문에서
  LLM이 `clients` 테이블만 세고(`contracts` JOIN 누락) 고객사 수를
  계약 건수로 착각하던 문제. `DeterministicSqlPlanner`에
  `isIndustryContractCount` 결정적 패턴을 추가해 LLM 생성 이전에
  올바른 JOIN 쿼리로 우선 처리하도록 해결(대소문자 정규식 버그 포함
  2차 수정, "IT/SW" 등 영문 업종명 매칭 실패 건).

세 구현체 모두 동일한 두 수정을 적용했고, 결과가 소수점까지 일치한다.

Gemma 4 E2B는 reasoning 모델이라 답변 전에 `thinking` 토큰을 먼저
소비한다. `max-tokens`를 512에서 1024로 올려야 thinking 이후 실제 답변까지
도달하며(`OLLAMA_MAX_TOKENS` 환경변수로 조정 가능), 이로 인해 평균
지연이 이전(1.8~5.3초)보다 길어졌다(11초 내외) — 다만 이는 세 구현체
모두 동일하게 적용되는 조건이라 상대 비교에는 영향이 없다.

### 리소스 사용량 (100문항, idle 대비 부하 시 peak RSS, gemma3:1b 세대 실측)

| 구현체 | agent-app RSS | mcp-server RSS | 합계 |
|---|---:|---:|---:|
| **Go** | 20.5MB | 22.7MB | **43.2MB** |
| AIR (agent-app-spring + mcp-server-air) | 201.8MB | 87.1MB | 293.9MB |
| Spring AI | 179.6MB | 307.8MB | 487.4MB |

Go는 Spring AI 대비 리소스를 **약 11분의 1**로 줄인다. 부하를 걸어도
(idle → 100문항 처리) 세 구현체 모두 peak RSS가 idle 대비 거의 증가하지
않는다 — 이 규모(직원 45명 등 Company-X 시드 데이터 수준)에서는 메모리가
요청량이 아니라 런타임 자체(Go vs JVM vs Node.js)로 결정된다. 이 리소스
실측은 `gemma3:1b` 기준이며, `gemma4:e2b-it-qat`(모델 자체가 더 큼, 약
2.3B effective)로 재측정하면 모델 로드 메모리가 늘어날 수 있으나, 세
구현체 간 상대적 격차(약 11배)는 유지될 것으로 예상한다 — 재측정은
아직 하지 않았다.

### latency는 참고치다

이 로컬 macOS + Docker Desktop(CPU 전용 Ollama 추론) 환경에서는 시스템
부하에 따라 같은 구현체의 latency가 크게 흔들릴 수 있다. 네이티브
Ollama.app과 Docker 컨테이너의 Ollama가 포트를 동시에 점유하거나(IPv4/IPv6
각각 바인딩), macOS Spotlight 인덱싱이 백그라운드에서 CPU를 잠식하는 경우
이 변동폭이 특히 커진다. 이 요인들을 제거한 뒤에도 Gemma 4 E2B의 reasoning
토큰 소비 때문에 지연 자체는 gemma3:1b보다 늘어나지만, 세 구현체 사이의
차이는 여전히 유의미하지 않다(10.8~11.4초, 4% 이내). 이 결론은 로컬 환경
기준이며, 서버급 GPU 추론 인프라에서는 재검증이 필요하다.

### 재현 시 주의: `ROUTER_FALLBACK=semantic-ai` 누락

세 구현체 모두 이 환경변수 없이 실행하면 규칙 기반 라우터가 "인원수",
"책임자"처럼 키워드 목록에 없는 표현을 VECTOR로 잘못 분류해 GRAPH 라우팅
정확도가 42%까지 떨어진다. 이는 구현체 간 차이가 아니라 규칙 기반 라우터의
공통 설계(사전 정의 키워드 미매칭 시 fallback 필요)이므로, 세 구현체
모두 **반드시 `ROUTER_FALLBACK=semantic-ai`를 설정한 상태로 실행·비교해야
한다.**

## 기본 구현: Go

**Go(`mcp-server-go`, `agent-app-go`)를 기본 구현으로 사용한다.** 정확도는
Spring AI와 동급이면서 리소스는 11분의 1 수준이라, 다중 인스턴스 배포나
서버리스 콜드스타트가 중요한 환경에서 실질적 이득이 있다.

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
  OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=gemma4:e2b-it-qat SERVER_PORT=8080 \
  ROUTER_FALLBACK=semantic-ai go run .
```

`docker-compose.yml`의 `postgres`(5433)·`ollama`(11434)에 연결한다. 벡터
데이터는 Spring AI `mcp-server-spring`으로 먼저 적재해야 한다. 세부 포팅
노트는 [`mcp-server-go/README.md`](../../mcp-server-go/README.md),
[`agent-app-go/README.md`](../../agent-app-go/README.md)를 참고한다.

### 규모 기준: 언제 Spring AI로 전환하는가

이번 실측은 **소규모 데이터셋**(직원 45명, employees/contracts/sales 등
수십~수백 row 규모) 기준이다. 다음 조건에 해당하면 Spring AI 전환을
검토한다:

- 직원·고객 등 핵심 엔티티 규모가 수천~수만 건 이상으로 커지는 경우
- 쿼리 하나가 반환하는 row가 수천 건 이상으로 늘어나는 경우
- 동시 접속자·동시 요청이 많아 커넥션 풀 관리가 중요해지는 경우

이 조건에서 Spring AI(JVM의 HikariCP 커넥션 풀, JDBC 스트리밍, GC 튜닝
옵션)가 유리할 수 있다는 것은 일반적으로 알려진 특성이며, 이 저장소에서
대용량 데이터로 직접 재현 측정한 결과는 아직 없다. 규모가 커지는 배포를
계획한다면, 이 문서의 리소스 실측 방법론(`eval/resource-check-100.json` +
`ps`로 idle/peak RSS 샘플링)을 대용량 시드 데이터로 반복해 정확한 전환
임계값을 확인한다.

### 위험 / 트레이드오프

- **CI 미포함**: Go 모듈은 루트 Gradle CI 대상이 아니다. `agent-app-go`/
  `mcp-server-go` 각각의 `go test ./...`로 검증한다.
- **표현 다양성 검증 범위**: Go 포팅은 baseline과 같은 302문항+홀드아웃
  검증을 통과했지만, 완전히 다른 데이터셋(별도 시드)으로는 아직 반복
  검증하지 않았다.
- **대용량 데이터 미검증**: 위 규모 기준에서 설명한 대로, 대용량 데이터에서의
  리소스·정확도는 이 저장소에서 실측한 적이 없다.

## Spring AI (`agent-app-spring`, `mcp-server-spring`)

Kotlin/Spring Boot 기반 참조 구현체다. 라우팅·NL2SQL·SQL 검증·caching
정책의 원본이며, 자동화 테스트와 벡터 데이터 적재를 담당한다.

### 실행

```bash
./gradlew :mcp-server-spring:bootRun
# 별도 터미널
./gradlew :agent-app-spring:bootRun
```

### 언제 선택하는가

- 대용량 데이터·복잡한 트랜잭션 처리가 필요한 배포
- 벡터 데이터 최초 적재(모든 구현체가 공유하는 적재 경로)
- Spring 생태계(Spring Security, Spring Cloud 등)와의 통합이 필요한 경우

## AIR (`mcp-server-air`)

Node.js의 AIR MCP 프레임워크로 `mcp-server-spring`과 같은 도구 이름
(`vector_search`, `run_sql`, `kg_search`)과 `db://schema` Resource를
노출한다. agent-app은 Spring AI를 그대로 사용하고 mcp-server만 교체한다.

### 실행

AIR는 벡터 데이터를 스스로 적재하지 않는다 — Spring AI `mcp-server-spring`으로
먼저 적재한 뒤 전환한다.

```bash
npm ci --prefix mcp-server-air
npm --prefix mcp-server-air start

# 별도 터미널
MCP_SERVER_URL=http://localhost:8082 ./gradlew :agent-app-spring:bootRun
```

기본 Go 구현으로 돌아가려면 `agent-app-go`를 실행하고 `MCP_SERVER_URL`을
`mcp-server-go`의 주소(기본 `http://localhost:8081`)로 설정한다.

### 언제 선택하는가

- agent-app(오케스트레이션)은 Spring AI를 유지하면서 mcp-server만 가벼운
  런타임으로 교체하고 싶은 경우 — 리소스를 Spring AI 대비 1.66배 절감하면서
  정확도는 동등하거나 근소하게 높다.
- MCP 계약이 프레임워크에 독립적임을 실증하는 참고 구현으로 유지하는 경우.

### 위험 / 트레이드오프

- **자체 로직 부채**: mcp-server-air는 agent-app과 별개로 관리되므로,
  agent-app의 로직 개선이 자동으로 반영되지 않는다. kg_search predicate
  체이닝처럼 mcp-server 자체의 로직 갱신을 별도로 챙겨야 한다.
- **에러 처리**: JSON 문자열을 문자 수 기준으로 truncate하므로 잘린 JSON이
  파싱 실패로 이어질 수 있고, 원본 DB 예외 메시지를 그대로 반환해 내부
  구조가 노출될 수 있다.
- **CI 미포함**: `mcp-server-air`는 루트 CI 대상이 아니다. 자체
  `npm test`(`mcp-server-air/server.test.mjs`)로 검증한다.
- **스키마 계약 격차**: `get_schema` 응답의 `foreignKeys` 필드가 누락되어
  있다(이슈 #49). 실제 운영 트래픽을 받게 할 계획이 있다면 이 부분부터
  해소한다.
