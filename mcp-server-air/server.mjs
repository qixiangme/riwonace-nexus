// air 프레임워크로 구현한 MCP 데이터 플랫폼 서버 (Kotlin mcp-server와 동일한 실행 도구 3종).
// MCP 표준 규격 덕분에 agent-app은 접속 URL만 바꾸면 이 서버로 그대로 붙는다
// — "L3 프로토콜 계층의 구현체 교체 가능성" 실증.
import { defineServer, defineTool, defineResource, timeoutPlugin } from '@airmcp-dev/core';
import pg from 'pg';
import { pathToFileURL } from 'node:url';

const pool = new pg.Pool({
  host: 'localhost',
  port: 5433,
  database: 'riwonace',
  user: 'riwonace',
  password: 'riwonace',
  max: 5,
});

const OLLAMA = process.env.OLLAMA_BASE_URL ?? 'http://localhost:11434';
const MAX_OUTPUT_CHARS = 4000;
const MAX_HINT_VALUES = 12;

// 검색 가치가 높은 predicate 키워드 - 엔티티가 없을 때 이것들로 검색
// DB의 predicate는 한글: 담당한다, 사용한다, 소속, 부서장, 이끈다, 프로젝트, 이슈보고
// (Kotlin RetrievalTools.kt의 PREDICATE_KEYWORDS 1:1 포팅)
const PREDICATE_KEYWORDS = [
  ['부서장', '부서장'],
  ['담당', '담당한다'],
  ['사용', '사용한다'],
  ['소속', '소속'],
  ['이끈', '이끈다'],
  ['이끌', '이끈다'],
  ['이끄', '이끈다'], // "이끄는" → 이끈다 (어간 "이끌"이 아니라 "이끄"로 활용되는 경우)
  ['보고', '이슈보고'],
  ['팀장', '부서장'],
  ['진행', '프로젝트'], // "진행 중인 프로젝트" → 프로젝트 predicate
];

/** 모든 도구 공통 방어막: 예외를 오류 JSON으로, 출력 크기 제한 (Kotlin guard()와 동일 정책) */
const guard = async (fn) => {
  try {
    const json = JSON.stringify(await fn());
    return json.length > MAX_OUTPUT_CHARS ? json.slice(0, MAX_OUTPUT_CHARS) + '"...(truncated)"' : json;
  } catch (e) {
    return JSON.stringify({ error: e.message ?? String(e) });
  }
};

/** 제로 트러스트 SQL 검증 (Kotlin SqlGuard 포팅) */
const sqlGuard = (sql) => {
  const s = sql.trim().replace(/;+\s*$/, '');
  const upper = s.toUpperCase();
  if (!upper.startsWith('SELECT') && !upper.startsWith('WITH')) throw new Error('SELECT/WITH 문만 허용됩니다.');
  if (s.includes(';')) throw new Error('다중 문장은 허용되지 않습니다.');
  if (s.includes('--') || s.includes('/*')) throw new Error('주석은 허용되지 않습니다.');
  for (const kw of ['INSERT', 'UPDATE', 'DELETE', 'DROP', 'ALTER', 'CREATE', 'TRUNCATE', 'GRANT', 'REVOKE', 'PG_SLEEP']) {
    if (new RegExp(`\\b${kw}\\b`, 'i').test(s)) throw new Error(`${kw}는 허용되지 않습니다. SELECT만 가능합니다.`);
  }
  return /\bLIMIT\b/i.test(s) ? s : `${s} LIMIT 50`;
};

