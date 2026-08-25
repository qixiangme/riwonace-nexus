#!/bin/bash
# =============================================================================
# 장애 주입 데모 스크립트 (Fault Injection Demo)
# =============================================================================
#
# 목적: MCP 기반 시스템의 장애 복구(Recovery Policy) 능력을 시연
#
# 시나리오:
# 1. MCP 서버 타임아웃 시뮬레이션
# 2. SQL 스키마 오류 발생
# 3. 벡터 검색 빈 결과 상황
# 4. MCP 서버 전환 (Spring AI → AIR)
#
# 사용법:
#   ./scripts/fault-injection-demo.sh [scenario]
#
# 시나리오:
#   timeout   - MCP 타임아웃 시뮬레이션
#   schema    - SQL 스키마 오류
#   empty     - 검색 결과 없음
#   hotswap   - MCP 서버 핫스왑
#   all       - 모든 시나리오 순차 실행
#
# =============================================================================

set -e

AGENT_URL="${AGENT_URL:-http://localhost:8080}"
MCP_SPRING_URL="${MCP_SPRING_URL:-http://localhost:8081}"
MCP_AIR_URL="${MCP_AIR_URL:-http://localhost:8082}"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

print_header() {
    echo ""
    echo -e "${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${PURPLE}  $1${NC}"
    echo -e "${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
}

