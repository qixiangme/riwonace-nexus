package sql

import (
	"strings"
	"testing"
)

func TestSchemaPromptFormatter_SeparatesTableForeignKeyValueHintSemanticAreas(t *testing.T) {
	f := &SchemaPromptFormatter{}
	raw := `{
		"tables": {
			"clients": ["id (integer)", "region (character varying)"],
			"sales": ["client_id (integer)", "amount (integer)"]
		},
		"foreignKeys": ["sales.client_id -> clients.id"],
		"valueHints": {"clients.region": ["서울", "경기"]}
	}`

	result := f.Format(raw)

	if !strings.Contains(result, "TABLE clients (id (integer), region (character varying))") {
		t.Fatalf("got %q", result)
	}
	if !strings.Contains(result, "sales.client_id -> clients.id") {
		t.Fatalf("got %q", result)
	}
	if !strings.Contains(result, "clients.region = ['서울', '경기']") {
		t.Fatalf("got %q", result)
	}
	if !strings.Contains(result, "SQL 식별자가 아닌 실제 데이터 값") {
		t.Fatalf("got %q", result)
	}
	if strings.Contains(result, "TABLE valueHints") {
		t.Fatalf("got %q", result)
	}
}

func TestSchemaPromptFormatter_SingleQuoteValuesEscapedAsSqlLiterals(t *testing.T) {
	f := &SchemaPromptFormatter{}
	raw := `{"tables":{"clients":["name (varchar)"]},"valueHints":{"clients.name":["O'Reilly"]}}`
	result := f.Format(raw)
	if !strings.Contains(result, "'O''Reilly'") {
		t.Fatalf("got %q", result)
	}
}

func TestSchemaPromptFormatter_NonJsonResponseFromOtherMcpImplFallsBackToRaw(t *testing.T) {
	f := &SchemaPromptFormatter{}
	raw := "TABLE clients(id integer)"
	if got := f.Format(raw); got != raw {
		t.Fatalf("got %q", got)
	}
}

// Kotlin's Jackson JsonNode preserves object-key insertion order; verified end-to-end that
// reordering this section (e.g. alphabetically) changes what SQL gemma3:4b generates for an
// unrelated question, spuriously adding a status='active' filter. Go's map[string]any has no
// stable order, so Format must walk the raw JSON token stream instead of unmarshalling into a
// map, or this ordering guarantee silently regresses.
func TestSchemaPromptFormatter_PreservesOriginalKeyOrderNotAlphabetical(t *testing.T) {
	f := &SchemaPromptFormatter{}
	raw := `{
		"tables": {"zzz_table": ["id (integer)"], "aaa_table": ["id (integer)"]},
		"valueHints": {
			"clients.industry": ["미디어"],
			"clients.company_size": ["mid"],
			"contracts.status": ["active"]
		}
	}`
	result := f.Format(raw)

	tableOrder := strings.Index(result, "TABLE zzz_table")
	otherTableOrder := strings.Index(result, "TABLE aaa_table")
	if tableOrder == -1 || otherTableOrder == -1 || tableOrder > otherTableOrder {
		t.Fatalf("expected zzz_table before aaa_table (source order), got %q", result)
	}

	industryIdx := strings.Index(result, "clients.industry")
	sizeIdx := strings.Index(result, "clients.company_size")
	statusIdx := strings.Index(result, "contracts.status")
	if industryIdx == -1 || sizeIdx == -1 || statusIdx == -1 {
		t.Fatalf("expected all three valueHints columns present, got %q", result)
	}
	if !(industryIdx < sizeIdx && sizeIdx < statusIdx) {
		t.Fatalf("expected valueHints in source order (industry, company_size, status), got %q", result)
	}
}
