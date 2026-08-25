# mcp-server-air

`mcp-server-spring`과 짝을 이루는 중간 성능대 구현체다. agent-app은 Spring AI를
그대로 두고 mcp-server만 [AIR](https://github.com/airmcp-dev/air) MCP
프레임워크(Node.js)로 교체해, MCP 도구 계약이 실제로 프레임워크에 독립적인지
검증한다. 리소스 사용량은 Spring AI 대비 1.66배 적고, 정확도는 동등하거나
근소하게 높다. 배경과 다른 구현체(기본 구현인 Go)와의 비교는 저장소 루트의
[`docs/architecture/IMPLEMENTATIONS.md`](../docs/architecture/IMPLEMENTATIONS.md)를
참고한다.

## 제공 계약

- MCP Tool 3종: `vector_search`, `run_sql`, `kg_search` (`mcp-server`와 이름·파라미터 동일)
- MCP Resource 1종: `db://schema`

## 실행

AIR는 벡터 데이터를 스스로 적재하지 않는다. Spring AI `mcp-server`로 먼저
적재한 뒤 전환한다.

```bash
npm ci
npm start        # :8082에서 기동

# 별도 터미널에서 agent-app을 AIR로 향하게 전환
MCP_SERVER_URL=http://localhost:8082 ./gradlew :agent-app-spring:bootRun
```

## 테스트

```bash
npm test    # server.test.mjs — 도구 3종 + 리소스 1종 노출 검증
npm run smoke  # contract-smoke.mjs — 실행 중인 서버에 대한 계약 스모크 테스트
```

## 권장 사항

- 프로덕션 전환용이 아니라 "MCP 계약이 프레임워크에 종속되지 않는다"를 보여주는
  참고/회귀 검증 구현으로 유지하는 것을 권장한다.
- 실제로 AIR로 전환할 계획이 있다면, 아래 위험 항목(특히 스키마 응답 격차)부터
  Spring AI와 맞춘 뒤 트래픽을 받게 해야 한다.

## 위험 / 알려진 한계

- **스키마 응답 격차**: `get_schema` 응답이 `foreignKeys`를 포함하지 않는다.
  Spring AI는 포함한다. NL2SQL이 조인 관계를 스키마 힌트에서 찾지 못하면
  잘못된 JOIN을 만들 수 있다.
- **출력 크기 제한 불일치**: 모든 도구 출력을 4,000자로 자른다. Spring AI는
  도구별로 4KB/8KB를 다르게 적용한다.
- **에러 처리**: 출력이 문자 수 기준으로 잘려(byte-safe truncate가 아님) JSON
  파싱이 실패할 수 있고, DB 예외 메시지를 가공 없이 그대로 반환한다.
- **CI 미포함**: 루트 CI(`agent-app`/`mcp-server`/`client`/`eval`) 대상이 아니다.
  `npm test`/`npm run smoke`로만 검증되므로, Spring AI 쪽에서 회귀가 생겨도
  AIR 쪽에 자동으로 반영되지 않는다.

상세 배경은 GitHub 이슈 [#49](https://github.com/qixiangme/riwonace-nexus/issues/49)를 참고한다.
