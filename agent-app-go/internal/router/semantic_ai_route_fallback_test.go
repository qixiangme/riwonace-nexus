package router

import (
	"context"
	"errors"
	"reflect"
	"testing"
)

// Test names mirror SemanticAiRouteFallbackTest.kt cases 1:1.

type stubChat struct {
	response string
	err      error
}

func (s stubChat) Complete(ctx context.Context, system, user string) (string, error) {
	return s.response, s.err
}

func TestSemanticAiRouteFallback_SingleRouteParses(t *testing.T) {
	f := &SemanticAiRouteFallback{Chat: stubChat{response: "GRAPH"}}
	got := f.Classify("이 부서에 속한 담당자는 누구인가")
	if !reflect.DeepEqual(got, []Route{RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

func TestSemanticAiRouteFallback_MultipleRoutesParseInOrderDeduped(t *testing.T) {
	f := &SemanticAiRouteFallback{Chat: stubChat{response: "sql,graph,sql"}}
	got := f.Classify("월 가격과 실제 이용 고객을 함께 알려줘")
	if !reflect.DeepEqual(got, []Route{RouteSQL, RouteGraph}) {
		t.Fatalf("got %v", got)
	}
}

func TestSemanticAiRouteFallback_UnparsableOutputDefaultsToVector(t *testing.T) {
	f := &SemanticAiRouteFallback{Chat: stubChat{response: "이 질문은 GRAPH 입니다"}}
	got := f.Classify("아무 질문")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestSemanticAiRouteFallback_ChatErrorDefaultsToVector(t *testing.T) {
	f := &SemanticAiRouteFallback{Chat: stubChat{err: errors.New("connection refused")}}
	got := f.Classify("아무 질문")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestSemanticAiRouteFallback_EmptyOutputDefaultsToVector(t *testing.T) {
	f := &SemanticAiRouteFallback{Chat: stubChat{response: ""}}
	got := f.Classify("아무 질문")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
}

func TestSemanticAiRouteFallback_PromptSubstitutesQuestion(t *testing.T) {
	got := parseExactRoutes("VECTOR")
	if !reflect.DeepEqual(got, []Route{RouteVector}) {
		t.Fatalf("got %v", got)
	}
	if parseExactRoutes("") != nil {
		t.Fatal("expected nil for empty raw output")
	}
	if parseExactRoutes("SQL,") != nil {
		t.Fatal("expected nil for trailing comma with empty label")
	}
}
