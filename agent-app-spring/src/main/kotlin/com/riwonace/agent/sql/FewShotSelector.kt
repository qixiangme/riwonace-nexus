package com.riwonace.agent.sql

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.sqrt

/**
 * 동적 Few-Shot 예시 선택기.
 *
 * 고정된 예시 대신 질문과 유사한 예시를 동적으로 선택하여
 * NL2SQL 정확도를 향상시킨다.
 *
 * 논문 참고: Instructional Prompt Optimization for Few-Shot LLM (2025)
 * https://arxiv.org/pdf/2509.09066
 */
@Component
class FewShotSelector {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 질문-SQL 예시 풀.
     * 다양한 SQL 패턴을 커버한다:
     * - 단순 조회
     * - 집계 (COUNT, SUM, AVG)
     * - JOIN
     * - 서브쿼리
     * - GROUP BY
     * - ORDER BY + LIMIT
     */
    // 예시는 반드시 Company-X MCP 스키마의 실제 식별자와 외래키만 사용한다.
    // 스키마와 무관한 범용 예시는 작은 모델이 존재하지 않는 컬럼을 복사하게 만든다.
    private val examplePool = listOf(
        // 단순 필터
        SqlExample(
            question = "완료된 프로젝트는 몇 개야?",
            sql = "SELECT count(*) FROM projects WHERE status = 'completed'",
            pattern = "count-filter",
            keywords = setOf("몇 개", "개수", "수", "count"),
        ),
        // JOIN
        SqlExample(
            question = "경영지원팀 직원 이름과 연봉을 알려줘",
            sql = "SELECT e.name, e.salary FROM employees e JOIN departments d ON e.dept_id = d.id WHERE d.name = '경영지원팀'",
            pattern = "join-filter",
            keywords = setOf("팀", "부서", "직원"),
        ),
        // 집계 + GROUP BY
        SqlExample(
            question = "부서별 직원 수는?",
            sql = "SELECT d.name, count(*) FROM employees e JOIN departments d ON e.dept_id = d.id GROUP BY d.name",
            pattern = "group-count",
            keywords = setOf("별", "각", "그룹"),
        ),
        // SUM 집계
        SqlExample(
            question = "전체 계약 금액 합계는?",
            sql = "SELECT sum(amount) FROM contracts WHERE status = 'active'",
            pattern = "sum",
            keywords = setOf("합계", "총", "전체", "sum"),
        ),
        SqlExample(
            question = "활성 계약은 몇 건이야?",
            sql = "SELECT count(*) FROM contracts WHERE status = 'active'",
            pattern = "active-contract-count",
            keywords = setOf("활성", "계약", "몇", "개", "건", "수"),
        ),
        // AVG 집계
        SqlExample(
            question = "평균 연봉은 얼마야?",
            sql = "SELECT avg(salary) FROM employees",
            pattern = "avg",
            keywords = setOf("평균", "avg"),
        ),
        // MAX/MIN
        SqlExample(
            question = "가장 비싼 월 구독 제품은?",
            sql = "SELECT name, price_monthly FROM products ORDER BY price_monthly DESC LIMIT 1",
            pattern = "max-order",
            keywords = setOf("가장", "최고", "최대", "max"),
        ),
        SqlExample(
            question = "가장 싼 월 구독 제품은?",
            sql = "SELECT name, price_monthly FROM products ORDER BY price_monthly ASC LIMIT 1",
            pattern = "min-order",
            keywords = setOf("가장", "최저", "최소", "min"),
        ),
        // 다중 조건
        SqlExample(
            question = "서울 지역 활성 고객사 목록은?",
            sql = "SELECT name FROM clients WHERE region = '서울' AND is_active = true",
            pattern = "multi-filter",
            keywords = setOf("지역", "활성", "목록"),
        ),
        // 날짜 필터
        SqlExample(
            question = "2025년 신규 계약은 몇 건이야?",
            sql = "SELECT count(*) FROM contracts WHERE EXTRACT(YEAR FROM created_at) = 2025",
            pattern = "date-filter",
            keywords = setOf("년", "월", "일", "날짜", "기간"),
        ),
        // 서브쿼리
        SqlExample(
            question = "평균 이상 연봉 받는 직원은?",
            sql = "SELECT name, salary FROM employees WHERE salary > (SELECT avg(salary) FROM employees)",
            pattern = "subquery",
            keywords = setOf("이상", "초과", "보다", "평균"),
        ),
        // NOT IN / 부정
        SqlExample(
            question = "프로젝트가 없는 고객사는?",
            sql = "SELECT name FROM clients WHERE id NOT IN (SELECT DISTINCT client_id FROM projects)",
            pattern = "not-in",
            keywords = setOf("없", "않", "제외", "아닌"),
        ),
        // LEFT JOIN + NULL 체크
        SqlExample(
            question = "담당자가 배정되지 않은 티켓은?",
            sql = "SELECT id, title FROM support_tickets WHERE assignee_id IS NULL",
            pattern = "null-check",
            keywords = setOf("없", "미배정", "null", "배정되지"),
        ),
        // 매출/고객사 조인 + 그룹 순위
        SqlExample(
            question = "부산 지역 고객사별 매출 합계를 큰 순서로 보여줘",
            sql = "SELECT c.name, sum(s.amount) AS total_sales FROM sales s JOIN clients c ON s.client_id = c.id WHERE s.region = '부산' GROUP BY c.id, c.name ORDER BY total_sales DESC",
            pattern = "sales-client-ranking",
            keywords = setOf("매출", "고객사", "지역", "상위", "순서"),
        ),
        // 분기 문자열은 sale_date 추론보다 데이터셋의 quarter 컬럼을 우선한다.
        SqlExample(
            question = "2024년 2분기 매출 합계는?",
            sql = "SELECT sum(amount) FROM sales WHERE quarter = '2024-Q2'",
            pattern = "quarter-sales",
            keywords = setOf("분기", "매출", "합계", "총"),
        ),
        SqlExample(
            question = "클라우드 카테고리의 건당 평균 매출은?",
            sql = "SELECT avg(amount) FROM sales WHERE category = 'cloud'",
            pattern = "category-sales-average",
            keywords = setOf("카테고리", "평균", "매출"),
        ),
        SqlExample(
            question = "고객사별 진행 중 프로젝트 수를 많은 순서로 보여줘",
            sql = "SELECT c.name, count(*) AS project_count FROM projects p JOIN clients c ON p.client_id = c.id WHERE p.status = 'in_progress' GROUP BY c.id, c.name ORDER BY project_count DESC",
            pattern = "project-client-ranking",
            keywords = setOf("프로젝트", "고객사", "가장", "많은", "진행 중"),
        ),
        SqlExample(
            question = "해결되지 않은 High 우선순위 티켓 수는?",
            sql = "SELECT count(*) FROM support_tickets WHERE priority = 'high' AND status IN ('open', 'in_progress')",
            pattern = "unresolved-ticket-count",
            keywords = setOf("티켓", "우선순위", "해결", "건", "수"),
        ),
        SqlExample(
            question = "제품별 계약 합계를 큰 순서로 보여줘",
            sql = "SELECT p.name, sum(c.amount) AS total_amount FROM contracts c JOIN products p ON c.product_id = p.id GROUP BY p.id, p.name ORDER BY total_amount DESC",
            pattern = "product-contract-sum",
            keywords = setOf("제품별", "계약", "금액", "합계", "순서"),
        ),
        SqlExample(
            question = "2023년에 등록한 고객사 수는?",
            sql = "SELECT count(*) FROM clients WHERE registered_at >= DATE '2023-01-01' AND registered_at < DATE '2024-01-01'",
            pattern = "client-registration-year",
            keywords = setOf("등록", "고객사", "년", "몇", "수"),
        ),
        SqlExample(
            question = "부서별 평균 연봉을 높은 순서로 보여줘",
            sql = "SELECT d.name, avg(e.salary) AS average_salary FROM employees e JOIN departments d ON e.dept_id = d.id GROUP BY d.id, d.name ORDER BY average_salary DESC",
            pattern = "department-salary-ranking",
            keywords = setOf("부서", "평균", "연봉", "높은", "가장"),
        ),
    )