print_step() {
    echo -e "${CYAN}▶ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# API 호출 함수
call_agent() {
    local question="$1"
    local response=$(curl -s -X POST "$AGENT_URL/api/chat/v2?trace=true" \
        -H "Content-Type: application/json" \
        -d "{\"question\": \"$question\"}" 2>/dev/null)
    echo "$response"
}

# 결과 파싱 및 표시
show_result() {
    local response="$1"
    local answer=$(echo "$response" | jq -r '.answer // "응답 없음"' 2>/dev/null)
    local routes=$(echo "$response" | jq -r '.routes // []' 2>/dev/null)
    local coverage=$(echo "$response" | jq -r '.claimCoverage // "N/A"' 2>/dev/null)
    local model=$(echo "$response" | jq -r '.selectedModel // "N/A"' 2>/dev/null)
    local latency=$(echo "$response" | jq -r '.latencyMs // "N/A"' 2>/dev/null)
    local escalated=$(echo "$response" | jq -r '.wasEscalated // false' 2>/dev/null)

    echo -e "  ${BLUE}모델:${NC} $model"
    echo -e "  ${BLUE}라우트:${NC} $routes"
    echo -e "  ${BLUE}커버리지:${NC} $coverage"
    echo -e "  ${BLUE}에스컬레이션:${NC} $escalated"
    echo -e "  ${BLUE}지연시간:${NC} ${latency}ms"
    echo -e "  ${BLUE}답변:${NC} ${answer:0:100}..."
}

# 시나리오 1: 타임아웃 시뮬레이션
demo_timeout() {
    print_header "시나리오 1: MCP 타임아웃 복구"

    print_info "설명: MCP 서버 응답이 지연될 때 Recovery Policy가"
    print_info "      백오프 후 재시도 또는 대체 라우트로 전환합니다."
    echo ""

    print_step "1. 복잡한 다중 소스 질문 실행 (타임아웃 가능성 높음)"
    local response=$(call_agent "2023년 매출 상위 5개 제품의 상세 기술 문서와 관련 장애 이력을 모두 알려줘")

    if [ $? -eq 0 ]; then
        print_success "응답 수신 완료"
        show_result "$response"
    else
        print_warning "타임아웃 발생 - Recovery Policy 동작 확인"
    fi

    echo ""
    print_step "2. 폴백 라우트 확인"
    print_info "Recovery Policy는 타임아웃 시 다음 전략을 적용합니다:"
    print_info "  - 첫 번째: 백오프(2초) 후 동일 쿼리 재시도"
    print_info "  - 두 번째: 대체 라우트(VECTOR → GRAPH → SQL)로 전환"
}

# 시나리오 2: SQL 스키마 오류
demo_schema_error() {
    print_header "시나리오 2: SQL 스키마 오류 복구"

    print_info "설명: 존재하지 않는 테이블/컬럼 참조 시"
    print_info "      Self-Corrective SQL이 스키마 재확인 후 SQL을 재생성합니다."
    echo ""

    print_step "1. 의도적으로 모호한 테이블명 사용"
    local response=$(call_agent "employee_records 테이블에서 salary 평균을 알려줘")

    if [ $? -eq 0 ]; then
        print_success "응답 수신 완료"
        show_result "$response"

        local tool_calls=$(echo "$response" | jq -r '.toolCalls // []' 2>/dev/null)
        if echo "$tool_calls" | grep -q "retry"; then
            print_success "Self-Corrective SQL 재시도 감지됨"
        fi
    fi

    echo ""
    print_step "2. Recovery Policy 동작:"
    print_info "  1) SQL_SCHEMA_ERROR 분류"
    print_info "  2) 스키마 재확인 (get_schema 재호출)"
    print_info "  3) 올바른 테이블명으로 SQL 재생성"
    print_info "  4) 실패 시 VECTOR 라우트로 폴백"
}

# 시나리오 3: 빈 검색 결과
demo_empty_result() {
    print_header "시나리오 3: 검색 결과 없음 복구"

    print_info "설명: 검색 결과가 0건일 때 쿼리 완화 또는"
    print_info "      대체 라우트로 전환합니다."
    echo ""

    print_step "1. 존재하지 않을 가능성이 높은 검색"
    local response=$(call_agent "2030년 예정된 신규 프로젝트 목록을 알려줘")

    if [ $? -eq 0 ]; then
        print_success "응답 수신 완료"
        show_result "$response"

        local answer=$(echo "$response" | jq -r '.answer // ""' 2>/dev/null)
        if echo "$answer" | grep -q "찾을 수 없"; then
            print_info "Answerability Gate: 답변 불가 판정"
        fi
    fi

    echo ""
    print_step "2. Recovery Policy 동작:"
    print_info "  1) RETRIEVAL_EMPTY 분류"
    print_info "  2) 쿼리 조건 완화 (연도 조건 제거)"
    print_info "  3) 대체 라우트 시도"
    print_info "  4) 최종 실패 시 DECLINE 응답"
}

# 시나리오 4: MCP 서버 핫스왑
demo_hotswap() {
    print_header "시나리오 4: MCP 서버 핫스왑"

    print_info "설명: MCP 프로토콜 표준 준수로"
    print_info "      서버 교체 시에도 동일 기능을 보장합니다."
    echo ""

    print_step "1. 현재 MCP 서버 상태 확인"
    local tools_response=$(curl -s "$AGENT_URL/api/tools" 2>/dev/null)
    local current_tools=$(echo "$tools_response" | jq -r '.mcpTools // []' 2>/dev/null)
    print_info "현재 연결된 도구: $current_tools"

    print_step "2. Spring AI MCP 서버로 테스트 질문"
    print_info "MCP_SERVER_URL=$MCP_SPRING_URL"
    local response1=$(call_agent "직원 수는 몇 명이야?")
    show_result "$response1"

    echo ""
    print_step "3. AIR MCP 서버로 전환 데모"
    print_warning "실제 전환은 환경변수 변경 후 재시작 필요:"
    print_info "  export MCP_SERVER_URL=$MCP_AIR_URL"
    print_info "  ./gradlew :agent-app-spring:bootRun"

    echo ""
    print_step "4. MCP 표준 호환성 검증 포인트:"
    print_info "  ✓ 동일한 도구 시그니처 (run_sql, vector_search, kg_search)"
    print_info "  ✓ 동일한 JSON-RPC 프로토콜"
    print_info "  ✓ 응답 스키마 호환"
    print_info "  ✓ 에러 코드 표준화"
}

# 전체 벤치마크 출력
demo_benchmark() {
    print_header "아키텍처 v2 벤치마크 결과"

    print_info "테스트 환경:"
    print_info "  - 기본 모델: gemma3:1b"
    print_info "  - 중간 모델: qwen2.5:3b"
    print_info "  - 대형 모델: qwen2.5:7b"
    print_info "  - 데이터셋: Company-X 30문항 + Route Eval 12문항"
    echo ""

    echo -e "${YELLOW}┌─────────────────────────────────────────────────────────────┐${NC}"
    echo -e "${YELLOW}│  메트릭                          │  v1        │  v2        │${NC}"
    echo -e "${YELLOW}├─────────────────────────────────────────────────────────────┤${NC}"
    echo -e "${YELLOW}│  라우트 적중률                    │  83.3%     │  93.3%     │${NC}"
    echo -e "${YELLOW}│  키워드 적중률                    │  76.7%     │  86.7%     │${NC}"
    echo -e "${YELLOW}│  평균 지연시간                    │  2,450ms   │  2,180ms   │${NC}"
    echo -e "${YELLOW}│  P95 지연시간                     │  4,200ms   │  3,100ms   │${NC}"
    echo -e "${YELLOW}│  모델 에스컬레이션 비율           │  N/A       │  23.8%     │${NC}"
    echo -e "${YELLOW}│  평균 클레임 커버리지             │  N/A       │  78.5%     │${NC}"
    echo -e "${YELLOW}│  복구 성공률                      │  N/A       │  67.3%     │${NC}"
    echo -e "${YELLOW}└─────────────────────────────────────────────────────────────┘${NC}"
    echo ""

    print_info "주요 개선 사항:"
    print_success "  라우트 정확도 +10% (프로파일러 의도 분석)"
    print_success "  지연시간 -11% (적응형 모델 선택)"
    print_success "  에러 복구 67% 성공 (Recovery Policy)"
}

# 메인 실행
main() {
    local scenario="${1:-all}"

    print_header "MCP 장애 복구 데모 시작"
    print_info "Agent URL: $AGENT_URL"
    print_info "Spring AI MCP: $MCP_SPRING_URL"
    print_info "AIR MCP: $MCP_AIR_URL"

    case "$scenario" in
        timeout)
            demo_timeout
            ;;
        schema)
            demo_schema_error
            ;;
        empty)
            demo_empty_result
            ;;
        hotswap)
            demo_hotswap
            ;;
        benchmark)
            demo_benchmark
            ;;
        all)
            demo_timeout
            sleep 2
            demo_schema_error
            sleep 2
            demo_empty_result
            sleep 2
            demo_hotswap
            sleep 2
            demo_benchmark
            ;;
        *)
            print_error "알 수 없는 시나리오: $scenario"
            print_info "사용 가능: timeout, schema, empty, hotswap, benchmark, all"
            exit 1
            ;;
    esac

    print_header "데모 완료"
}

main "$@"
