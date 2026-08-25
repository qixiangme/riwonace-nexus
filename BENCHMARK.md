# 벤치마크 보고서 — 수치로 증명하기

> 최신 판정은 [제출 후보 벤치마크](./docs/research/CONTEST_FINAL_BENCHMARK.md)를 기준으로
> 봅니다. 아래의 초기 실험은 설계가 바뀌기 전의 역사적 기준선이며 현재 성능으로 읽지
> 않습니다.

## 최신 재현 결과 요약

| 평가 | 기준선 | 현재 후보 | 판정 |
|---|---:|---:|---|
| 공식 30문항 답변 정확도 | vanilla 50.0% | Spring AI 60.0% | +10.0%p |
| 공식 라우팅 적중률 | 86.7% | 100.0% | +13.3%p |
| 복합 30문항 답변 정확도 | vanilla 30.0% | Spring AI 93.3% | +63.3%p |
| TACC COVERAGE | - | 정확도 5%p 회귀 | 기본값 미채택 |
| 장애 주입 | - | 5/5 | 부분 실패 격리 |

공식 평가는 `gemma3:1b`, `nomic-embed-text`, temperature 0, seed 20260813, 3회 반복으로
실행했습니다. 공식 원시 결과는
[`eval/results/final-official-8daba43/ours_current.json`](./eval/results/final-official-8daba43/ours_current.json)에,
전체 판정과 AIR 비교는 [최종 벤치마크 문서](./docs/research/CONTEST_FINAL_BENCHMARK.md)에
있습니다.

> 최신 제출 후보의 3회 반복 복합 질문·TACC·AIR/Spring·장애 주입 판정은
> [제출 후보 벤치마크 판정](./docs/research/CONTEST_FINAL_BENCHMARK.md)을 먼저 보세요.
> 과거 실험은 가설과 실패 이력을 보존하기 위해 아래에 유지합니다.

> 측정 환경: Windows 11 노트북(GPU 없음, CPU 추론) · Ollama gemma3:1b(815MB, temperature 0)
> · nomic-embed-text · PostgreSQL 16 + pgvector(도커) · 채점 = 문항별 `keywords` 또는
> `answerRule(anyOf/allOf/minMatches)` 자동 판정
> 하네스: `eval/run-eval.ps1` (질문 → /api/chat → 답변 정확도·라우팅 적중률·지연시간 기록)

## 1. 1차: 시드 데이터 12문항 — 개선 전/후

| 지표 | 개선 전(baseline) | 개선 v1 | 개선 v2 |
|---|---|---|---|
| 답변 정확도 | **66.7%** | 33.3% ⚠️회귀 | 62.5% |
| 라우팅 적중률 | 100% | 100% | 100% |
| 평균 지연 | 17.9초 | 21.7초 | 21.0초 |

측정 과정에서 잡은 실전 교훈 3가지 (수치가 없었으면 몰랐을 것들):

1. **채점기 인코딩 버그** — PowerShell 5.1이 charset 없는 JSON 응답을 Latin-1로 디코딩해
   한글 키워드가 전부 미채점(최초 측정 41.7%는 과소치). 바이트를 UTF-8로 직접 디코딩해 해결.
2. **v1 회귀의 원인 2가지** — ① get_schema에 kg_triples가 노출되자 1B가 테이블을 혼합한
   SQL(`SELECT avg(salary) FROM kg_triples...`)을 생성. NL2SQL 스키마에서 비대상 테이블 제외로 해결.
   ② 컨텍스트 배치를 "최상위=맨 뒤"(recency 가설)로 뒤집었더니 gemma3:1b는 오히려 첫 문서를
   우선 사용해 VECTOR 정확도 급락. **A/B 실측으로 기각**하고 원래 배치(최상위=맨 앞) 유지.
3. **정답을 주고도 거부하는 1B** — `{"rows":[{"count":8}]}` JSON을 답으로 인식 못 함.
   SQL 결과를 "컬럼=값" 평문으로 변환해 개선.

## 2. 2차: 공식 데이터셋(Company-X) 30문항 — Spring AI 서버 vs air 서버

