# 대체 구현: AIR(Node.js)와 Go 포팅

이 프로젝트의 핵심 경계는 특정 프레임워크나 언어가 아니라
[MCP 도구 계약](./MCP_CONTRACT.md)이다. 그 계약이 실제로 프레임워크·언어에
독립적인지 검증하기 위해 두 종류의 대체 구현을 만들었다. 둘 다 **기본 실행
경로가 아니며**, `agent-app-spring`(Spring AI) → `mcp-server-spring`(Spring AI)이
여전히 기본이고 자동화 테스트·데이터 적재를 담당한다.

`agent-app`과 `mcp-server`는 독립적으로 조합할 수 있다 — 즉 이론상
agent-app-spring + mcp-server-go, agent-app-go + mcp-server-spring 같은
크로스 조합도 가능하다. 아래 실측은 각 언어 구현체를 agent-app/mcp-server
양쪽 다 같은 언어로 맞춘 조합(Spring AI 풀스택, Go 풀스택)과, agent-app은
Spring AI를 그대로 두고 mcp-server만 교체한 AIR 조합을 비교한다.

| 실험 | 무엇을 바꿨나 | 무엇을 유지했나 | 검증한 것 |
|---|---|---|---|
| AIR (`mcp-server-air`) | 프레임워크(Spring AI MCP → Node.js AIR MCP) | agent-app은 Spring AI 그대로, MCP 도구 계약 | 서버 URL만 바꿔도 `agent-app-spring` 코드 변경 없이 교체 가능한가 |
| Go (`mcp-server-go`, `agent-app-go`) | 언어·런타임 전체(Kotlin/JVM → Go) | 라우팅·NL2SQL·SQL 검증·caching 정책·MCP 도구 계약 | 다른 언어로 1:1 포팅했을 때 성능·자원 사용량이 실제로 어떻게 다른가 |

## 실측 비교 (2026-08-25, 3-way 동일 조건)

`eval/generalization-eval.json`(302문항)과 100문항 서브셋으로, 동일 모델
(`gemma3:1b` + escalation `qwen2.5:3b`/`qwen2.5:7b`), 동일 seed(42),
`ROUTER_FALLBACK=semantic-ai` 조건에서 Spring AI/Go/AIR 세 스택을 측정했다.

### 정확도 (302문항)

| 지표 | Spring AI (baseline) | Go | AIR |
|---|---:|---:|---:|
| 라우팅 정확도 | 99.3% | 98.0% | 99.3% |
| 답변 정확도 | 73.8% | 73.8% | 73.5% |
| SQL 답변 정확도 | 55.1% | 55.1% | 55.1% |
| VECTOR 답변 정확도 | 93.3% | 90.0% | 90.0% |
| GRAPH 답변 정확도 | 94.7% | 95.6% | 94.7% |

