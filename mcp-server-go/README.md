# mcp-server-go

`mcp-server`의 기본 구현체다. `mcp-server-spring`(Kotlin/Spring AI)과 동일한
아키텍처·라우팅·SQL 처리·caching 정책, MCP tool contract를 재현한다. 실측
비교는 [IMPLEMENTATIONS.md](../docs/architecture/IMPLEMENTATIONS.md)를
참고한다. 상세 배경은 저장소 루트의 이슈
[#100](https://github.com/qixiangme/riwonace-nexus/issues/100),
[#101](https://github.com/qixiangme/riwonace-nexus/issues/101)을 참고.

## 제공 계약

- MCP Tool 3종: `vector_search`, `run_sql`, `kg_search` (baseline과 이름/파라미터/출력 shape 동일)
- MCP Resource 1종: `db://schema` (tool이 아님 — `RetrievalToolsContractTest.kt` 기준)

## 실행

```bash
export DATABASE_URL=postgres://riwonace:riwonace@localhost:5433/riwonace
export OLLAMA_BASE_URL=http://localhost:11434
export SERVER_PORT=8081
go run ./...
```

baseline과 동일하게 `docker-compose.yml`의 `postgres`(5433)·`ollama`(11434) 서비스에
연결하도록 기본값이 설정되어 있다.

## 테스트

```bash
go test ./...
```

`internal/tools`의 테스트는 원본 Kotlin 오라클 테스트(`SqlGuardTest`,
`ToolResponseEncoderTest`, `HybridRetrievalScoreTest`)를 1:1로 옮긴 것이며,
`kg_search`처럼 원본에 전용 단위 테스트가 없는 경로는 `graph/schema.md` 예시와
`RetrievalTools.kt` 주석에서 직접 도출한 케이스로 검증한다.

## 포팅 시 확인한 baseline 세부사항

- `vector_search`는 Spring AI `PgVectorStore` 1.0.3의 실제 동작(바이트코드 확인)을
  따른다: 거리 연산자는 `<=>`(COSINE_DISTANCE), `score = 1 - distance`,
  `similarityThreshold` 기본값 0.0(사실상 all-accept), overfetch는 `topK*5`
  (최대 20)건 후 `lexicalCoverage` 내림차순 → 벡터 score 내림차순으로 재정렬한다.
- `vector_store` 테이블 스키마(`id uuid`, `content text`, `metadata json`,
  `embedding vector(n)`)는 Spring AI가 `initialize-schema: true`로 자동 생성하는
  것과 동일하다 — 이 저장소가 별도로 마이그레이션을 만들지 않는다.
- `kg_triples` 테이블은 `companyx-dataset-v1.0/sql/01-schema.sql`이 아니라
  `db/init.sql`이 정의하며, 실 데이터는 `companyx-dataset-v1.0/graph/load-triples.sql`로
  적재된다.

## 권장 사항

`kg_search` predicate 체이닝, `Product-`/`Client-` 패턴 인식 등 baseline의
버그 수정을 모두 반영했다. Gemma 4 E2B 302문항 재검증에서 라우팅 100.0%,
답변 정확도 100.0%(오류 0건)로 `mcp-server-spring`과 소수점까지 동일하며
리소스는 약 11분의 1이라 **기본 구현으로 사용한다.** 자세한 실측 비교는 저장소 루트
[`docs/architecture/IMPLEMENTATIONS.md`](../docs/architecture/IMPLEMENTATIONS.md)를 참고한다.

## 위험 / 알려진 한계

- **표현 다양성 검증 부족**: baseline은 302문항 + 홀드아웃 298문항(완전히
  다른 데이터셋)까지 검증했지만, 이 포팅은 302문항 재검증까지만 거쳤다.
- **대용량 데이터 미검증**: 직원 45명 수준의 소규모 데이터셋 기준 실측이다.
- **CI 미포함**: 루트 Gradle CI 대상이 아니다. `go test ./...`로만 자체 검증된다.
