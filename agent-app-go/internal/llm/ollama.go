// Package llm ports the Spring AI ChatClient usage scattered across agent-app's
// service/core classes into a single minimal Ollama chat client.
package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

// ChatClient calls Ollama's /api/chat with a system+user message pair, matching the
// shape of every chatClient.prompt().system(...).user(...).call().content() call site
// in the Kotlin baseline. temperature=0.0/num-ctx=4096/max-tokens=512 mirror
// application.yml's spring.ai.ollama.chat.options.
type ChatClient struct {
	BaseURL     string
	Model       string
	Temperature float64
	NumCtx      int
	MaxTokens   int
	Client      *http.Client
}

type ollamaChatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type ollamaChatRequest struct {
	Model    string              `json:"model"`
	Messages []ollamaChatMessage `json:"messages"`
	Stream   bool                `json:"stream"`
	Options  ollamaChatOptions   `json:"options"`
}

type ollamaChatOptions struct {
	Temperature float64 `json:"temperature"`
	NumCtx      int     `json:"num_ctx"`
	NumPredict  int     `json:"num_predict"`
}

type ollamaChatResponse struct {
	Message ollamaChatMessage `json:"message"`
}

// Complete mirrors chatClient.prompt().system(system).user(user).call().content().
func (c *ChatClient) Complete(ctx context.Context, system, user string) (string, error) {
	reqBody := ollamaChatRequest{
		Model: c.Model,
		Messages: []ollamaChatMessage{
			{Role: "system", Content: system},
			{Role: "user", Content: user},
		},
		Stream: false,
		Options: ollamaChatOptions{
			Temperature: c.Temperature,
			NumCtx:      c.NumCtx,
			NumPredict:  c.MaxTokens,
		},
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")

	client := c.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("ollama chat request failed: status %d", resp.StatusCode)
	}

	var out ollamaChatResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	return out.Message.Content, nil
}

// Warmup mirrors ModelWarmup.kt: fire a minimal request to force the model to load.
func (c *ChatClient) Warmup(ctx context.Context) {
	body, _ := json.Marshal(ollamaChatRequest{
		Model:    c.Model,
		Messages: []ollamaChatMessage{{Role: "user", Content: "hi"}},
		Stream:   false,
		Options:  ollamaChatOptions{NumPredict: 1},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/chat", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	client := c.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err == nil {
		resp.Body.Close()
	}
}