    /**
     * 질문과 가장 유사한 예시 N개를 선택한다.
     *
     * @param question 사용자 질문
     * @param topK 선택할 예시 수 (기본 3개)
     * @return 유사도 순으로 정렬된 예시 목록
     */
    fun selectExamples(question: String, topK: Int = 3): List<SqlExample> {
        val scores = examplePool.map { example ->
            example to calculateSimilarity(question, example)
        }

        val selected = scores
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        log.debug("Few-shot 예시 선택: {} → {}", question, selected.map { it.pattern })
        return selected
    }

    /**
     * 질문과 예시 간의 유사도를 계산한다.
     * 키워드 매칭 + TF-IDF 유사 방식 사용.
     */
    private fun calculateSimilarity(question: String, example: SqlExample): Double {
        val questionTokens = tokenize(question)
        val exampleTokens = tokenize(example.question)

        // 1. 키워드 매칭 점수 (가중치 높음)
        val keywordScore = example.keywords.count { keyword ->
            question.contains(keyword, ignoreCase = true)
        }.toDouble() / example.keywords.size.coerceAtLeast(1)

        // 2. 토큰 겹침 (Jaccard 유사도)
        val intersection = questionTokens.intersect(exampleTokens).size
        val union = questionTokens.union(exampleTokens).size
        val jaccardScore = if (union > 0) intersection.toDouble() / union else 0.0

        // 3. 코사인 유사도 (TF 기반)
        val cosineScore = cosineSimilarity(questionTokens, exampleTokens)

        // 가중 평균 (키워드 매칭에 높은 가중치)
        return keywordScore * 0.5 + jaccardScore * 0.25 + cosineScore * 0.25
    }

    /**
     * 텍스트를 토큰으로 분리한다.
     */
    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^가-힣a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .toSet()

    /**
     * 두 토큰 집합 간의 코사인 유사도를 계산한다.
     */
    private fun cosineSimilarity(tokens1: Set<String>, tokens2: Set<String>): Double {
        val allTokens = tokens1.union(tokens2)
        val vec1 = allTokens.map { if (it in tokens1) 1.0 else 0.0 }
        val vec2 = allTokens.map { if (it in tokens2) 1.0 else 0.0 }

        val dotProduct = vec1.zip(vec2).sumOf { it.first * it.second }
        val norm1 = sqrt(vec1.sumOf { it * it })
        val norm2 = sqrt(vec2.sumOf { it * it })

        return if (norm1 > 0 && norm2 > 0) dotProduct / (norm1 * norm2) else 0.0
    }

    /**
     * 선택된 예시를 프롬프트용 텍스트로 포맷팅한다.
     */
    fun formatExamplesForPrompt(examples: List<SqlExample>): String =
        examples.mapIndexed { index, example ->
            "예시${index + 1} — 질문: ${example.question}\nSQL: ${example.sql}"
        }.joinToString("\n")
}

/**
 * SQL 예시 데이터 클래스
 */
data class SqlExample(
    val question: String,
    val sql: String,
    val pattern: String,
    val keywords: Set<String>,
)