**세 구현체의 정확도는 사실상 동급이다.** 오차범위 안의 차이(±1.5%p)만
존재하고, SQL 세부 정확도는 정확히 일치한다(55.1%). baseline의 답변 정확도
버그 수정 8건(AnswerabilityGate 오판, DeterministicSqlPlanner v2 미연결,
RuleBasedRouter 키워드 누락, kg_search predicate 체이닝 등)은 Go(PR #113)와
AIR(PR #117)에 모두 반영을 완료했다 — 이전 버전 문서에 남아있던 "버그 수정이
Go에 반영 안 됨"이라는 서술은 더 이상 사실이 아니다.

### 리소스 사용량 (100문항, idle 대비 부하 시 peak RSS)

| 구현체 | agent-app RSS | mcp-server RSS | 합계 | baseline 대비 |
|---|---:|---:|---:|---:|
| Spring AI (baseline) | 179.6MB | 307.8MB | **487.4MB** | 1.0x |
| Go | 20.5MB | 22.7MB | **43.2MB** | **0.089x (11.3배 적음)** |
| AIR (agent-app-spring + mcp-server-air) | 201.8MB | 87.1MB | **293.9MB** | 0.60x (1.66배 적음) |

부하를 걸어도(idle → 100문항 처리) 세 구현체 모두 peak RSS가 idle 대비 거의
증가하지 않았다 — 이 데이터셋 규모(직원 45명 등 Company-X 시드 수준)에서는
메모리가 요청량이 아니라 런타임 자체(JVM vs Go vs Node.js)에 의해 결정된다.

### latency는 참고치로만 본다

이 로컬 macOS + Docker Desktop(CPU 전용 Ollama 추론) 환경에서는 시스템 부하에
따라 **baseline조차 1.8초~5.3초까지 3배 가까이 흔들렸다.** 최초 관찰에서
"Go가 baseline보다 3~4배 느리다"고 나온 원인은 언어 성능 차이가 아니라:

1. 네이티브 macOS `Ollama.app`과 Docker 컨테이너의 Ollama가 포트 11434를
   동시에 점유(IPv4/IPv6 각각 바인딩)하고 있어, 요청이 두 백엔드 중 예측
   불가능한 쪽으로 갔다 — Java(Spring AI)의 HTTP 클라이언트는 커넥션을
   재사용해 우연히 한산한 백엔드에 고정됐고, Go는 매 요청 새 커넥션을 맺으며
   그때그때 다른(때로는 바쁜) 백엔드로 튕겼을 가능성이 높다.
2. macOS Spotlight 인덱싱(`mds`/`mds_stores`)이 벤치마크와 무관하게 CPU를
   크게 잠식하고 있었다.

두 요인을 제거(네이티브 Ollama.app 종료, `mdutil -i off`)한 뒤 재측정하니
Spring AI(5319ms)와 Go(5477ms)의 지연시간이 3% 차이로 수렴했다 — 오차범위
안이다. 이 결론은 **로컬 환경에 한정되며, 서버급 GPU 추론 인프라에서는
latency 재검증이 필요하다.**

## AIR (`mcp-server-air`)

Node.js의 AIR MCP 프레임워크로 `mcp-server-spring`과 같은 도구 이름
(`vector_search`, `run_sql`, `kg_search`)과 `db://schema` Resource를 노출한다.

### 실행

AIR는 벡터 데이터를 스스로 적재하지 않는다 — Spring AI `mcp-server-spring`으로
먼저 적재한 뒤 전환한다.

```bash
npm ci --prefix mcp-server-air
npm --prefix mcp-server-air start

# 별도 터미널
MCP_SERVER_URL=http://localhost:8082 ./gradlew :agent-app-spring:bootRun
```

기본 Spring AI 구현으로 돌아가려면 `MCP_SERVER_URL`을 생략하거나
`http://localhost:8081`로 설정한다.

### 권장 사항

- **정확도·리소스 실측 결과, AIR는 프로덕션 후보로 고려할 만하다.** agent-app을
  Spring AI 그대로 유지하면서 mcp-server만 Node.js로 교체해 리소스를 1.66배
  줄이고, 정확도는 오히려 baseline과 동등하거나 근소하게 높다(302문항 73.5%
  vs 73.8%, 100문항 83.0% vs 82.0%).
- kg_search predicate 체이닝 격차(PR #117에서 해소)처럼, mcp-server-air는
  agent-app과 별개로 자체 로직 부채가 쌓일 수 있다는 점은 계속 주의해야
  한다 — agent-app이 같다고 mcp-server 쪽 회귀가 자동으로 없어지는 게
  아니다.
- 출력 크기 제한(`MAX_OUTPUT_CHARS`)은 현재 Spring AI와 AIR 모두 4,000자로
  동일하다(과거 "AIR는 4,000자, Spring AI는 8,000자"로 기록됐던 문서는
  최신 코드 기준 사실이 아니다). `foreignKeys` 스키마 응답 격차는 이슈 #49에
  여전히 남아 있어, 실제 운영 트래픽을 받게 할 계획이 있다면 이 부분부터
  해소해야 한다.

### 위험 / 트레이드오프

- **에러 처리**: JSON 문자열을 임의 위치에서 자를 수 있어(문자 수 기준 truncate)
  잘린 JSON이 파싱 실패로 이어질 가능성이 있고, 원본 DB 예외 메시지를 그대로
  반환해 내부 구조가 노출될 수 있다.
- **CI 미포함**: `mcp-server-air`는 루트 CI(`agent-app-spring`/
  `mcp-server-spring`/`client`/`eval`) 대상이 아니다. 자체
  `npm test`(`mcp-server-air/server.test.mjs`)로만 검증되므로, Spring AI
  쪽 회귀가 AIR에도 동일하게 적용됐는지는 수동 확인이 필요하다.

## Go 포팅 (`mcp-server-go`, `agent-app-go`)

공식 [Go MCP SDK](https://github.com/modelcontextprotocol/go-sdk)로
`mcp-server-spring`/`agent-app-spring`을 1:1 포팅했다. 라우팅·NL2SQL·SQL
검증·caching 정책·MCP 도구 계약을 임의로 개선하지 않고 동일하게 재현하는
것이 목표였다.

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
  OLLAMA_BASE_URL=http://localhost:11434 OLLAMA_MODEL=gemma3:1b SERVER_PORT=8080 \
  ROUTER_FALLBACK=semantic-ai go run .
```

기본 Spring AI 구현과 마찬가지로 `docker-compose.yml`의 `postgres`(5433)·
`ollama`(11434)에 연결한다. 벡터 데이터는 Spring AI `mcp-server-spring`으로
먼저 적재해야 한다(AIR와 동일한 제약). 세부 포팅 노트는
[`mcp-server-go/README.md`](../../mcp-server-go/README.md),
[`agent-app-go/README.md`](../../agent-app-go/README.md)를 참고한다.

### 권장 사항 — 규모 기준 선택 가이드

이번 실측(직원 45명, employees/contracts/sales 등 수십~수백 row 규모의
Company-X 시드 데이터)에서는 Go와 Spring AI의 답변 정확도가 사실상 동일했고,
Go의 리소스 사용량(43.2MB)이 Spring AI(487.4MB)의 약 **1/11**이었다.
이 규모에서는 **Go를 기본으로 권장한다.**

다만 이 실측은 **소규모 데이터셋 한정**이다. 대용량 데이터(예: 직원 수천~수만
명, 쿼리당 반환 row가 수천 건 이상, 동시 접속자가 많은 환경)에서 Spring AI가
유리한지는 아직 이 프로젝트에서 별도로 실측한 적이 없다 — JVM의 커넥션 풀
(HikariCP)·JDBC 스트리밍·GC 튜닝 옵션이 대용량 처리에서 이점을 낼 수 있다는
것은 일반론이지, 이 저장소의 실측 결과가 아니다. 규모가 커지는 배포를
계획한다면, 이 문서의 리소스 실측 방법론(`eval/resource-check-100.json` +
`ps`로 idle/peak RSS 샘플링)을 대용량 시드 데이터로 반복해서 임계값을
직접 확인하고, 그 결과로 이 권장 사항을 갱신해야 한다.

### 위험 / 트레이드오프

- **CI 미포함**: Go 모듈은 루트 Gradle CI 대상이 아니다. `agent-app-go`/
  `mcp-server-go` 각각의 `go test ./...`로만 검증된다.
- **표현 다양성 검증 부족**: Go 포팅 자체는 1:1 포팅으로 설계됐지만, baseline
  만큼 대규모(302문항+홀드아웃) 표현 다양성 검증을 별도 데이터셋으로 반복하지
  않았다.
- **대용량 데이터 미검증**: 위 권장 사항에서 설명한 대로, 이 실측은 소규모
  데이터셋 한정이다.
