package com.riwonace.mcp.ingest

import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File

data class DirectoryIngestionResult(
    val ingested: Int,
    val replaced: Int,
    val files: List<String>,
    val dataset: String,
)

/**
 * 디렉토리 문서의 임베딩·교체를 서버 내부 트랜잭션으로 캡슐화한다.
 * 평가 스크립트나 에이전트가 Ollama·vector_store에 직접 접속하지 않게 하는 운영 경계다.
 */
@Service
class DirectoryIngestionService(
    private val vectorStore: VectorStore,
    private val jdbc: JdbcTemplate,
) {
    @Transactional
    fun replaceMarkdownDirectory(dir: File, dataset: String): DirectoryIngestionResult {
        val files = dir.listFiles { file ->
            file.isFile && file.extension.equals("md", ignoreCase = true) &&
                !file.name.equals("README.md", ignoreCase = true)
        }.orEmpty().sortedBy { it.name }

        require(files.isNotEmpty()) {
            "README를 제외한 Markdown 문서가 없어 기존 dataset을 교체하지 않았습니다."
        }

        val docs = files.map { file ->
            Document(
                file.readText(Charsets.UTF_8),
                mutableMapOf<String, Any>("source" to file.name, "dataset" to dataset),
            )
        }
        val replaced = jdbc.update("DELETE FROM vector_store WHERE metadata->>'dataset' = ?", dataset)
        vectorStore.add(docs)

        return DirectoryIngestionResult(
            ingested = docs.size,
            replaced = replaced,
            files = files.map { it.name },
            dataset = dataset,
        )
    }
}
