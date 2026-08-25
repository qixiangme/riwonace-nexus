package router

import (
	"context"
	_ "embed"
	"log/slog"
	"strings"
)

//go:embed semantic_ai_prompt.txt
var semanticAiPromptTemplate string

// ChatCompleter mirrors the subset of Spring AI's ChatClient that
// SemanticAiRouteFallback.kt uses: chatClient.prompt().user(...).call().content().
type ChatCompleter interface {
	Complete(ctx context.Context, system, user string) (string, error)
}

// SemanticAiRouteFallback ports router/SemanticAiRouteFallback.kt 1:1: an LLM-based
// fallback classifier used when RuleBasedRouter finds no deterministic keyword match.
// Enabled only when main.go wires it in behind ROUTER_FALLBACK=semantic-ai, mirroring
// the Kotlin @ConditionalOnProperty(name = ["agent.router.fallback"], havingValue = "semantic-ai").
type SemanticAiRouteFallback struct {
	Chat   ChatCompleter
	Logger *slog.Logger
}

// Classify mirrors fun classify(question: String): List<Route>.
func (f *SemanticAiRouteFallback) Classify(question string) []Route {
	prompt := strings.ReplaceAll(semanticAiPromptTemplate, "{{question}}", question)

	raw, err := f.Chat.Complete(context.Background(), "", prompt)
	if err != nil {
		if f.Logger != nil {
			f.Logger.Warn("의미 라우팅 호출 실패, VECTOR로 대체", "error", err)
		}
		raw = ""
	}

	resolved := parseExactRoutes(raw)
	if resolved == nil {
		if f.Logger != nil {
			preview := raw
			if len(preview) > 100 {
				preview = preview[:100]
			}
			f.Logger.Warn("의미 라우팅 출력 파싱 실패, VECTOR로 대체", "raw", preview)
		}
		return []Route{RouteVector}
	}
	return resolved
}

var allRoutes = []Route{RouteSQL, RouteVector, RouteGraph}

// parseExactRoutes mirrors internal fun parseExactRoutes(raw: String): List<Route>?.
func parseExactRoutes(raw string) []Route {
	labels := strings.Split(strings.ToUpper(strings.TrimSpace(raw)), ",")
	if len(labels) == 0 {
		return nil
	}
	var routes []Route
	for _, label := range labels {
		label = strings.TrimSpace(label)
		if label == "" {
			return nil
		}
		var match Route
		found := false
		for _, r := range allRoutes {
			if string(r) == label {
				match = r
				found = true
				break
			}
		}
		if !found {
			return nil
		}
		routes = append(routes, match)
	}

	deduped := dedupRoutes(routes)
	if len(deduped) == 0 {
		return nil
	}
	return deduped
}
