package com.riwonace.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * MCP 도구 응답의 JSON 문법과 출력 예산을 한 경계에서 보장한다.
 *
 * 보안 강화:
 * - 에러 메시지를 유형별로 일반화하여 내부 정보 노출 방지
 * - 과도한 결과를 구조화된 오류로 대체해 무효 JSON과 컨텍스트 폭증 방지
 */
class ToolResponseEncoder(
    private val mapper: ObjectMapper,
    private val maxOutputChars: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(maxOutputChars >= MIN_OUTPUT_CHARS) {
            "maxOutputChars는 구조화된 오류를 담을 수 있도록 $MIN_OUTPUT_CHARS 이상이어야 합니다."
        }
    }

    fun encode(value: Any, outputLimit: Int = maxOutputChars): String {
        require(outputLimit >= MIN_OUTPUT_CHARS) {
            "outputLimit은 구조화된 오류를 담을 수 있도록 $MIN_OUTPUT_CHARS 이상이어야 합니다."
        }
        val json = mapper.writeValueAsString(value)
        if (json.length <= outputLimit) return json

        return mapper.writeValueAsString(
            mapOf(
                "error" to "tool_output_too_large",
                "truncated" to true,
                "originalChars" to json.length,
                "maxOutputChars" to outputLimit,
            ),
        )
    }

    /**
     * 에러를 안전하게 인코딩한다.
     * 내부 구현 정보를 노출하지 않도록 에러 유형별로 일반화된 메시지를 반환한다.
     */
    fun encodeError(error: Exception): String {
        val errorType = classifyError(error)
        val userMessage = getUserFriendlyMessage(errorType)

        // 상세 정보는 로그에만 기록
        log.warn("도구 실행 오류 [{}]: {}", errorType, error.message)

        val encoded = mapper.writeValueAsString(
            mapOf(
                "error" to userMessage,
                "type" to errorType.name,
                "truncated" to false,
            ),
        )
        if (encoded.length <= maxOutputChars) return encoded

        return mapper.writeValueAsString(
            mapOf(
                "error" to "내부 오류가 발생했습니다.",
                "type" to "UNKNOWN",
                "truncated" to true,
            ),
        )
    }

    /** 에러 유형을 분류한다. */
    private fun classifyError(e: Exception): ErrorType {
        val message = (e.cause?.message ?: e.message ?: "").lowercase()
        return when {
            message.contains("syntax error") -> ErrorType.SQL_SYNTAX
            message.contains("permission denied") -> ErrorType.SQL_PERMISSION
            message.contains("does not exist") -> ErrorType.SQL_NOT_FOUND
            message.contains("timeout") || message.contains("timed out") -> ErrorType.TIMEOUT
            message.contains("connection") -> ErrorType.CONNECTION
            e is IllegalArgumentException -> ErrorType.INVALID_INPUT
            else -> ErrorType.UNKNOWN
        }
    }

    /** 에러 유형별 사용자 친화적 메시지를 반환한다. */
    private fun getUserFriendlyMessage(errorType: ErrorType): String = when (errorType) {
        ErrorType.SQL_SYNTAX -> "SQL 문법 오류입니다. 질의를 다시 확인해주세요."
        ErrorType.SQL_PERMISSION -> "데이터베이스 접근 권한이 없습니다."
        ErrorType.SQL_NOT_FOUND -> "요청한 데이터를 찾을 수 없습니다."
        ErrorType.TIMEOUT -> "질의 실행 시간이 초과되었습니다. 조건을 좀 더 구체적으로 명시해주세요."
        ErrorType.CONNECTION -> "데이터베이스 연결에 실패했습니다."
        ErrorType.INVALID_INPUT -> "입력값이 올바르지 않습니다."
        ErrorType.UNKNOWN -> "내부 오류가 발생했습니다."
    }

    private enum class ErrorType {
        SQL_SYNTAX,
        SQL_PERMISSION,
        SQL_NOT_FOUND,
        TIMEOUT,
        CONNECTION,
        INVALID_INPUT,
        UNKNOWN
    }

    private companion object {
        const val MIN_OUTPUT_CHARS = 128
    }
}
