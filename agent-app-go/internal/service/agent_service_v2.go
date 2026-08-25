// Package service ports com.riwonace.agent.service.AgentServiceV2 from the
// Kotlin/Spring AI baseline.
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"sync"
	"time"

	agentcontext "github.com/riwonace/agent-app-go/internal/context"
	"github.com/riwonace/agent-app-go/internal/core"
	"github.com/riwonace/agent-app-go/internal/llm"
	agentmcp "github.com/riwonace/agent-app-go/internal/mcp"
	"github.com/riwonace/agent-app-go/internal/router"
	agentsql "github.com/riwonace/agent-app-go/internal/sql"
)

const maxSQLRetries = 2

// AgentAnswerV2 mirrors data class AgentAnswerV2.
type AgentAnswerV2 struct {
	Answer         string
	Routes         []router.Route
	ToolCalls      []string
	ContextSources []string
	LatencyMs      int64
	Trace          *core.ExecutionTrace
	SelectedModel  string
	ClaimCoverage  float64
	WasEscalated   bool
}

// AgentServiceV2 ports AgentServiceV2.kt 1:1: profile -> plan (DAG) -> execute ->
// optimize evidence -> answerability gate -> generate (with model escalation) -> trace.
type AgentServiceV2 struct {
	Planner                 *core.ExecutionPlanner
	Optimizer               *core.EvidenceOptimizer
	Gate                    *core.AnswerabilityGate
	Recovery                *core.RecoveryPolicy
	Escalator               *core.ModelEscalator
	Gateway                 *agentmcp.Gateway
	LLM                     *llm.ChatClient
	FewShotSelector         *agentsql.FewShotSelector
	SchemaLinker            *agentsql.SchemaLinker
	SchemaPromptFormatter   *agentsql.SchemaPromptFormatter
	DeterministicSqlPlanner *agentsql.DeterministicSqlPlanner
	Logger                  *slog.Logger
}

func (s *AgentServiceV2) Chat(ctx context.Context, question string) AgentAnswerV2 {
	started := time.Now()
	var toolCalls []string
	var nodeTraces []core.NodeTrace

	plan := s.Planner.Plan(question)

	items := s.executePlan(ctx, plan, &toolCalls, &nodeTraces)

	optimized := s.Optimizer.Optimize(items, plan.Profile.SuggestedRoutes, plan.Profile)

	gateResult := s.Gate.Verify(question, optimized.Selected, plan.Profile)
	finalEvidence := optimized.Selected
	wasEscalated := false

	switch gateResult.RecommendedAction {
	case core.ActionSearchMore:
		additionalItems := s.searchAdditional(ctx, question, gateResult.MissingClaims, &toolCalls)
		combined := s.Optimizer.Optimize(append(items, additionalItems...), plan.Profile.SuggestedRoutes, plan.Profile)
		finalEvidence = combined.Selected
	case core.ActionDecline:
		return s.declineResponse(question, plan, started, nodeTraces, toolCalls)
	}

	modelSelection := s.Escalator.SelectModel(plan.Profile)
	answer := s.generateAnswer(ctx, question, finalEvidence, modelSelection.Model)

	answerQuality := estimateAnswerQuality(answer, question)
	if answerQuality < 0.6 || gateResult.ClaimCoverage < 0.7 {
		if reescalation := s.Escalator.ShouldReescalate(modelSelection, answerQuality, gateResult.ClaimCoverage); reescalation != nil {
			answer = s.generateAnswer(ctx, question, finalEvidence, reescalation.Model)
			modelSelection = *reescalation
			wasEscalated = true
		}
	}

	totalDuration := time.Since(started).Milliseconds()

	sources := make([]string, len(finalEvidence))
	for i, e := range finalEvidence {
		sources[i] = e.Source
	}

	trace := &core.ExecutionTrace{
		Plan:            plan,
		NodeTraces:      nodeTraces,
		TotalDurationMs: totalDuration,
		FinalAnswer:     answer,
		EvidenceSources: sources,
		ClaimCoverage:   gateResult.ClaimCoverage,
	}

	return AgentAnswerV2{
		Answer:         answer,
		Routes:         plan.Profile.SuggestedRoutes,
		ToolCalls:      toolCalls,
		ContextSources: sources,
		LatencyMs:      totalDuration,
		Trace:          trace,
		SelectedModel:  modelSelection.Model,
		ClaimCoverage:  gateResult.ClaimCoverage,
		WasEscalated:   wasEscalated,
	}
}