> 역사적 결과: 이 측정은 스키마가 `get_schema` Tool이던 시점의 1회 실행이다. 현재 계약은
> 실행 Tool 3개와 `db://schema` Resource로 바뀌었으며, AIR의 하이브리드 검색과 Spring의
> 순수 벡터 검색도 동일 알고리즘이 아니다. 따라서 아래 지연 차이를 프레임워크 단독 효과나
> 현재 기본 구현 선택의 근거로 사용하지 않는다.

동일한 에이전트(Kotlin/Spring AI), 동일한 질문 30개(questions.json), 동일한 모델(gemma3:1b).
MCP 서버만 교체: Spring AI MCP Server(Kotlin, :8081) ↔ **air 프레임워크(TypeScript, :8082)**.
전환 비용 = 환경변수 1개(`MCP_SERVER_URL`), 에이전트 코드 수정 **0줄**.

| 지표 | Spring AI 서버 | air 서버 |
|---|---|---|
| 전체 정확도 | 26.7% (8/30) | **26.7% (8/30) — 문항별 O/X 완전 동일** |
| 라우팅 적중률 | 100% | 100% |
| NL2SQL (10) | 1/10 | 1/10 |
| 벡터 검색 (10) | 2/10 | 2/10 |
| 지식 그래프 (10) | 5/10 | 5/10 |
| 평균 지연 | 48.8초 | **38.7초 (-21%)** |
| 중앙값 지연 | 33.5초 | **27.9초 (-17%)** |

### 역사적 판정: 초기 air/Spring AI 비교

- **정확도: 무승부(완전 동일).** 두 서버가 같은 도구 계약(이름·설명·입출력)을 지키면
  답변 품질은 서버 구현체와 무관하다 — **MCP 표준이 구현체를 완전히 추상화한다는 실증**.
  30문항의 O/X가 하나도 다르지 않았다.
- **초기 측정에서는 air 우세 경향** (평균 -21%). 단 CPU LLM 생성 시간의 편차가 커서
  단정에는 반복 측정이 필요. 도구 호출 자체는 양쪽 모두 지연의 소수 비중.
- **개발 경험: air 우세.** 서버 전체가 파일 1개(~200줄, 플러그인 2줄 포함)로,
  Kotlin 모듈(빌드+기동 ~20초) 대비 기동 1초 미만. timeout·cache가 플러그인 한 줄.
- 결론: 이 1회 결과만으로 기본 서버를 변경하지 않았다. 최신 동일 조건 평가에서도
  답변 정확도는 Spring AI와 AIR 사이에 차이가 없었고 지연 우위는 일관되게 재현되지 않았다.
  따라서 Spring AI를 기본 실행으로 유지하고 AIR은 동일 계약의 비교 구현으로 둔다.

## 3. 실패 분석 (공식 30문항 기준, 정직하게)

| 실패 유형 | 문항 | 원인 | 대응 |
|---|---|---|---|
| NL2SQL JOIN·GROUP BY | N1~N9 대부분 | 1B의 컬럼 환각(`registered_at`↔`created_at`, 존재하지 않는 `contracts.is_active`) | 자가수정 재시도가 일부 회복(N8은 재시도에서 완벽한 JOIN 생성 확인). 근본 대응은 모델 사다리(4B~7B) |
| 벡터 리콜 실패 | V1·V6·V9 등 | nomic-embed의 한국어 의미 검색이 정답 문서를 top-4에 못 올림 | **하이브리드 검색(키워드+RRF)을 air 서버에 구현** — V6 기준 정답 문서(DOC-013·018)가 top-4 진입 확인 |
| 컨텍스트 내 정답 미인식 | V6(검색 수정 후에도) | 1B가 4개 문서 속 정답 단락을 읽어내지 못함 | 생성 단계 한계 = 모델 크기 영역. 권장 사양 하한(gemma3:4b)에서 재측정 예정 |
| 그래프 2홉 노이즈 | G5·G6·G10 | 2홉 확장으로 40개 관계가 들어가면 1B가 집계 질문("가장 많은")에서 길을 잃음 | 집계형 그래프 질문은 SQL 병렬 라우트가 이미 커버(support_tickets) — 라우터가 SQL+GRAPH 병렬 호출 중 |
| **환각 저항(통과)** | **G4 서울물산** | 데이터셋에 존재하지 않는 개체를 묻는 질문 | **"제공된 데이터에서 찾을 수 없습니다" 정답 — 지어내지 않음. 양쪽 서버 모두 통과** ✅ |