const embed = async (text) => {
  const res = await fetch(`${OLLAMA}/api/embeddings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'nomic-embed-text', prompt: text }),
  });
  if (!res.ok) throw new Error(`임베딩 실패: HTTP ${res.status}`);
  return (await res.json()).embedding;
};

/** 스키마는 실행 Action이 아니라 NL2SQL에 제공할 Knowledge이므로 MCP Resource로 노출한다. */
const readSchema = () =>
  guard(async () => {
    const { rows } = await pool.query(
      `SELECT table_name, column_name, data_type FROM information_schema.columns
       WHERE table_schema = 'public'
         AND table_name NOT IN ('vector_store', 'kg_triples', 'document_chunks')
       ORDER BY table_name, ordinal_position`,
    );
    const tables = {};
    for (const r of rows) (tables[r.table_name] ??= []).push(`${r.column_name} (${r.data_type})`);
    const valueHints = {};
    for (const r of rows.filter((r) => r.data_type.includes('char'))) {
      const { rows: vals } = await pool.query(
        `SELECT DISTINCT ${r.column_name} AS v FROM ${r.table_name}
         WHERE ${r.column_name} IS NOT NULL LIMIT ${MAX_HINT_VALUES + 1}`,
      );
      if (vals.length >= 1 && vals.length <= MAX_HINT_VALUES)
        valueHints[`${r.table_name}.${r.column_name}`] = vals.map((x) => x.v);
    }
    return { tables, valueHints };
  });

const server = defineServer({
  name: 'riwonace-data-platform-air',
  version: '1.0.0',
  transport: { type: 'sse', port: 8082 },
  use: [
    timeoutPlugin(30_000),
  ],
  resources: [
    defineResource('db://schema', {
      name: 'database-schema',
      description: 'NL2SQL 생성에 필요한 테이블·컬럼·저카디널리티 값 힌트',
      mimeType: 'application/json',
      handler: readSchema,
    }),
  ],
  tools: [
    defineTool('vector_search', {
      description:
        '사내 기술 문서 저장소에서 질문과 의미적으로 유사한 문서를 벡터 검색한다. ' +
        '개념 설명, 기술 소개, 정책·가이드 질문에 사용한다.',
      params: { query: 'string', topK: 'number?' },
      handler: ({ query, topK }) =>
        guard(async () => {
          const k = Math.min(Math.max(topK ?? 4, 1), 10);
          // 하이브리드 검색: 벡터 유사도 + 키워드 매칭을 RRF로 융합 (pgvector README 권장 패턴).
          // 임베딩 모델이 한국어 리콜을 놓치는 경우("백업 정책" → 운영 매뉴얼)를 키워드가 보완한다.
          const vec = `[${(await embed(query)).join(',')}]`;
          const { rows: vRows } = await pool.query(
            `SELECT metadata->>'source' AS source, content AS text,
                    1 - (embedding <=> $1::vector) AS score
             FROM vector_store ORDER BY embedding <=> $1::vector LIMIT 10`,
            [vec],
          );
          const STOP = new Set(['어떻게', '무엇', '되어', '있어', '있었', '있는', '해줘', '알려줘', '궁금해', '보여줘', '관련', '내용', '방법', '어디', '누구']);
          const tokens = query
            .split(/[\s,.?!'"()]+/)
            .map((t) => t.replace(/(은|는|이|가|을|를|의|에서|으로|로|와|과|도|만|야|이야)$/, ''))
            .filter((t) => t.length >= 2 && !STOP.has(t))
            .slice(0, 6);
          let kRows = [];
          if (tokens.length > 0) {
            // 매칭된 토큰 수로 정렬 — 흔한 단어 하나만 걸린 문서가 앞서지 않게 한다
            const hits = tokens.map((_, i) => `(CASE WHEN content ILIKE $${i + 1} THEN 1 ELSE 0 END)`).join(' + ');
            const where = tokens.map((_, i) => `content ILIKE $${i + 1}`).join(' OR ');
            kRows = (
              await pool.query(
                `SELECT metadata->>'source' AS source, content AS text, 0 AS score, ${hits} AS hits
                 FROM vector_store WHERE ${where} ORDER BY hits DESC LIMIT 10`,
                tokens.map((t) => `%${t}%`),
              )
            ).rows;
          }
          // Reciprocal Rank Fusion: 두 랭킹의 1/(60+순위) 합산
          const fused = new Map();
          const addRank = (rows, weight) =>
            rows.forEach((r, i) => {
              const key = r.text.slice(0, 80);
              const cur = fused.get(key) ?? { ...r, rrf: 0 };
              cur.rrf += weight / (60 + i);
              fused.set(key, cur);
            });
          addRank(vRows, 1.0);
          addRank(kRows, 1.0);
          return [...fused.values()]
            .sort((a, b) => b.rrf - a.rrf)
            .slice(0, k)
            .map((r) => ({ source: r.source ?? 'unknown', score: Math.max(Number(r.score), 0.5), text: r.text }));
        }),
    }),

    defineTool('run_sql', {
      description:
        'NL2SQL 경로가 만든 읽기 전용 SELECT SQL 한 문장을 검증·실행하고 결과를 JSON으로 반환한다. ' +
        '집계·통계·목록 등 정형 데이터 질문에 사용한다. INSERT/UPDATE/DELETE는 거부된다.',
      params: { sql: 'string' },
      handler: ({ sql }) =>
        guard(async () => {
          const safe = sqlGuard(sql);
          const { rows } = await pool.query(safe);
          return { executedSql: safe, rows };
        }),
    }),

    defineTool('kg_search', {
      description:
        '온톨로지 기반 지식 그래프에서 엔티티와 관련된 관계(triple)를 조회한다. ' +
        "'A와 B의 관계', '무엇을 개발했나' 같은 개체 간 연결 질문에 사용한다.",
      params: { query: 'string' },
      handler: ({ query }) =>
        guard(async () => {
          // 한국어 조사 제거: "Product-C1을" → "Product-C1" (Kotlin RetrievalTools.kt 포팅)
          const SUFFIXES = ['은', '는', '이', '가', '을', '를', '의', '에서', '에', '으로', '로', '와', '과', '도'];
          const rawTokens = [
            ...new Set(
              query
                .split(/[\s,.?!'"()]+/)
                .map((t) => t.trim())
                .filter((t) => t.length >= 2)
                .map((token) => {
                  for (const suf of SUFFIXES) {
                    if (token.length > suf.length + 2 && token.endsWith(suf)) return token.slice(0, -suf.length);
                  }
                  return token;
                }),
            ),
          ];
          const entityTokens = rawTokens.filter(
            (t) => t.includes('-') || t.endsWith('팀') || t.endsWith('부') || t.endsWith('부서') || t.endsWith('사업부') || /[A-Z]/.test(t),
          );
          // "이끄는"은 사람 뒤에 오면 "이끈다"(프로젝트 리드)지만, 부서 뒤에 오면 실제로는
          // "부서장"을 묻는 것이다("경영지원팀을 이끄는 사람" = 부서장).
          const isDepartmentEntity = entityTokens.some((t) => ['팀', '부', '부서', '사업부'].some((suf) => t.endsWith(suf)));
          const LEADS_PROJECT_KEYWORDS = new Set(['이끈', '이끌', '이끄']);
          const matchedPredicates = [
            ...new Set(
              PREDICATE_KEYWORDS.filter(([korean]) => query.includes(korean)).map(([korean, predicate]) =>
                isDepartmentEntity && LEADS_PROJECT_KEYWORDS.has(korean) ? '부서장' : predicate,
              ),
            ),
          ];
          const GRAPH_STOP_TOKENS = new Set(['누구', '무엇', '알려줘', '확인해줘', '현재', '실제', '하나', '이상']);
          const tokens = (entityTokens.length > 0 ? entityTokens : rawTokens.filter((t) => !GRAPH_STOP_TOKENS.has(t))).slice(0, 4);
          if (tokens.length === 0 && matchedPredicates.length === 0) return [];

          const tokenWhereClause = tokens
            .map((_, i) => {
              const b = i * 4;
              return `subject ILIKE $${b + 1} OR object ILIKE $${b + 2} OR $${b + 3} ILIKE '%' || subject || '%' OR $${b + 4} ILIKE '%' || object || '%'`;
            })
            .join(' OR ');
          const tokenParams = tokens.flatMap((t) => [`%${t}%`, `%${t}%`, t, t]);
          const hasSpecificEntity = entityTokens.length > 0;
          const predicateWhereClause = matchedPredicates.length > 0 ? matchedPredicates.map(() => 'predicate = ?').join(' OR ') : null;

          // predicate 자리표시자는 위치 기반($n)이 아니라 순서대로 이어붙는 파라미터라 실제 SQL을
          // 만들 때 $n 인덱스를 새로 부여해야 한다.
          let paramIdx = tokenParams.length;
          const predicateWhereIndexed = predicateWhereClause
            ? matchedPredicates.map(() => `predicate = $${++paramIdx}`).join(' OR ')
            : null;
          const where =
            tokens.length > 0 && predicateWhereIndexed
              ? hasSpecificEntity
                ? `(${tokenWhereClause}) AND (${predicateWhereIndexed})`
                : `(${tokenWhereClause}) OR (${predicateWhereIndexed})`
              : predicateWhereIndexed ?? tokenWhereClause;
          const params = [...tokenParams, ...matchedPredicates];

          const { rows: direct } = await pool.query(
            `SELECT subject, predicate, object FROM kg_triples WHERE ${where} LIMIT 30`,
            params,
          );

          // predicate 2개 이상 매칭되면("부서장이 담당하는") 체이닝: 1홉(엔티티 --[첫 predicate]-->
          // 중간 엔티티) 다음 2홉(중간 엔티티 --[다음 predicate]--> 결과).
          const lastPredicate = matchedPredicates.at(-1) ?? null;
          const directSatisfiesLastPredicate = lastPredicate !== null && direct.some((r) => r.predicate === lastPredicate);
          let neighbors = [];
          if (!directSatisfiesLastPredicate && hasSpecificEntity && tokens.length > 0 && matchedPredicates.length >= 2) {
            const firstPredicate = matchedPredicates[0];
            const restPredicates = matchedPredicates.slice(1);
            const { rows: hop1 } = await pool.query(
              `SELECT subject, predicate, object FROM kg_triples WHERE (${tokenWhereClause}) AND predicate = $${tokenParams.length + 1} LIMIT 30`,
              [...tokenParams, firstPredicate],
            );
            const intermediateEntities = [...new Set(hop1.flatMap((r) => [r.subject, r.object]))];
            if (intermediateEntities.length > 0) {
              const inClause = intermediateEntities.map((_, i) => `$${i + 1}`).join(',');
              const inClause2 = intermediateEntities.map((_, i) => `$${intermediateEntities.length + i + 1}`).join(',');
              const restWhere = restPredicates.map((_, i) => `predicate = $${intermediateEntities.length * 2 + i + 1}`).join(' OR ');
              const { rows } = await pool.query(
                `SELECT subject, predicate, object FROM kg_triples
                 WHERE (subject IN (${inClause}) OR object IN (${inClause2})) AND (${restWhere}) LIMIT 30`,
                [...intermediateEntities, ...intermediateEntities, ...restPredicates],
              );
              neighbors = rows;
            }
          }

          // 그 외 2홉 확장은 질문이 명시적으로 간접/연쇄 관계를 요구할 때만 수행한다.
          const entities = [...new Set(direct.flatMap((r) => [r.subject, r.object]))];
          const requiresExplicitExpansion = ['2홉', '간접', '연쇄', '거쳐', '연결된 프로젝트'].some((cue) => query.includes(cue));
          let explicitNeighbors = [];
          if (entities.length > 0 && requiresExplicitExpansion) {
            const inClause = entities.map((_, i) => `$${i + 1}`).join(',');
            const inClause2 = entities.map((_, i) => `$${entities.length + i + 1}`).join(',');
            const { rows } = await pool.query(
              `SELECT subject, predicate, object FROM kg_triples
               WHERE subject IN (${inClause}) OR object IN (${inClause2}) LIMIT 30`,
              [...entities, ...entities],
            );
            explicitNeighbors = rows;
          }

          const seen = new Set();
          return [...direct, ...neighbors, ...explicitNeighbors]
            .filter((r) => {
              const key = `${r.subject}|${r.predicate}|${r.object}`;
              return seen.has(key) ? false : seen.add(key);
            })
            .slice(0, 40)
            .map((r) => `${r.subject} --[${r.predicate}]--> ${r.object}`);
        }),
    }),
  ],
});

export { server };

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  server.start();
  console.log('air MCP server on :8082 (tools: vector_search, run_sql, kg_search; resource: db://schema)');
}
