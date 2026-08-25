package resources

import (
	"encoding/json"
	"strings"
	"testing"
)

// groupColumnsByTable and fetchValueHints must preserve query result order (table then
// ordinal_position) on the JSON wire, mirroring Kotlin's groupBy/linkedMapOf. This order is
// load-bearing for NL2SQL accuracy in agent-app-go's SchemaPromptFormatter -- verified
// end-to-end that reordering it changes what SQL a small model generates for an unrelated
// question. encoding/json alphabetizes plain map[string]V keys, so these must round-trip
// through orderedMap instead.

func TestGroupColumnsByTable_PreservesFirstSeenTableOrderNotAlphabetical(t *testing.T) {
	columns := []columnRow{
		{Table: "zzz_table", Column: "id", DataType: "integer"},
		{Table: "aaa_table", Column: "id", DataType: "integer"},
		{Table: "zzz_table", Column: "name", DataType: "character varying"},
	}

	tables := groupColumnsByTable(columns)

	if len(tables.keys) != 2 || tables.keys[0] != "zzz_table" || tables.keys[1] != "aaa_table" {
		t.Fatalf("expected [zzz_table, aaa_table] in encounter order, got %v", tables.keys)
	}

	zzzCols, _ := tables.values["zzz_table"].([]string)
	if len(zzzCols) != 2 || zzzCols[0] != "id (integer)" || zzzCols[1] != "name (character varying)" {
		t.Fatalf("expected zzz_table columns in ordinal order, got %v", zzzCols)
	}
}

func TestOrderedMap_MarshalsKeysInInsertionOrderNotAlphabetical(t *testing.T) {
	m := newOrderedMap()
	m.set("clients.industry", []string{"미디어"})
	m.set("clients.company_size", []string{"mid"})
	m.set("contracts.status", []string{"active"})

	b, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	out := string(b)

	industryIdx := strings.Index(out, "clients.industry")
	sizeIdx := strings.Index(out, "clients.company_size")
	statusIdx := strings.Index(out, "contracts.status")
	if industryIdx == -1 || sizeIdx == -1 || statusIdx == -1 {
		t.Fatalf("expected all three keys present, got %s", out)
	}
	if !(industryIdx < sizeIdx && sizeIdx < statusIdx) {
		t.Fatalf("expected insertion order (industry, company_size, status), got %s", out)
	}
}

func TestOrderedMap_RoundTripsThroughStandardJSONUnmarshal(t *testing.T) {
	m := newOrderedMap()
	m.set("a", 1)
	m.set("b", []string{"x", "y"})

	b, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}

	var decoded map[string]any
	if err := json.Unmarshal(b, &decoded); err != nil {
		t.Fatalf("unmarshal failed: %v", err)
	}
	if decoded["a"].(float64) != 1 {
		t.Fatalf("got %v", decoded["a"])
	}
}
