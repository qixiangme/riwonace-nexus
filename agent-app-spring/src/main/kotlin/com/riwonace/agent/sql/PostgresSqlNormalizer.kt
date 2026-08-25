package com.riwonace.agent.sql

import org.springframework.stereotype.Component

/** 소형 모델이 섞어 쓰는 보편적인 SQL 방언 표기를 PostgreSQL 문법으로 제한적으로 정규화한다. */
@Component
class PostgresSqlNormalizer {
    fun normalize(sql: String): String = sql.replace('`', '"')
}
