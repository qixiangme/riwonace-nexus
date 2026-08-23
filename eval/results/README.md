# 실험 원시 결과 아카이브

이 디렉토리는 개별 실험의 원시 채점 결과(JSON)를 보존한다. 평균만 남기지 않고
문항별 결과와 실패 분류까지 보존하는 것은 [벤치마크 무결성
정책](../../docs/contributing/BENCHMARK_POLICY.md)에 따른 것이며, 목표를
충족하지 못한 후보의 결과도 삭제하지 않는다.

파일이 많아 보이지만 대부분 아래 세 문서 중 하나가 그 실험의 배경과 결론을
설명하며, 파일 경로도 함께 인용한다 — 개별 JSON을 먼저 열기보다 관련 문서부터
읽는 것을 권장한다.

| 문서 | 다루는 실험 |
|---|---|
| [`docs/research/KEYWORDLESS_ROUTING_RESULTS.md`](../../docs/research/KEYWORDLESS_ROUTING_RESULTS.md) | 키워드 없는 질문의 라우팅 정확도(`keyword-gap-*.json`, `full-*-semantic-gemma4b-*.json`) |
| [`docs/research/NL2SQL_SCHEMA_GROUNDING_RESULTS.md`](../../docs/research/NL2SQL_SCHEMA_GROUNDING_RESULTS.md) | 스키마 링킹 전후 NL2SQL 정확도(`schema-grounding-*.json`) |
| [`docs/research/CONTEST_FINAL_BENCHMARK.md`](../../docs/research/CONTEST_FINAL_BENCHMARK.md) | 복합 질문·TACC·AIR/Spring AI·장애 주입 최종 판정(`final-*`, `final-*-<commit>/`) |
