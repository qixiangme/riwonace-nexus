package com.riwonace.mcp.ingest

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 벡터 저장소 적재를 수동으로 실행하는 운영용 API.
 *
 * 파일시스템 경로를 받는 `/admin` 엔드포인트이므로 운영 환경에서는 접근 제어가 필요하다.
 *
 * 보안 강화:
 * - 경로 정규화로 상위 디렉토리 탈출(../) 방지
 * - 심볼릭 링크를 통한 범위 탈출 방지
 * - 허용된 루트 디렉토리 내에서만 작동
 */
@RestController
@RequestMapping("/admin")
class IngestController(
    private val ingestor: DataIngestor,
    private val directoryIngestionService: DirectoryIngestionService,
    @Value("\${ingest.allowed-root:../companyx-dataset-v1.0/documents}")
    private val allowedRootPath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 허용된 읽기 전용 문서 루트. 배포 환경에서는 INGEST_ALLOWED_ROOT로 명시한다. */
    private val allowedRoot: Path by lazy {
        Paths.get(allowedRootPath).toAbsolutePath().normalize().toFile().canonicalFile.toPath()
    }

    /** 벡터 저장소가 비어 있을 때만 기본 문서를 적재하고, 추가된 문서 수를 반환한다. */
    @PostMapping("/ingest")
    fun ingest(): Map<String, Any> {
        val ingested = ingestor.ingestIfEmpty()
        return mapOf("ingested" to ingested)
    }

    /**
     * 지정한 디렉토리의 README를 제외한 마크다운 파일을 파일당 문서 1건으로 적재한다.
     * 같은 dataset의 기존 벡터를 트랜잭션 안에서 교체하므로 재실행해도 중복되지 않는다.
     */
    @PostMapping("/ingest-dir")
    fun ingestDirectory(
        @RequestParam path: String,
        @RequestParam(defaultValue = "manual") dataset: String,
    ): Map<String, Any?> {
        return try {
            require(DATASET_PATTERN.matches(dataset)) {
                "dataset은 영문, 숫자, 점, 밑줄, 하이픈만 사용해 1~64자로 지정해야 합니다."
            }
            val sanitizedPath = validateAndSanitizePath(path)
            val dir = File(sanitizedPath)

            if (!dir.isDirectory) {
                return mapOf("error" to "디렉토리가 아닙니다.")
            }
            if (!dir.canRead()) {
                return mapOf("error" to "읽기 권한이 없습니다.")
            }

            val result = directoryIngestionService.replaceMarkdownDirectory(dir, dataset)
            log.info("디렉토리 임베딩 교체 완료: dataset={}, {} 파일", dataset, result.ingested)
            mapOf(
                "ingested" to result.ingested,
                "replaced" to result.replaced,
                "files" to result.files,
                "dataset" to result.dataset,
            )
        } catch (e: IllegalArgumentException) {
            mapOf("error" to e.message)
        } catch (e: SecurityException) {
            log.warn("경로 접근 거부: {}", e.message)
            mapOf("error" to e.message)
        } catch (e: Exception) {
            log.error("디렉토리 임베딩 실패", e)
            mapOf("error" to "내부 오류가 발생했습니다.")
        }
    }

    /**
     * 경로를 검증하고 정규화한다.
     * @throws SecurityException 허용 범위를 벗어난 경로일 경우
     */
    private fun validateAndSanitizePath(path: String): String {
        // 경로 정규화
        val requestedPath = Paths.get(path).toAbsolutePath().normalize()

        // 허용 루트 내에 있는지 확인
        if (!requestedPath.startsWith(allowedRoot)) {
            throw SecurityException("허용된 디렉토리 범위를 벗어났습니다.")
        }

        // 심볼릭 링크 확인 (canonical path로 실제 경로 확인)
        val canonicalPath = requestedPath.toFile().canonicalFile.toPath()
        if (!canonicalPath.startsWith(allowedRoot)) {
            throw SecurityException("심볼릭 링크를 통한 범위 탈출이 탐지되었습니다.")
        }

        return canonicalPath.toString()
    }

    companion object {
        private val DATASET_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