func (s *AgentServiceV2) executePlan(ctx context.Context, plan core.ExecutionPlan, toolCalls *[]string, nodeTraces *[]core.NodeTrace) []agentcontext.ContextItem {
	results := make(map[string]any)
	var resultsMu sync.Mutex
	var toolCallsMu sync.Mutex
	var tracesMu sync.Mutex
	var items []agentcontext.ContextItem

	levels := groupByDependencyLevel(plan.Nodes)

	for _, level := range levels {
		var wg sync.WaitGroup
		levelItems := make([][]agentcontext.ContextItem, len(level))

		for i, node := range level {
			wg.Add(1)
			go func(i int, node *core.PlanNode) {
				defer wg.Done()
				resultsMu.Lock()
				snapshot := make(map[string]any, len(results))
				for k, v := range results {
					snapshot[k] = v
				}
				resultsMu.Unlock()

				nodeItems := s.executeNode(ctx, node, snapshot, &toolCallsMu, toolCalls, &tracesMu, nodeTraces)
				levelItems[i] = nodeItems

				if node.OutputBinding != "" {
					resultsMu.Lock()
					results[node.OutputBinding] = nodeItems
					resultsMu.Unlock()
				}
			}(i, node)
		}
		wg.Wait()

		for _, li := range levelItems {
			items = append(items, li...)
		}
	}

	return items
}

func (s *AgentServiceV2) executeNode(
	ctx context.Context,
	node *core.PlanNode,
	results map[string]any,
	toolCallsMu *sync.Mutex,
	toolCalls *[]string,
	tracesMu *sync.Mutex,
	nodeTraces *[]core.NodeTrace,
) []agentcontext.ContextItem {
	startedAt := time.Now()
	node.Status = core.NodeRunning

	if node.Condition != nil && !evaluateCondition(*node.Condition, results) {
		node.Status = core.NodeSkipped
		appendTrace(tracesMu, nodeTraces, node, startedAt, "조건 미충족으로 스킵")
		return nil
	}

	input := resolveTemplate(node.InputTemplate, results)

	items, err := s.collectContext(ctx, node.Route, input, toolCallsMu, toolCalls)
	if err == nil {
		node.Status = core.NodeSuccess
		appendTrace(tracesMu, nodeTraces, node, startedAt, fmt.Sprintf("성공: %d개 항목", len(items)))
		return items
	}

	classification := s.Recovery.ClassifyFailure(err.Error(), node.Route)
	strategy := s.Recovery.DetermineStrategy(classification, node.Route, 0)

	node.Status = core.NodeFailed
	summary := err.Error()
	if len([]rune(summary)) > 50 {
		summary = string([]rune(summary)[:50])
	}
	appendTrace(tracesMu, nodeTraces, node, startedAt, "실패: "+summary)

	for _, fallbackRoute := range strategy.FallbackRoutes {
		fallbackItems, ferr := s.collectContext(ctx, fallbackRoute, input, toolCallsMu, toolCalls)
		if ferr == nil && len(fallbackItems) > 0 {
			return fallbackItems
		}
	}

	return nil
}

func appendTrace(mu *sync.Mutex, traces *[]core.NodeTrace, node *core.PlanNode, startedAt time.Time, summary string) {
	mu.Lock()
	defer mu.Unlock()
	*traces = append(*traces, core.NodeTrace{
		NodeID:        node.ID,
		Route:         node.Route,
		Status:        node.Status,
		StartedAt:     startedAt.UnixMilli(),
		EndedAt:       time.Now().UnixMilli(),
		ResultSummary: summary,
	})
}

