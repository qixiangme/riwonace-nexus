package com.riwonace.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * MCP 표준 규격으로 노출되는 데이터 플랫폼 실행 도구 3종.
 * 에이전트는 이 도구들만 알면 되고, DB 접속 정보·임베딩 모델 등
 * 내부 구현은 알 필요가 없다. 물리 장애가 사라지는 것이 아니라 데이터 통합 경계를 MCP로 모은다.
 * NL2SQL 스키마는 실행 동작이 아니라 Knowledge이므로 `db://schema` Resource로 분리한다.
 */
@Component
class RetrievalTools(
    private val vectorStore: VectorStore,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val responseEncoder = ToolResponseEncoder(mapper, MAX_OUTPUT_CHARS)

    @Tool(
        name = "vector_search",
        description = "사내 기술 문서 저장소에서 질문과 의미적으로 유사한 문서를 벡터 검색한다. " +
            "개념 설명, 기술 소개, 정책·가이드 질문에 사용한다.",
    )
    fun vectorSearch(
        @ToolParam(description = "검색할 자연어 질의") query: String,
        @ToolParam(description = "가져올 문서 수 (1~10, 기본 4)", required = false) topK: Int?,
    ): String = guard {
        val k = (topK ?: 4).coerceIn(1, 10)
        val docs = vectorStore.similaritySearch(
            SearchRequest.builder().query(query).topK((k * 5).coerceAtMost(20)).build(),
        ) ?: emptyList()
        docs.sortedWith(
            compareByDescending<org.springframework.ai.document.Document> {
                lexicalCoverage(query, it.text.orEmpty())
            }.thenByDescending { it.score ?: 0.0 },
        ).take(k).map {
            mapOf(
                "source" to (it.metadata["source"] ?: "unknown"),
                "score" to it.score,
                "text" to it.text,
            )
        }
    }

    @Tool(
        name = "run_sql",
        description = "NL2SQL 경로가 만든 읽기 전용 SELECT SQL 한 문장을 검증·실행하고 결과를 JSON으로 반환한다. " +
            "집계·통계·목록 등 정형 데이터 질문에 사용한다. INSERT/UPDATE/DELETE는 거부된다.",
    )
    fun runSql(
        @ToolParam(description = "실행할 PostgreSQL SELECT 문") sql: String,
    ): String = guard {
        val safe = SqlGuard.sanitize(sql)
        mapOf(
            "executedSql" to safe,
            "rows" to jdbc.queryForList(safe),
        )
    }

    private val log = org.slf4j.LoggerFactory.getLogger(RetrievalTools::class.java)

    @Tool(
        name = "kg_search",
        description = "온톨로지 기반 지식 그래프에서 엔티티와 관련된 관계(triple)를 조회한다. " +
            "'A와 B의 관계', '무엇을 개발했나' 같은 개체 간 연결 질문에 사용한다.",
    )
    fun kgSearch(
        @ToolParam(description = "관계를 조회할 자연어 질의 또는 엔티티 이름") query: String,
    ): String = guard {
        log.info("[kg_search] query='$query'")
        val rawTokens = query.split(Regex("[\\s,.?!'\"()]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .map { token ->
                // 한국어 조사 제거: "Product-C1을" → "Product-C1"
                val suffixes = listOf("은", "는", "이", "가", "을", "를", "의", "에", "에서", "으로", "로", "와", "과", "도")
                suffixes.fold(token) { acc, suffix ->
                    if (acc.length > suffix.length + 2 && acc.endsWith(suffix)) acc.dropLast(suffix.length) else acc
                }
            }
            .distinct()
        val entityTokens = rawTokens.filter { token ->
            '-' in token || token.endsWith("팀") || token.endsWith("부") || token.endsWith("부서") ||
                token.endsWith("사업부") || token.any(Char::isUpperCase)
        }
        // predicate 키워드로 매칭되는 predicate 찾기
        // "이끄는"은 사람 뒤에 오면 "이끈다"(프로젝트 리드)지만, 부서 뒤에 오면 실제로는
        // "부서장"을 묻는 것이다("경영지원팀을 이끄는 사람" = 부서장). 엔티티가 부서 토큰이면
        // LEADS_PROJECT_KEYWORDS 매칭을 부서장으로 바꿔 잘못된 predicate 검색을 막는다.
        val isDepartmentEntity = entityTokens.any { token ->
            listOf("팀", "부", "부서", "사업부").any(token::endsWith)
        }
        val matchedPredicates = PREDICATE_KEYWORDS.filter { (korean, _) -> query.contains(korean) }
            .map { (korean, predicate) ->
                if (isDepartmentEntity && korean in LEADS_PROJECT_KEYWORDS) "부서장" else predicate
            }
            .distinct()
        val tokens = (entityTokens.ifEmpty { rawTokens.filterNot { it in GRAPH_STOP_TOKENS } }).take(4)
        log.info("[kg_search] rawTokens=$rawTokens, entityTokens=$entityTokens, tokens=$tokens, matchedPredicates=$matchedPredicates")
        // 토큰이 없어도 predicate 검색 가능
        if (tokens.isEmpty() && matchedPredicates.isEmpty()) {
            log.info("[kg_search] Early return: no tokens and no predicates")
            return@guard emptyList<Any>()
        }

        // 양방향 포함 매칭: 엔티티가 토큰에 포함되는 경우("air는" ⊃ "air")도 잡아
        // 한국어 조사가 붙은 질의에서도 매칭된다.
        val tokenWhere = if (tokens.isNotEmpty()) {
            tokens.joinToString(" OR ") {
                "subject ILIKE ? OR object ILIKE ? OR ? ILIKE '%' || subject || '%' OR ? ILIKE '%' || object || '%'"
            }
        } else null
        val predicateWhere = if (matchedPredicates.isNotEmpty()) {
            matchedPredicates.joinToString(" OR ") { "predicate = ?" }
        } else null
        // 구체적 엔티티(Client-X, Product-X 등)와 predicate가 모두 있으면 AND 결합
        // 일반 토큰(진행, 프로젝트 등)은 OR 유지
        val hasSpecificEntity = entityTokens.isNotEmpty()
        val where = if (hasSpecificEntity && predicateWhere != null) {
            // Client-A + 사용한다 → AND 결합으로 정확한 결과
            "($tokenWhere) AND ($predicateWhere)"
        } else {
            listOfNotNull(tokenWhere, predicateWhere).joinToString(" OR ")
        }
        val tokenParams = tokens.flatMap { listOf("%$it%", "%$it%", it, it) }
        val predicateParams = matchedPredicates
        val params = (tokenParams + predicateParams).toTypedArray()
        log.info("[kg_search] SQL WHERE: $where")
        log.info("[kg_search] SQL params: ${params.toList()}")
        val direct = jdbc.queryForList(
            "SELECT subject, predicate, object FROM kg_triples WHERE $where LIMIT 30",
            *params,
        )
        log.info("[kg_search] direct results: ${direct.size}")
        // 질문에 predicate 키워드가 2개 이상 매칭되면("부서장이 담당하는") 실제로는 체이닝이다:
        // 1홉(엔티티 --[첫 predicate]--> 중간 엔티티) 다음 2홉(중간 엔티티 --[다음 predicate]--> 결과).
        // 예: "클라우드사업부 --[부서장]--> 안진우" 다음 "안진우 --[담당한다]--> Client-I".
        // direct는 predicateWhere가 OR라서 첫 predicate(부서장)만 만족해도 걸린다 — 정말 필요한
        // 마지막 predicate(담당한다)로 끝나는 트리플이 없으면 체이닝으로 최종 hop을 찾는다.
        val lastPredicate = matchedPredicates.lastOrNull()
        val directSatisfiesLastPredicate = lastPredicate != null && direct.any { it["predicate"] == lastPredicate }
        val neighbors = if (
            !directSatisfiesLastPredicate && hasSpecificEntity && tokenWhere != null && matchedPredicates.size >= 2
        ) {
            val firstPredicate = matchedPredicates[0]
            val restPredicates = matchedPredicates.drop(1)
            val hop1 = jdbc.queryForList(
                "SELECT subject, predicate, object FROM kg_triples WHERE ($tokenWhere) AND predicate = ? LIMIT 30",
                *(tokenParams + firstPredicate).toTypedArray(),
            )
            val intermediateEntities = hop1.flatMap { listOf(it["subject"] as String, it["object"] as String) }
                .distinct()
            if (intermediateEntities.isEmpty()) {
                emptyList()
            } else {
                val inClause = intermediateEntities.joinToString(",") { "?" }
                val restWhere = restPredicates.joinToString(" OR ") { "predicate = ?" }
                jdbc.queryForList(
                    "SELECT subject, predicate, object FROM kg_triples " +
                        "WHERE (subject IN ($inClause) OR object IN ($inClause)) AND ($restWhere) LIMIT 30",
                    *(intermediateEntities + intermediateEntities + restPredicates).toTypedArray(),
                )
            }
        } else {
            emptyList()
        }
        // 그 외 2홉 확장은 질문이 명시적으로 간접/연쇄 관계를 요구할 때만 수행한다.
        // (예: "Product-D1 관련 프로젝트" → 사용 고객 → 그 고객의 프로젝트)
        val entities = direct.flatMap { listOf(it["subject"] as String, it["object"] as String) }.distinct()
        val requiresExplicitExpansion = listOf("2홉", "간접", "연쇄", "거쳐", "연결된 프로젝트").any(query::contains)
        val explicitNeighbors =
            if (entities.isEmpty() || !requiresExplicitExpansion) emptyList()
            else {
                val inClause = entities.joinToString(",") { "?" }
                jdbc.queryForList(
                    "SELECT subject, predicate, object FROM kg_triples " +
                        "WHERE subject IN ($inClause) OR object IN ($inClause) LIMIT 30",
                    *(entities + entities).toTypedArray(),
                )
            }
        // 직접 매칭을 앞에 배치 (관련도 순) — 총 40개로 제한해 컨텍스트 예산 보호
        (direct + neighbors + explicitNeighbors)
            .distinctBy { "${it["subject"]}|${it["predicate"]}|${it["object"]}" }
            .take(40)
            .map { "${it["subject"]} --[${it["predicate"]}]--> ${it["object"]}" }
    }

    /**
     * 모든 도구의 공통 방어막: 예외를 삼켜 오류 JSON으로 바꾸고(연결 보호),
     * 출력 크기를 제한한다(MCP 스펙의 출력 sanitize 의무 + 컨텍스트 예산 보호).
     */
    private inline fun guard(maxOutputChars: Int = MAX_OUTPUT_CHARS, block: () -> Any): String =
        try {
            responseEncoder.encode(block(), maxOutputChars)
        } catch (e: Exception) {
            responseEncoder.encodeError(e)
        }

    companion object {
        const val MAX_OUTPUT_CHARS = 4000
        private val GRAPH_STOP_TOKENS = setOf(
            "누구", "무엇", "알려줘", "확인해줘", "현재", "실제", "하나", "이상",
        )
        // 검색 가치가 높은 predicate 키워드 - 엔티티가 없을 때 이것들로 검색
        // DB의 predicate는 한글: 담당한다, 사용한다, 소속, 부서장, 이끈다, 프로젝트, 이슈보고
        private val PREDICATE_KEYWORDS = setOf(
            "부서장" to "부서장",
            "담당" to "담당한다",
            "사용" to "사용한다",
            "소속" to "소속",
            "이끈" to "이끈다",
            "이끌" to "이끈다",
            "이끄" to "이끈다",  // "이끄는" → 이끈다 (어간 "이끌"이 아니라 "이끄"로 활용되는 경우)
            "보고" to "이슈보고",
            "팀장" to "부서장",
            "진행" to "프로젝트",  // "진행 중인 프로젝트" → 프로젝트 predicate
        )
        private val LEADS_PROJECT_KEYWORDS = setOf("이끈", "이끌", "이끄")
    }
}

/** 벡터 후보군 안에서 질문 어휘 coverage를 우선하고, 동률일 때 벡터 점수를 사용한다. */
internal fun lexicalCoverage(query: String, text: String): Double {
    val queryTerms = retrievalTerms(query)
    val textTerms = retrievalTerms(text)
    return if (queryTerms.isEmpty()) 0.0 else queryTerms.intersect(textTerms).size.toDouble() / queryTerms.size
}

private fun retrievalTerms(text: String): Set<String> = Regex("[가-힣a-z0-9][가-힣a-z0-9_-]{1,}")
    .findAll(text.lowercase())
    .map { match ->
        val term = match.value
        val suffix = listOf("으로", "에서", "에게", "까지", "부터", "의", "에", "은", "는", "이", "가", "을", "를", "과", "와", "도")
            .firstOrNull { term.length > it.length + 1 && term.endsWith(it) }
        if (suffix == null) term else term.dropLast(suffix.length)
    }
    .filter { it.length >= 2 }
    .toSet()
