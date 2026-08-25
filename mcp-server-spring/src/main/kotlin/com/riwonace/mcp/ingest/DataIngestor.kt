package com.riwonace.mcp.ingest

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 시드 문서를 pgvector에 임베딩한다.
 * Ollama가 아직 떠 있지 않아도 서버 기동은 막지 않고,
 * 이후 POST /admin/ingest 로 재시도할 수 있다.
 */
@Component
class DataIngestor(
    private val vectorStore: VectorStore,
    private val jdbc: JdbcTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            ingestIfEmpty()
        } catch (e: Exception) {
            log.warn(
                "시드 문서 임베딩 실패 (Ollama 미기동일 수 있음). " +
                    "Ollama 기동 후 POST /admin/ingest 로 재시도하세요. 원인: {}",
                e.message,
            )
        }
    }

    /**
     * 저장소 전체가 비어 있을 때만 기본 문서를 추가한다.
     * 동시 호출은 직렬화되며, 이미 데이터가 있으면 변경 없이 `0`을 반환한다.
     */
    @Synchronized
    fun ingestIfEmpty(): Int {
        val count = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long::class.java) ?: 0
        if (count > 0) {
            log.info("vector_store에 이미 {}건의 문서가 있어 임베딩을 건너뜁니다.", count)
            return 0
        }
        val docs = SEED_DOCUMENTS.map { (source, text) ->
            Document(text, mutableMapOf<String, Any>("source" to source))
        }
        vectorStore.add(docs)
        log.info("시드 문서 {}건을 임베딩했습니다.", docs.size)
        return docs.size
    }

    companion object {
        val SEED_DOCUMENTS: List<Pair<String, String>> = listOf(
            "mcp-overview.md" to
                "MCP(Model Context Protocol)는 Anthropic이 2024년에 공개한 개방형 표준으로, " +
                "AI 모델과 외부 데이터 소스·도구를 연결하는 규격화된 프로토콜이다. " +
                "USB-C처럼 하나의 표준 커넥터 역할을 하며, 서버가 노출한 도구(Tool)·리소스(Resource)·프롬프트(Prompt)를 " +
                "어떤 MCP 클라이언트든 동일한 방식으로 사용할 수 있다.",
            "mcp-vs-rag.md" to
                "MCP는 청킹·임베딩·검색을 없애는 기술이 아니라 에이전트와 데이터 기능 사이의 계약을 표준화한다. " +
                "본 시스템은 문서별 적재, 임베딩, 벡터 검색, SQL 검증, 그래프 조회를 MCP 서버 뒤에 캡슐화해 " +
                "에이전트가 MCP 연결 하나만 관리하게 한다. 참조 연구의 9→2 파라미터, 3→1 장애 지점, " +
                "64.0% 정확도는 해당 실험 구성의 결과이며 모든 MCP 시스템에 자동으로 보장되는 수치가 아니다.",
            "tacc.md" to
                "TACC(선별적 컨텍스트 큐레이션)는 모델 특성과 과업 유형에 따라 MCP 컨텍스트를 구성하는 원칙이다. " +
                "전현우·김태성·강현(2026)의 3,805회 실험에서는 전체 컨텍스트가 빈 기준선보다 유리했고, " +
                "Knowledge(K)만 유의한 주효과를 보였다. 추가 Diagnostics와 Guidelines의 효용은 모델별로 달랐으며 " +
                "과업이 분산의 약 56%를 설명했다. 따라서 핵심 지식을 유지하면서 관련도와 과업별 예산으로 노이즈를 제한한다.",
            "lost-in-the-middle.md" to
                "Liu et al.(2024)의 'Lost in the Middle' 연구에 따르면 언어모델은 긴 컨텍스트의 " +
                "중간에 위치한 정보를 잘 활용하지 못한다. 따라서 검색된 문서를 배치할 때 " +
                "가장 관련도 높은 문서를 맨 앞과 맨 뒤에 배치하는 전략이 효과적이다.",
            "pylon-7.md" to
                "Pylon-7은 Jeon(2026)이 제안한 AI 에이전트 워크플로의 7계층 참조 모델이다. " +
                "L1 데이터 저장, L2 검색·인덱싱, L3 프로토콜(MCP), L4 라우팅, L5 컨텍스트 구성, " +
                "L6 추론(LLM), L7 응답 생성으로 계층을 분리해 각 계층을 독립적으로 교체·검증할 수 있게 한다.",
            "pgvector.md" to
                "pgvector는 PostgreSQL에 벡터 자료형과 유사도 검색을 추가하는 오픈소스 확장이다. " +
                "HNSW와 IVFFlat 인덱스를 지원하며, 코사인 거리·내적·L2 거리 연산자를 제공한다. " +
                "별도의 벡터 전용 DB 없이 기존 PostgreSQL 운영 역량으로 벡터 검색을 서비스할 수 있어 " +
                "장애 지점과 운영 부담을 줄인다.",
            "air-framework.md" to
                "air는 리원에이스가 개발한 오픈소스 MCP 서버 프레임워크(Apache-2.0)다. " +
                "MCP SDK를 직접 사용할 때의 보일러플레이트를 대폭 줄여주며, " +
                "보안·캐싱·재시도 등 운영에 필요한 기능을 플러그인으로 제공한다.",
            "ollama-onprem.md" to
                "Ollama는 온프레미스 환경에서 소형 LLM을 손쉽게 실행하는 런타임이다. " +
                "gemma3:1b, qwen2.5:3b 같은 1~3B급 모델은 GPU 없이 일반 노트북 CPU만으로도 구동 가능해, " +
                "데이터를 외부로 보내지 않아야 하는 기업 환경에서 프라이버시와 비용 문제를 동시에 해결한다.",
            "routing-policy.md" to
                "본 플랫폼의 도구 선택은 LLM의 함수 호출(function calling)이 아니라 규칙 기반 라우터가 수행한다. " +
                "집계·수치 질문은 run_sql, 개념·설명 질문은 vector_search, 개체 간 관계 질문은 kg_search로 " +
                "결정적으로 라우팅되며, 복합 질문은 여러 도구를 병렬 호출(MCP Parallel 패턴)한 뒤 결과를 통합한다. " +
                "NL2SQL 스키마는 네 번째 실행 도구가 아니라 db://schema MCP Resource로 읽는다. " +
                "이는 소형 모델의 불안정한 도구 선택 능력에 의존하지 않기 위한 설계다.",
            "security-policy.md" to
                "데이터 플랫폼의 SQL 실행 도구는 읽기 전용으로 제한된다. SELECT 단일 문장만 허용하고 " +
                "INSERT, UPDATE, DELETE, DDL, 주석, 다중 문장은 모두 거부하며 결과 행 수도 기본 50행으로 제한한다. " +
                "이는 LLM이 생성한 SQL을 신뢰하지 않는 제로 트러스트 원칙을 따른 것이다.",
        )
    }
}