func (s *AgentServiceV2) collectContext(ctx context.Context, route router.Route, question string, toolCallsMu *sync.Mutex, toolCalls *[]string) ([]agentcontext.ContextItem, error) {
	addToolCall := func(name string) {
		toolCallsMu.Lock()
		*toolCalls = append(*toolCalls, name)
		toolCallsMu.Unlock()
	}

	switch route {
	case router.RouteVector:
		addToolCall("vector_search")
		result, err := s.Gateway.VectorSearch(ctx, question, 4)
		if err != nil {
			return nil, err
		}
		return parseVectorResult(result), nil

	case router.RouteGraph:
		addToolCall("kg_search")
		result, err := s.Gateway.KgSearch(ctx, question)
		if err != nil {
			return nil, err
		}
		return []agentcontext.ContextItem{{Source: "knowledge-graph", Text: "지식 그래프 관계:\n" + result, Score: 0.9}}, nil

	case router.RouteSQL:
		addToolCall("get_schema")
		addToolCall("run_sql")
		sql, err := s.generateSQL(ctx, question, "", "")
		if err != nil {
			return nil, err
		}
		result, err := s.Gateway.RunSQL(ctx, sql)
		if err != nil {
			return nil, err
		}

		for retry := 0; retry < maxSQLRetries; retry++ {
			feedback := analyzeSQLResult(result, question)
			if feedback == "" {
				break
			}
			addToolCall(fmt.Sprintf("run_sql(retry-%d)", retry+1))
			sql, err = s.generateSQL(ctx, question, sql, feedback)
			if err != nil {
				return nil, err
			}
			result, err = s.Gateway.RunSQL(ctx, sql)
			if err != nil {
				return nil, err
			}
		}

		return []agentcontext.ContextItem{{
			Source: "sql",
			Text:   "실행한 SQL: " + sql + "\n조회 결과:\n" + humanizeSQLResult(result),
			Score:  1.0,
		}}, nil

	default:
		return nil, fmt.Errorf("unknown route: %s", route)
	}
}

func (s *AgentServiceV2) searchAdditional(ctx context.Context, question string, missingClaims []string, toolCalls *[]string) []agentcontext.ContextItem {
	var items []agentcontext.ContextItem
	var mu sync.Mutex

	claims := missingClaims
	if len(claims) > 2 {
		claims = claims[:2]
	}

	for _, claim := range claims {
		switch {
		case strings.Contains(claim, "entity_"):
			entity := strings.TrimPrefix(claim, "entity_")
			mu.Lock()
			*toolCalls = append(*toolCalls, "vector_search(additional)")
			mu.Unlock()
			result, err := s.Gateway.VectorSearch(ctx, entity, 4)
			if err == nil {
				items = append(items, parseVectorResult(result)...)
			}
		case claim == "quantity_value" || claim == "aggregated_value":
			mu.Lock()
			*toolCalls = append(*toolCalls, "run_sql(additional)")
			mu.Unlock()
			sql, err := s.generateSQL(ctx, question, "", "")
			if err == nil {
				result, rerr := s.Gateway.RunSQL(ctx, sql)
				if rerr == nil {
					items = append(items, agentcontext.ContextItem{Source: "sql", Text: humanizeSQLResult(result), Score: 0.8})
				}
			}
		default:
			mu.Lock()
			*toolCalls = append(*toolCalls, "vector_search(additional)")
			mu.Unlock()
			result, err := s.Gateway.VectorSearch(ctx, question, 4)
			if err == nil {
				items = append(items, parseVectorResult(result)...)
			}
		}
	}

	return items
}

var numericRe = regexp.MustCompile(`\d+`)

func estimateAnswerQuality(answer, question string) float64 {
	score := 0.5

	if len([]rune(answer)) >= 50 {
		score += 0.1
	}
	if len([]rune(answer)) >= 100 {
		score += 0.1
	}
	if strings.Contains(answer, "출처") || strings.Contains(answer, "[") {
		score += 0.1
	}
	if strings.Contains(answer, "찾을 수 없") || strings.Contains(answer, "알 수 없") {
		score -= 0.2
	}
	if (strings.Contains(question, "몇") || strings.Contains(question, "얼마")) && numericRe.MatchString(answer) {
		score += 0.15
	}

	if score < 0 {
		return 0
	}
	if score > 1 {
		return 1
	}
	return score
}

func (s *AgentServiceV2) declineResponse(question string, plan core.ExecutionPlan, started time.Time, nodeTraces []core.NodeTrace, toolCalls []string) AgentAnswerV2 {
	answer := fmt.Sprintf("죄송합니다. '%s'에 대해 충분한 정보를 찾지 못했습니다. 질문을 더 구체적으로 해주시거나 다른 방식으로 질문해 주세요.", question)
	duration := time.Since(started).Milliseconds()

	return AgentAnswerV2{
		Answer:         answer,
		Routes:         plan.Profile.SuggestedRoutes,
		ToolCalls:      toolCalls,
		ContextSources: nil,
		LatencyMs:      duration,
		Trace: &core.ExecutionTrace{
			Plan:            plan,
			NodeTraces:      nodeTraces,
			TotalDurationMs: duration,
			FinalAnswer:     answer,
			ClaimCoverage:   0.0,
		},
		SelectedModel: plan.SelectedModel,
		ClaimCoverage: 0.0,
		WasEscalated:  false,
	}
}

