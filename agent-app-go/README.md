# agent-app-go

`agent-app`(Kotlin/Spring AI, Architecture v2 경로)의 1:1 Go 포팅. 라우팅·NL2SQL·
answerability gate·evidence 최적화·recovery policy 로직을 임의로 개선하지 않고
재현한다. 상세 배경은 저장소 루트 이슈 [#100](https://github.com/qixiangme/DB-MCP/issues/100),
[#101](https://github.com/qixiangme/DB-MCP/issues/101)을 참고.

## 스코프

이 포팅은 `AgentServiceV2`(현재 production 경로, `agent.v2.enabled=true` 기본값)만
대상으로 한다. v1(`AgentService`)는 포팅하지 않는다.

## 실행

```bash
export MCP_SERVER_URL=http://localhost:8081   # mcp-server 또는 mcp-server-go
export OLLAMA_BASE_URL=http://localhost:11434
export OLLAMA_MODEL=gemma3:1b
export SERVER_PORT=8080
go run .
```

## API

- `POST /api/chat`, `POST /api/chat/v2?trace=true` — baseline `ChatController`와 동일 응답 shape
- `GET /api/tools` — MCP tool/resource 목록
- `GET /api/v2/status` — v2 기능 목록

## 테스트

```bash
go test ./...
```

`internal/router`, `internal/sql`, `internal/core`, `internal/answer`의 테스트는
원본 Kotlin 오라클 테스트(`RuleBasedRouterTest`, `DeterministicSqlPlannerTest`,
`QueryProfilerTest`, `ModelEscalatorTest`, `EvidenceOptimizerTest`,
`AnswerabilityGateTest`, `RecoveryPolicyTest`, `SchemaLinkerTest`,
`FewShotSelectorTest`, `SchemaPromptFormatterTest`, `PostgresSqlNormalizerTest`,
`StructuredEvidenceFormatterTest`, `RouteQuestionProjectorTest`)를 1:1로 옮긴 것이다.

## 포팅 시 확인한 baseline 세부사항

- Go RE2는 lookbehind를 지원하지 않아 `RuleBasedRouter`의 `PRODUCT_NUMERIC`과
  `RouteQuestionProjector`의 `BOUNDARY`(`(과|와)` 앞 lookbehind)는 별도 함수로
  동일한 의미를 재구현했다. 오라클 테스트로 baseline과 동일한 결과를 확인했다.
- `AnswerabilityGate`의 `useLlm` 설정은 baseline에서도 실제로는 읽히지 않는
  죽은 설정이다. 이 포팅에서도 동일하게 유지한다(버그가 아니라 재현 대상).
- v2의 model escalation은 tier 계산까지만 하고 baseline에서도 실제 Ollama 호출
  모델을 프로그램적으로 바꾸지 않는다(`AgentServiceV2.kt`의 기존 TODO) — 이
  포팅도 동일하게 `Escalator.SelectModel`이 고른 모델 이름으로 `Complete`를
  호출하도록 맞췄다(정적 설정이 아니라 매 호출 인자로 모델을 넘긴다는 점에서
  baseline과 완전히 동일하지는 않지만, 재에스컬레이션 시 실제로 다른 텍스트가
  나오는지는 Ollama가 요청받은 모델을 실제로 바꿔 응답하는지에 달려 있다).
- MCP 클라이언트는 공식 Go MCP SDK의 `SSEClientTransport`를 사용하며, baseline의
  `McpSessionGuard`(단일 세션 직렬화)와 `cachedSchema`(프로세스 수명 캐시)를
  각각 mutex와 캐시 필드로 재현했다.

## 검증

실제 `mcp-server-go` + Postgres + Ollama(`gemma3:1b`, `qwen2.5:3b`,
`nomic-embed-text`)에 붙여 SQL/VECTOR/GRAPH 세 라우트 모두 end-to-end로
수동 검증했다: `/api/tools`, `/api/v2/status`, 그리고 각 라우트별 `/api/chat/v2`
호출이 정상적인 tool call 순서·컨텍스트 소스·모델 에스컬레이션과 함께 응답함을
확인했다.

## 권장 사항

startup(5배)·RSS(13배) 이득은 명확하지만, **아직 baseline(Kotlin/Spring AI)의
답변 정확도 버그 수정 8건이 이 포팅에 반영되지 않았다.** 다중 인스턴스
스케일 아웃이나 콜드스타트가 실제 제약이 아니라면 baseline을 기본으로
유지하는 것을 권장한다. 자세한 실측 비교와 판단 근거는 저장소 루트
[`docs/architecture/ALTERNATIVE_IMPLEMENTATIONS.md`](../docs/architecture/ALTERNATIVE_IMPLEMENTATIONS.md)를 참고한다.

## 위험 / 알려진 한계

- **정확도 버그 미반영**: baseline에서 고친 `AnswerabilityGate` 클레임 오판,
  `DeterministicSqlPlanner` v2 미연결, 라우팅 키워드 누락 등은 이 Go 포팅에
  아직 반영되지 않았다. baseline과 같은 조건(`gemma3:4b`)에서 재측정하지
  않았으므로 지금 이 포팅으로 전환하면 baseline에서 이미 해결된 오답 패턴을
  다시 겪을 수 있다.
- **표현 다양성 검증 부족**: baseline은 302문항(문체 3~4배 변형) +
  홀드아웃 298문항(완전히 다른 데이터셋)까지 검증했지만, 이 포팅은 원본
  오라클 테스트와 수동 end-to-end 검증까지만 거쳤다.
- **CI 미포함**: 루트 Gradle CI 대상이 아니다. `go test ./...`로만 자체 검증된다.
