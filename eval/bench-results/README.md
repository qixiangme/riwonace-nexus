# 3-way 벤치마크: Kotlin/Spring AI baseline vs Go vs Kotlin/Ktor

이슈 [#100](https://github.com/qixiangme/DB-MCP/issues/100) (추적), [#101](https://github.com/qixiangme/DB-MCP/issues/101) (Go), [#102](https://github.com/qixiangme/DB-MCP/issues/102) (Ktor)에서 진행한 migration 실험의 벤치마크 하네스와 원시 결과.

이 디렉토리에는 두 계열의 실험이 섞여 있다.

1. **이 문서(startup/RSS/latency/안정성)**: 세 구현의 성능·자원 사용량 비교.
   `{impl}-2.json`, `{impl}-isolated.json`, `go-first.json`, `{impl}-equivalence.json`.
2. **baseline 답변 정확도 개선과 그 일반화 검증**: 30문항 오답 분석 → 93문항 →
   302문항(문체 다양화) → 홀드아웃 298문항(완전히 다른 가상 회사 데이터)까지
   네 단계로 검증한 전체 과정은 별도 문서
   [`private-eval-results.md`](./private-eval-results.md)에 있다. 여기 있는
   `baseline-*.json`, `go-4b.json`, `ktor-4b.json`, `*-generalization*.json`,
   `holdout-results.json`, `naive-llm-30-results.json` 등은 전부 그 문서가
   인용하는 원시 결과 파일이며, 생성기 스크립트(`generalization_gen.py`,
   `generalization_gen_holdout.py`, `generate_holdout_data.py`,
   `dump_full_context.py`, `naive_llm_eval.py`)도 함께 있다.

## 가설

같은 MCP 아키텍처(라우팅·NL2SQL·caching 정책 동일, 신규 기능 없음)를 Go/Ktor로
1:1 이식했을 때 startup·메모리·latency·안정성에 실제로 어떤 차이가 발생하는가.

## 도구

- `bench_harness.py` — startup time, sequential/concurrent latency(p50/p95/p99),
  RSS, 에러 처리, cancellation을 측정. `--sequential-only`로 latency만 빠르게 재현 가능.
- `equivalence_check.py` — `eval/official-eval.json`(기존 30문항, 신규 문항 생성 없음)으로
  라우팅 일치율과 답변 정확도를 비교. `eval/answer_rules.py`의 채점 로직을 그대로 재사용.

## 결과 파일

| 파일 | 내용 |
|---|---|
| `{impl}-2.json` | Ollama 재시작+워밍업 후 3구현 연속 실행 (startup/RSS/latency/concurrency/error/cancellation) |
| `{impl}-isolated.json` | 구현마다 Ollama를 완전히 재시작·재워밍업한 격리 측정 (sequential latency만) |
| `go-first.json` | 실행 순서 가설(뒤에 실행하는 쪽이 불리한가) 검증용 — Go를 맨 먼저 실행 |
| `{impl}-equivalence.json` | 공식 30문항에 대한 라우팅/답변 정확도 비교 |

`impl` ∈ `{baseline, go, ktor}`.

## 핵심 수치 (재현 확인됨, 최소 3회 독립 반복)

| 지표 | baseline | Go | Ktor |
|---|---:|---:|---:|
| mcp-server startup | 2.53s | 0.51s | 1.02s |
| agent-app startup | 2.07s | 0.52s | 1.11s |
| sequential p50 | 1.93s | 9.51s | 2.14s |
| mcp-server RSS | 312MB | 24MB | 186MB |
| 라우팅 일치율 (30문항) | 100% | 100% | 100% |
| 답변 정확도 (30문항) | 50.0% | 43.3% | 50.0% |

## Go의 latency 저하 — pprof로 원인 확정

초기 자동화 반복 실험에서 Go가 일관되게 4~5배 느린 것이 재현되어, 코드 수준 병목을
`net/http/pprof`로 확정했다.

- **CPU 프로파일**: 18초 요청 처리 동안 Go 프로세스는 CPU를 10ms(0.055%)만 사용,
  나머지는 전부 `runtime.netpoll`(소켓 응답 대기). 애플리케이션 코드에 CPU 바운드
  병목이 없음을 확인.
- **애플리케이션을 완전히 배제한 대조 실험**: Go/Ktor/baseline 코드를 전혀 거치지
  않고 동일 프롬프트로 Ollama에 5회 연속 직접 curl 요청했을 때도 토큰 생성 속도가
  16~22 tok/s로 낮고 불안정했다. 세션 전체 986개 샘플의 tokens/sec 분포는
  2.98~61.8 tok/s(20배 편차, stdev 7.82).

**결론**: Go 구현의 latency 저하는 애플리케이션 코드 문제가 아니라, 로컬
macOS + Docker Desktop VM + CPU 추론 환경에서 Ollama 자체의 처리량이 시스템
상태에 따라 근본적으로 불안정하기 때문이다. 이 결론은 이 특정 로컬 환경에
한정되며, 서버급 GPU 추론 인프라에서는 재검증이 필요하다.

## 재현 방법

```bash
# 세 구현을 각각 8080/8081에 띄운 상태에서
python3 bench_harness.py --impl <baseline|go|ktor> \
  --mcp-cmd '...' --mcp-cwd '...' \
  --agent-cmd '...' --agent-cwd '...' \
  --out result.json

python3 equivalence_check.py --impl <baseline|go|ktor> --out equiv.json
```