// === Helper functions ===

func groupByDependencyLevel(nodes []*core.PlanNode) [][]*core.PlanNode {
	var levels [][]*core.PlanNode
	remaining := append([]*core.PlanNode{}, nodes...)
	completed := make(map[string]bool)

	for len(remaining) > 0 {
		var currentLevel []*core.PlanNode
		for _, node := range remaining {
			allDepsCompleted := true
			for _, dep := range node.DependsOn {
				if !completed[dep] {
					allDepsCompleted = false
					break
				}
			}
			if allDepsCompleted {
				currentLevel = append(currentLevel, node)
			}
		}

		if len(currentLevel) == 0 {
			levels = append(levels, remaining)
			break
		}

		levels = append(levels, currentLevel)
		currentSet := make(map[string]bool, len(currentLevel))
		for _, n := range currentLevel {
			completed[n.ID] = true
			currentSet[n.ID] = true
		}

		var next []*core.PlanNode
		for _, n := range remaining {
			if !currentSet[n.ID] {
				next = append(next, n)
			}
		}
		remaining = next
	}

	return levels
}

func evaluateCondition(condition core.ExecutionCondition, results map[string]any) bool {
	value := results[condition.Variable]

	switch condition.Type {
	case core.ConditionNotEmpty:
		switch v := value.(type) {
		case nil:
			return false
		case []agentcontext.ContextItem:
			return len(v) > 0
		case string:
			return strings.TrimSpace(v) != ""
		default:
			return true
		}
	case core.ConditionOnFailure:
		return value == nil
	case core.ConditionAlways:
		return true
	default:
		return true
	}
}

var templateVarRe = regexp.MustCompile(`\$\{([^}]+)}`)

func resolveTemplate(template string, results map[string]any) string {
	return templateVarRe.ReplaceAllStringFunc(template, func(match string) string {
		varName := templateVarRe.FindStringSubmatch(match)[1]
		value, ok := results[varName]
		if !ok || value == nil {
			return ""
		}
		if items, ok := value.([]agentcontext.ContextItem); ok {
			parts := make([]string, len(items))
			for i, it := range items {
				text := it.Text
				if len([]rune(text)) > 50 {
					text = string([]rune(text)[:50])
				}
				parts[i] = text
			}
			return strings.Join(parts, ", ")
		}
		return fmt.Sprintf("%v", value)
	})
}

func (s *AgentServiceV2) generateSQL(ctx context.Context, question, previousAttempt, errorMsg string) (string, error) {
	if previousAttempt == "" && s.DeterministicSqlPlanner != nil {
		if plan := s.DeterministicSqlPlanner.Plan(question); plan != nil {
			return *plan, nil
		}
	}

	rawSchema, err := s.Gateway.Schema(ctx)
	if err != nil {
		return "", err
	}
	if s.Logger != nil {
		s.Logger.Info("raw schema debug", "rawSchema", rawSchema)
	}
	schema := s.SchemaPromptFormatter.Format(rawSchema)
	selectedExamples := s.FewShotSelector.SelectExamples(question, 1)
	examplesBlock := s.FewShotSelector.FormatExamplesForPrompt(selectedExamples)
	schemaHints := s.SchemaLinker.LinkEntities(rawSchema, question)
	hintBlock := s.SchemaLinker.FormatHintsForPrompt(schemaHints)

	retryBlock := ""
	if previousAttempt != "" {
		errSnippet := errorMsg
		if len([]rune(errSnippet)) > 300 {
			errSnippet = string([]rune(errSnippet)[:300])
		}
		retryBlock = fmt.Sprintf("직전 시도: %s\n오류: %s\n오류를 고쳐서 다시 작성한다.\n\n", previousAttempt, errSnippet)
	}

	system := "너는 PostgreSQL 전문가다. 주어진 스키마만 사용해서 질문에 답하는 " +
		"SELECT 문 한 문장만 출력한다. TABLE에 없는 식별자를 만들지 않고, " +
		"JOIN은 FOREIGN KEYS에 제시된 관계만 사용한다. " +
		"설명, 마크다운, 세미콜론 없이 SQL만 출력한다."
	if len(schemaHints) > 0 {
		system += " 스키마 힌트에 표시된 테이블.컬럼과 값을 반드시 사용한다."
	}

	user := fmt.Sprintf("스키마:\n%s%s\n\n%s\n\n%s질문: %s\nSQL:", schema, hintBlock, examplesBlock, retryBlock, question)

	if s.Logger != nil {
		s.Logger.Info("full prompt debug", "system", system, "user", user)
	}

	raw, err := s.LLM.Complete(ctx, system, user)
	if err != nil {
		return "", err
	}

	cleaned := regexp.MustCompile("(?i)```(sql)?").ReplaceAllString(raw, "")
	cleaned = strings.TrimSpace(cleaned)
	cleaned = strings.TrimSuffix(cleaned, ";")
	return cleaned, nil
}

