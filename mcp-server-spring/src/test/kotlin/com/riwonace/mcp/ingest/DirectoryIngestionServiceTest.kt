package com.riwonace.mcp.ingest

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectoryIngestionServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `같은 dataset을 교체하고 README를 제외한 Markdown만 적재한다`() {
        tempDir.resolve("DOC-002.md").writeText("두 번째")
        tempDir.resolve("DOC-001.md").writeText("첫 번째")
        tempDir.resolve("README.md").writeText("제외")
        tempDir.resolve("note.txt").writeText("제외")
        val vectorStore = RecordingVectorStore()
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.update(anyString(), anyString())).thenReturn(2)
        val service = DirectoryIngestionService(vectorStore, jdbc)

        val result = service.replaceMarkdownDirectory(tempDir.toFile(), "companyx-v1.0")

        assertEquals(2, result.ingested)
        assertEquals(2, result.replaced)
        assertEquals(listOf("DOC-001.md", "DOC-002.md"), result.files)
        verify(jdbc).update("DELETE FROM vector_store WHERE metadata->>'dataset' = ?", "companyx-v1.0")
        assertEquals("companyx-v1.0", vectorStore.added.first().metadata["dataset"])
    }

    @Test
    fun `적재할 Markdown이 없으면 기존 dataset을 삭제하지 않는다`() {
        tempDir.resolve("README.md").writeText("제외")
        val vectorStore = RecordingVectorStore()
        val jdbc = mock(JdbcTemplate::class.java)
        val service = DirectoryIngestionService(vectorStore, jdbc)

        val error = assertFailsWith<IllegalArgumentException> {
            service.replaceMarkdownDirectory(tempDir.toFile(), "companyx-v1.0")
        }

        assertEquals(
            "README를 제외한 Markdown 문서가 없어 기존 dataset을 교체하지 않았습니다.",
            error.message,
        )
        verifyNoInteractions(jdbc)
        assertEquals(emptyList(), vectorStore.added)
    }

    private class RecordingVectorStore : VectorStore {
        var added: List<Document> = emptyList()

        override fun add(documents: List<Document>) {
            added = documents
        }

        override fun delete(idList: List<String>) = Unit

        override fun delete(filterExpression: Filter.Expression) = Unit

        override fun similaritySearch(request: SearchRequest): List<Document> = emptyList()
    }
}
