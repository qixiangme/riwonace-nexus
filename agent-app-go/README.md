# agent-app-go

`agent-app`의 기본 구현체다. `agent-app-spring`(Kotlin/Spring AI, Architecture
v2 경로)과 동일한 라우팅·NL2SQL·answerability gate·evidence 최적화·recovery
policy 로직을 재현한다. 302문항 동일 조건 실측에서 답변 정확도 73.8%로
Spring AI와 동급이며, 리소스 사용량은 11분의 1 수준이다. 실측 비교는
[IMPLEMENTATIONS.md](../docs/architecture/IMPLEMENTATIONS.md)를 참고한다.
상세 배경은 저장소 루트 이슈 [#100](https://github.com/qixiangme/riwonace-nexus/issues/100),
[#101](https://github.com/qixiangme/riwonace-nexus/issues/101)을 참고.

## 스코프

이 포팅은 `AgentServiceV2`(현재 production 경로, `agent.v2.enabled=true` 기본값)만
대상으로 한다. v1(`AgentService`)는 포팅하지 않는다.

## 실행

```bash
export MCP_SERVER_URL=http://localhost:8081   # mcp-server 또는 mcp-server-go
export OLLAMA_BASE_URL=http://localhost:11434
export OLLAMA_MODEL=gemma4:e2b-it-qat
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

실제 `mcp-server-go` + Postgres + Ollama(`gemma4:e2b-it-qat`,
`nomic-embed-text`)에 붙여 302문항(`eval/generalization-eval.json`)을
end-to-end로 재검증했다: 라우팅 정확도 100.0%, 답변 정확도 93.0%로
`agent-app-spring`과 소수점까지 동일하다. 세부 수치는
[IMPLEMENTATIONS.md](../docs/architecture/IMPLEMENTATIONS.md)를 참고한다.

## 권장 사항

정확도는 Spring AI와 동급이면서 리소스는 약 11분의 1이라 **기본 구현으로
사용한다.** startup 5배, RSS 13배 이득이 다중 인스턴스 스케일 아웃이나
콜드스타트가 중요한 환경에서 실질적이다. 대용량 데이터 환경에서의 재검증은
아직 하지 않았다 — 자세한 판단 근거는 저장소 루트
[`docs/architecture/IMPLEMENTATIONS.md`](../docs/architecture/IMPLEMENTATIONS.md)를 참고한다.

## 위험 / 알려진 한계

- **표현 다양성 검증 부족**: baseline은 302문항(문체 3~4배 변형) +
  홀드아웃 298문항(완전히 다른 데이터셋)까지 검증했지만, 이 포팅은 302문항
  재검증까지만 거쳤고 홀드아웃 298문항으로는 아직 반복 검증하지 않았다.
- **대용량 데이터 미검증**: 직원 45명 수준의 소규모 데이터셋 기준 실측이다.
- **CI 미포함**: 루트 Gradle CI 대상이 아니다. `go test ./...`로만 자체 검증된다.