func humanizeSQLResult(jsonStr string) string {
	var parsed map[string]any
	if err := json.Unmarshal([]byte(jsonStr), &parsed); err != nil {
		return jsonStr
	}
	rowsRaw, ok := parsed["rows"].([]any)
	if !ok {
		return jsonStr
	}
	if len(rowsRaw) == 0 {
		return "조회 결과 없음 (0행)"
	}
	lines := make([]string, len(rowsRaw))
	for i, r := range rowsRaw {
		row, ok := r.(map[string]any)
		if !ok {
			continue
		}
		var parts []string
		for k, v := range row {
			parts = append(parts, fmt.Sprintf("%s=%v", k, v))
		}
		lines[i] = strings.Join(parts, ", ")
	}
	return strings.Join(lines, "\n")
}

var negationKeywords = []string{"없", "아닌", "제외", "빼고", "않"}

func analyzeSQLResult(jsonStr, question string) string {
	var parsed map[string]any
	if err := json.Unmarshal([]byte(jsonStr), &parsed); err != nil {
		return "SQL 결과 파싱 실패: " + err.Error()
	}

	if errVal, ok := parsed["error"]; ok {
		if errStr := fmt.Sprintf("%v", errVal); errStr != "" && errStr != "<nil>" {
			return "SQL 실행 오류: " + errStr
		}
	}

	rowsRaw, ok := parsed["rows"].([]any)
	if !ok {
		return "응답에 rows 필드가 없음"
	}
	if len(rowsRaw) == 0 {
		for _, kw := range negationKeywords {
			if strings.Contains(question, kw) {
				return ""
			}
		}
		return "조회 결과가 0행임"
	}
	return ""
}

func parseVectorResult(jsonStr string) []agentcontext.ContextItem {
	var docs []map[string]any
	if err := json.Unmarshal([]byte(jsonStr), &docs); err != nil {
		return []agentcontext.ContextItem{{Source: "document", Text: jsonStr, Score: 0.5}}
	}
	items := make([]agentcontext.ContextItem, 0, len(docs))
	for _, d := range docs {
		source := "document"
		if s, ok := d["source"].(string); ok {
			source = s
		}
		text := ""
		if t, ok := d["text"].(string); ok {
			text = t
		}
		score := 0.5
		if sc, ok := d["score"].(float64); ok {
			score = sc
		}
		items = append(items, agentcontext.ContextItem{Source: source, Text: text, Score: score})
	}
	return items
}

func (s *AgentServiceV2) generateAnswer(ctx context.Context, question string, evidence []agentcontext.ContextItem, model string) string {
	contextBlock := "(검색된 컨텍스트 없음)"
	if len(evidence) > 0 {
		parts := make([]string, len(evidence))
		for i, e := range evidence {
			parts[i] = fmt.Sprintf("[출처: %s]\n%s", e.Source, e.Text)
		}
		contextBlock = strings.Join(parts, "\n\n")
	}

	if s.Logger != nil {
		s.Logger.Debug("답변 생성", "model", model, "context_size", len(evidence))
	}

	system := "너는 리원에이스의 데이터 플랫폼 AI 비서다. " +
		"아래 컨텍스트에서 질문과 관련된 정보를 찾아 한국어로 답한다. " +
		"컨텍스트에 장애 보고서, 기술 문서, 회의록 등이 있으면 핵심 내용(고객사, 제품, 원인, 조치사항 등)을 요약한다. " +
		"컨텍스트가 여러 출처([출처: sql], [출처: knowledge-graph] 등)로 나뉘어 있으면, " +
		"질문의 엔티티(고객사/제품/부서/이름 등)와 실제로 일치하는 출처만 사용하고 " +
		"무관한 출처의 결과는 답변에 섞지 않는다. " +
		"답변 끝에 출처를 표기한다."
	user := "컨텍스트:\n" + contextBlock + "\n\n질문: " + question

	answer, err := s.LLM.Complete(ctx, system, user)
	if err != nil {
		return ""
	}
	return answer
}
