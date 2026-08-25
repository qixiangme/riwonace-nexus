package sql

import "testing"

func TestSchemaLinker_LinksQuestionValuesFromDbSchemaValueHintsContract(t *testing.T) {
	linker := &SchemaLinker{}
	schema := `{
		"tables": {"departments": ["name (character varying)"]},
		"valueHints": {"departments.name": ["플랫폼팀", "기술지원팀"]}
	}`

	hints := linker.LinkEntities(schema, "기술지원팀 직원 목록")

	if len(hints) != 1 {
		t.Fatalf("got %+v", hints)
	}
	if hints[0].Suggestion != "departments.name = '기술지원팀'" {
		t.Fatalf("got %q", hints[0].Suggestion)
	}
}

// Same-confidence hints must keep the source valueHints key order (Kotlin's LinkedHashMap
// behavior), not Go's unordered map iteration -- see the analogous test in
// schema_prompt_formatter_test.go for why this ordering is load-bearing for NL2SQL accuracy.
func TestSchemaLinker_SameConfidenceHintsPreserveSourceKeyOrder(t *testing.T) {
	linker := &SchemaLinker{}
	schema := `{
		"tables": {},
		"valueHints": {
			"clients.industry": ["미디어"],
			"clients.company_size": ["엔터프라이즈"],
			"contracts.status": ["활성"]
		}
	}`

	hints := linker.LinkEntities(schema, "미디어 엔터프라이즈 활성")

	if len(hints) != 3 {
		t.Fatalf("expected 3 exact-match hints, got %+v", hints)
	}
	if hints[0].Column != "industry" || hints[1].Column != "company_size" || hints[2].Column != "status" {
		t.Fatalf("expected source order (industry, company_size, status), got %+v", hints)
	}
}
