# 지정과제 정합성: 소형 LLM을 위한 MCP 파이프라인

## 한 문장 목표

사용자 질문의 데이터 형태와 필요한 연산을 먼저 판별하고, `vector_search`, `run_sql`,
`kg_search` 중 필요한 MCP 실행 도구만 호출한 뒤, 소형 LLM에는 답변에 필요한 근거만 제공한다.

이 프로젝트의 성능 대상은 특정 MCP 프레임워크가 아니라 다음 전체 흐름이다.

```text
질문
  → 의도 라우팅(SQL / VECTOR / GRAPH, 복합 질문은 합집합)
  → MCP 실행 도구 1~3개
  → PostgreSQL의 정형·문서·관계 데이터 조회
  → 과업별 컨텍스트 선별
  → Ollama 소형 LLM의 근거 기반 답변
```

## 공개 MCP 계약

MCP의 Tool은 실행 가능한 Action(A), Resource는 읽을 수 있는 Knowledge(K)로 구분한다.

| MCP 종류 | 이름 | 과제 역할 | 선택 조건 |
|---|---|---|---|
| Tool | `vector_search` | 문서 의미 검색 | 정책·설명·장애 문서 질문 |
| Tool | `run_sql` | NL2SQL이 만든 읽기 전용 SQL 검증·실행 | 집계·통계·목록 질문 |
| Tool | `kg_search` | 엔티티 관계와 이웃 탐색 | 사람·제품·프로젝트 관계 질문 |
| Resource | `db://schema` | 테이블·컬럼·외래키·값 힌트 | SQL 생성에만 사용 |

따라서 서버의 실행 도구는 지정과제 설명과 같은 세 개다. `db://schema`는 독립적인 사용자
작업이 아니라 SQL 생성에 필요한 지식이므로 네 번째 Tool로 세지 않는다. 에이전트의 SQL 경로는
Resource를 읽어 SELECT를 생성하고, `run_sql` Tool이 이를 제로 트러스트로 검증·실행한다.

Spring AI 서버와 AIR 서버가 같은 Tool/Resource 계약을 제공하므로 에이전트는
`MCP_SERVER_URL`만 바꿔 두 구현을 사용할 수 있다.

## MCP가 줄이는 것과 줄이지 않는 것

MCP가 청킹, 임베딩, 인덱싱을 자동으로 제거하지는 않는다. 이 저장소는 다음 방법으로 운영
복잡도를 줄인다.

- 에이전트에는 DB 드라이버, 임베딩 API, 그래프 쿼리를 두지 않고 `McpGateway` 하나만 둔다.
- 문서 적재와 임베딩은 MCP 서버 모듈의 `DataIngestor`와 `/admin/ingest-dir`가 담당한다.
- 벡터·관계형·그래프 데이터를 PostgreSQL 하나에 저장한다.
- Company-X 문서는 작은 독립 문서이므로 파일 하나를 문서 하나로 적재한다. 가변 청킹 크기와
  오버랩, 별도 리랭커 임계값은 두지 않는다.
- SQL은 MCP 서버의 `SqlGuard`와 출력 인코더를 반드시 통과한다.

즉, 실제 DB·Ollama·PostgreSQL 장애가 사라지는 것이 아니라 **에이전트가 관리하는 통합 경계가
MCP 하나로 수렴**한다. 참조 자료의 `9→2`, `3→1`, `64%`는 해당 연구 구성의 측정값이며
MCP를 사용하기만 하면 자동으로 재현되는 보장은 아니다.

## 질문 의도와 도구 선택

`RuleBasedRouter`는 소형 LLM의 function calling에 도구 선택을 맡기지 않는다.

1. 데이터 형태와 연산을 나타내는 규칙으로 SQL, VECTOR, GRAPH를 독립 판정한다.
2. 둘 이상에 해당하면 여러 도구를 선택해 병렬로 조회한다.
3. 규칙에 걸리지 않은 질문만 설정된 폴백 분류기로 보낸다.
4. 조회 결과는 `ContextCurator`가 관련도, 중복, 과업별 예산으로 선별한다.

공개 질문에 대한 라우팅 적중률만으로 일반화를 주장하지 않는다. 키워드를 제거한 공개셋과
별도 보류셋을 함께 사용하며, 세부 결과는
[키워드 없는 라우팅 실험](./research/KEYWORDLESS_ROUTING_RESULTS.md)에 보존한다.

## TACC 연구를 코드에 적용한 범위

전현우·김태성·강현(2026)의 결과를 “컨텍스트는 적을수록 좋다”로 해석하지 않는다.

- Full은 빈 컨텍스트보다 두 모델 모두 유의하게 높았다.
- 네 구성요소 중 Knowledge(K)만 유의한 주효과를 보였다.
- Qwen은 Full이 K+A보다 높았지만 Llama는 유의한 차이가 없었다.
- 과업 유형이 성능 분산의 약 56%를 설명했다.

이에 따라 이 프로젝트는 스키마와 조회 결과 같은 핵심 K를 제거하지 않는다. 대신 검색 점수가
낮거나 중복된 근거를 제외하고, SQL 단독 질문에는 더 작은 답변 컨텍스트 예산을 적용한다.
`db://schema` Resource 분리는 K와 실행 Action을 프로토콜 수준에서도 구분한 결과다.

## 재현 가능한 계약 검증

```bash
# 단위 테스트
./gradlew test
npm test --prefix mcp-server-air

# PostgreSQL·Ollama 및 두 MCP 서버가 실행 중일 때
npm run smoke --prefix mcp-server-air -- \
  http://localhost:8081/sse http://localhost:8082/sse
```

스모크 테스트는 두 서버에서 실행 도구 세 개, `db://schema` Resource, JSON 스키마 내용을
동일하게 확인한다. 최종 답변 정확도는 라우팅·검색·생성의 영향을 함께 받으므로 계약 테스트와
분리하여 `eval/run-full-eval-v2.py`로 측정한다.
