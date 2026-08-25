// Command agent-app-go is a 1:1 port of agent-app (Kotlin/Spring AI) to Go.
// It ports the AgentServiceV2 (Architecture v2) orchestration path only: profiling ->
// execution DAG -> evidence optimization -> answerability gate -> Ollama answer
// generation, talking to an MCP server (mcp-server or mcp-server-go) over SSE.
//
// Routing, NL2SQL, answerability, evidence optimization, and recovery logic are ported
// unchanged from agent-app/src/main/kotlin/com/riwonace/agent -- see internal/router,
// internal/sql, internal/core for the per-component mapping.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"strconv"

	sdkmcp "github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/riwonace/agent-app-go/internal/api"
	"github.com/riwonace/agent-app-go/internal/core"
	"github.com/riwonace/agent-app-go/internal/llm"
	agentmcp "github.com/riwonace/agent-app-go/internal/mcp"
	"github.com/riwonace/agent-app-go/internal/router"
	"github.com/riwonace/agent-app-go/internal/service"
	agentsql "github.com/riwonace/agent-app-go/internal/sql"
)

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))

	port := getenv("SERVER_PORT", "8080")
	mcpServerURL := getenv("MCP_SERVER_URL", "http://localhost:8081")
	ollamaBaseURL := getenv("OLLAMA_BASE_URL", "http://localhost:11434")
	ollamaModel := getenv("OLLAMA_MODEL", "gemma4:e2b-it-qat")
	// Gemma 4 E2B는 reasoning 모델이라 thinking에 토큰을 먼저 소모한다 —
	// 512로는 thinking만 하다 답변 전에 잘려 1024로 올렸다(실측으로 확인).
	maxTokens := 1024
	if v, err := strconv.Atoi(getenv("OLLAMA_MAX_TOKENS", "1024")); err == nil {
		maxTokens = v
	}

	ctx := context.Background()

	transport := &sdkmcp.SSEClientTransport{Endpoint: mcpServerURL}
	client := sdkmcp.NewClient(&sdkmcp.Implementation{Name: "agent-app-go", Version: "1.0.0"}, nil)
	session, err := client.Connect(ctx, transport, nil)
	if err != nil {
		logger.Error("failed to connect to MCP server", "error", err, "url", mcpServerURL)
		os.Exit(1)
	}
	defer session.Close()

	gateway := agentmcp.NewGateway(session)

	chatClient := &llm.ChatClient{
		BaseURL:     ollamaBaseURL,
		Model:       ollamaModel,
		Temperature: 0.0,
		NumCtx:      4096,
		MaxTokens:   maxTokens,
		Seed:        42,
	}
	go chatClient.Warmup(context.Background())

	ruleRouter := &router.RuleBasedRouter{}
	if getenv("ROUTER_FALLBACK", "") == "semantic-ai" {
		ruleRouter.Fallback = &router.SemanticAiRouteFallback{Chat: chatClient, Logger: logger}
	}
	profiler := &core.QueryProfiler{Router: ruleRouter}
	// Gemma 4 E2B 단일 모델 실측(302문항)에서 에스컬레이션 없이 답변 정확도 93.0%를
	// 냈고, gemma3:1b 세대에서 에스컬레이션으로 보완하던 격차(73.8%)가 모델 자체
	// 성능으로 해소되어 기본값을 off로 바꿨다. 더 작은 모델로 되돌릴 계획이면
	// ESCALATION_ENABLED=true로 켜는 것을 권장한다.
	escalator := &core.ModelEscalator{
		Enabled:     getenv("MODEL_ESCALATION_ENABLED", "false") == "true",
		SmallModel:  getenv("ESCALATION_SMALL_MODEL", "gemma4:e2b-it-qat"),
		MediumModel: getenv("ESCALATION_MEDIUM_MODEL", "qwen2.5:3b"),
		LargeModel:  getenv("ESCALATION_LARGE_MODEL", "qwen2.5:7b"),
	}
	planner := &core.ExecutionPlanner{Profiler: profiler, Escalator: escalator}
	optimizer := &core.EvidenceOptimizer{BudgetChars: 2400}
	gate := &core.AnswerabilityGate{CoverageThreshold: 0.7, UseLLM: false}
	recovery := &core.RecoveryPolicy{}

	agentService := &service.AgentServiceV2{
		Planner:                 planner,
		Optimizer:               optimizer,
		Gate:                    gate,
		Recovery:                recovery,
		Escalator:               escalator,
		Gateway:                 gateway,
		LLM:                     chatClient,
		FewShotSelector:         &agentsql.FewShotSelector{},
		SchemaLinker:            &agentsql.SchemaLinker{},
		SchemaPromptFormatter:   &agentsql.SchemaPromptFormatter{},
		DeterministicSqlPlanner: &agentsql.DeterministicSqlPlanner{},
		Logger:                  logger,
	}

	controller := &api.ChatController{AgentServiceV2: agentService, Gateway: gateway}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/chat", controller.HandleChat)
	mux.HandleFunc("POST /api/chat/v2", controller.HandleChatV2)
	mux.HandleFunc("GET /api/tools", controller.HandleTools)
	mux.HandleFunc("GET /api/v2/status", controller.HandleV2Status)

	logger.Info("agent-app-go listening", "port", port, "mcpServerUrl", mcpServerURL)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		logger.Error("server exited", "error", err)
		os.Exit(1)
	}
}
