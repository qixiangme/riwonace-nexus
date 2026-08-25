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
	ollamaModel := getenv("OLLAMA_MODEL", "gemma3:1b")

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
		MaxTokens:   512,
		Seed:        42,
	}
	go chatClient.Warmup(context.Background())

	ruleRouter := &router.RuleBasedRouter{}
	if getenv("ROUTER_FALLBACK", "") == "semantic-ai" {
		ruleRouter.Fallback = &router.SemanticAiRouteFallback{Chat: chatClient, Logger: logger}
	}
	profiler := &core.QueryProfiler{Router: ruleRouter}
	escalator := &core.ModelEscalator{
		Enabled:     true,
		SmallModel:  getenv("ESCALATION_SMALL_MODEL", "gemma3:1b"),
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