## 4. 이 수치의 의미 (심사 관점)

1. **라우팅 적중률 100% (30/30, 양쪽 서버)** — "도구 선택을 LLM에 맡기지 않는다"는
   설계가 1B에서도 완벽 동작. 데이터셋의 도구 라벨과 규칙 라우터가 전부 일치.
2. **1B 모델은 권장 사양(4B~7B) 미만**임에도 그래프 50%, 환각 저항 통과.
   같은 시스템에서 `OLLAMA_MODEL`만 바꾸면 재측정 가능 — 정확도 낙폭의 원인이
   구조가 아니라 모델임을 분리 증명하는 장치.
3. **측정이 개선을 만든다** — 인코딩 버그, 회귀 2건, 리콜 실패를 모두 벤치마크가 잡았다.
   이 보고서 자체가 "튜닝 파라미터 없이 구조로 안정성 확보"의 증거 문서.

## 5. 키워드 미매칭 라우팅 갭 — 폴백 전략 비교

`RuleBasedRouter`는 키워드가 하나도 안 걸리면 무조건 VECTOR로 떨어진다. 공식 30문항의
라우팅 적중률이 100%인 건 구조가 완벽해서가 아니라 질문 표현이 키워드 목록과 우연히
겹쳐서일 수 있다는 의심을 실측으로 검증했다 ([#1](https://github.com/qixiangme/riwonace-nexus/issues/1)).

**방법**: 공식 30문항을 sqlKeywords/graphKeywords/vectorKeywords에 있는 단어를 하나도
안 쓰고 같은 사실을 묻도록 다시 쓴 `eval/keyword-gap-eval.json`을 만들고, 키워드
미매칭 시에만 개입하는 폴백 2종([#2](https://github.com/qixiangme/riwonace-nexus/pull/2))을
이 데이터셋과 기존 공식 30문항 양쪽에 실측했다.

| 폴백 전략 | MCP 서버 | 라우팅 적중률 | SQL | VECTOR | GRAPH | 답변 정확도 | 평균 지연 |
|---|---|---|---|---|---|---|---|
| (없음, 기존) | — | 33.3% (10/30) | 0/10 | 10/10\* | 0/10 | — | — |
| 임베딩 유사도 | air | 43.3% (13/30) | 1/10 | 7/10 | 5/10 | 13.3% | 11,945ms |
| 임베딩 유사도 | Spring AI | 43.3% (13/30) | 1/10 | 7/10 | 5/10 | 20.0% | 10,899ms |
| AI 분류 노드 | air | **60.0% (18/30)** | 4/10 | 5/10 | 9/10 | 20.0% | 13,534ms |
| AI 분류 노드 | Spring AI | **60.0% (18/30)** | 4/10 | 5/10 | 9/10 | 23.3% | 13,994ms |

\* 전부 VECTOR로 기본 라우팅되므로 VECTOR가 정답인 문항만 우연히 맞은 것 — 구조적 강점이 아니다.

### 판정

- **두 폴백 다 기존(33.3%)보다 낫지만, AI 분류 노드가 임베딩 유사도보다 확실히 우세하다
  (60.0% vs 43.3%).** "소형 LLM은 판단을 못 믿는다"는 전제로 임베딩을 먼저 의심했지만,
  **function-calling(도구 인자 생성)과 3지선다 분류는 난이도가 다르다** — 1B가 후자는
  꽤 잘 해낸다는 게 이번 실측의 핵심 발견이다.
- **AI 분류 노드는 GRAPH로 쏠리는 편향이 있다** (SQL·VECTOR 오답의 대부분이 GRAPH로 감,
  GRAPH 자체는 9/10). 소수 예시(few-shot 3개)의 균형 문제로 보이며, 예시를 늘리면
  개선 여지가 있다 — 다음 라운드 과제로 남긴다.
- **라우팅 결과는 air/Spring AI 서버와 무관하게 완전히 동일하다** (두 폴백 모두 두 서버에서
  라우팅 적중률이 한 자리도 다르지 않음). 라우팅은 MCP 도구 호출 이전에 agent-app 내부에서
  끝나므로 당연한 결과지만, 실측으로 한 번 더 확인했다. 답변 정확도만 서버별로 소폭
  다른데(예: 임베딩×air 13.3% vs 임베딩×Spring AI 20.0%), 이는 두 서버의 벡터 검색
  구현 차이(air는 하이브리드 RRF, Spring AI는 순수 유사도) 때문으로 보인다.
- **회귀 없음**: 기존 공식 30문항에 AI 분류 노드를 활성화한 채로 재실행해도 라우팅
  적중률은 그대로 100%다 (30문항 모두 키워드가 걸려 폴백 자체가 호출되지 않기 때문).
  답변 정확도는 23.3%(7/30)로 원래 26.7%(8/30)와 1문항 차이 — CPU 추론의 정상적인
  변동 범위 안이다.
- **비용은 있다**: AI 분류 노드는 폴백이 걸릴 때마다 LLM 호출을 하나 더 하므로 임베딩
  방식보다 지연이 약 2초 더 든다. "장애 지점·튜닝 파라미터 최소화"라는 원칙 위에
  올린 게 아니라 **키워드 미매칭이라는 좁고 드문 경로에만 격리된 예외**라, 결정적
  라우팅이라는 기본 원칙은 그대로 유지된다.

## 6. 재현 방법

```powershell
# 시드 12문항
powershell -File eval\run-eval.ps1 -Label my-run -Reps 2
# 공식 30문항 (Spring AI 서버)
powershell -File eval\run-eval.ps1 -Label official -Reps 1 -SetFile official-eval.json
# 공식 30문항 (air 서버) — 에이전트를 MCP_SERVER_URL=http://localhost:8082 로 기동 후
powershell -File eval\run-eval.ps1 -Label official-air -Reps 1 -SetFile official-eval.json
# 키워드 미매칭 갭 — 에이전트를 ROUTER_FALLBACK=embedding 또는 ai 로 기동 후
powershell -File eval\run-eval.ps1 -Label gap-embedding-air -Reps 1 -SetFile keyword-gap-eval.json
# 구형 평면 배열 결과를 구조화 스키마로 정규화
python3 eval/normalize_legacy_results.py --in-file eval/results/baseline.json --out-file eval/results/baseline-fixed.json --dataset eval/eval-set.json --label baseline-fixed
```

`eval/run-full-eval.py`는 새 측정값을 처음부터 `metadata`/`summary`/`rows` 구조로 저장합니다.
예전 `baseline.json`, `after.json` 같은 평면 배열 결과는 `normalize_legacy_results.py` 또는
`eval/rescore.ps1`를 통해 같은 스키마로 다시 저장해 비교합니다.

## 7. 의미 기반 폴백 — 90%대 재현

기존 키워드를 질문에서 모두 제거한 공개 30문항과, 프롬프트 고정 뒤 새로 만든 보류
30문항을 `gemma3:4b` + `semantic-ai`로 평가했다.

| 평가셋 | 라우팅 적중률 | 오류/잘못된 출력 |
|---|---:|---:|
| 공개 keyword-gap | **93.3% (28/30)** | 0 |
| 키워드 무교집합 보류셋 | **96.7% (29/30)** | 0 |

보류셋은 현재 결정 라우터의 96개 키워드와 교집합이 0이다. 100%에는 도달하지 않았으며,
다중 도구가 같은 사실을 보유한 경계 문항이 남았다. 후보별 수치, 실패 문항, 누수 방지 절차와
재현 명령은 [키워드 없는 라우팅 실험 결과](./docs/research/KEYWORDLESS_ROUTING_RESULTS.md)에 기록했다.

### 전체 스택 답변 정확도

Docker PostgreSQL에 Company-X SQL·그래프·문서 40건을 적재하고 Spring AI MCP 서버를
실제로 연결한 `gemma3:4b` 측정값이다.

| 평가셋 | 라우팅 | 답변 정확도 | 평균 지연 |
|---|---:|---:|---:|
| 공식 원문 30문항 | **100%** | **66.7% (20/30)** | 8,442ms |
| 키워드 제거 30문항 | **93.3%** | **50.0% (15/30)** | 5,503ms |

기존 1B/Spring 기록 대비 공식은 26.7%→66.7%, 키워드 제거셋은 23.3%→50.0%다.
모델과 문서 적재가 함께 바뀌었으므로 단일 요인 개선으로 주장하지 않는다. 세부 실패 분석과
원시 JSON은 [실험 결과 문서](./docs/research/KEYWORDLESS_ROUTING_RESULTS.md)를 참고한다.
