package sql

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// SchemaPromptFormatter ports SchemaPromptFormatter.kt 1:1: turns db://schema JSON into
// a compact TABLE/FOREIGN KEYS/KNOWN COLUMN VALUES/RULES textual block.
//
// Kotlin's Jackson JsonNode preserves object-key insertion order (the order the MCP
// server wrote them in), and that ordering measurably affects the small model's SQL
// generation (verified: reordering "KNOWN COLUMN VALUES" changed whether gemma3:4b added
// a spurious status='active' filter to an unrelated question). Go's map[string]any has no
// stable iteration order, so orderedObject below walks the raw JSON token stream to recover
// the original key order instead of unmarshalling into a map.
type SchemaPromptFormatter struct{}

func (f *SchemaPromptFormatter) Format(rawSchema string) string {
	root, err := parseOrderedObject(rawSchema)
	if err != nil {
		return rawSchema
	}

	tablesRaw, ok := root.get("tables")
	if !ok {
		return rawSchema
	}
	tables, ok := tablesRaw.(*orderedObject)
	if !ok {
		return rawSchema
	}

	var sb strings.Builder
	sb.WriteString("DATABASE SCHEMA\n")

	for _, table := range tables.keys {
		columns, _ := tables.values[table].([]any)
		colStrs := make([]string, len(columns))
		for i, c := range columns {
			colStrs[i] = fmt.Sprintf("%v", c)
		}
		sb.WriteString("TABLE " + table + " (" + strings.Join(colStrs, ", ") + ")\n")
	}

	if fksRaw, ok := root.get("foreignKeys"); ok {
		if fks, ok := fksRaw.([]any); ok && len(fks) > 0 {
			sb.WriteString("FOREIGN KEYS\n")
			for _, fk := range fks {
				sb.WriteString(fmt.Sprintf("- %v\n", fk))
			}
		}
	}

	if vhRaw, ok := root.get("valueHints"); ok {
		if valueHints, ok := vhRaw.(*orderedObject); ok && len(valueHints.keys) > 0 {
			sb.WriteString("KNOWN COLUMN VALUES (SQL 식별자가 아닌 실제 데이터 값)\n")
			for _, column := range valueHints.keys {
				values, _ := valueHints.values[column].([]any)
				escaped := make([]string, len(values))
				for i, v := range values {
					escaped[i] = "'" + strings.ReplaceAll(fmt.Sprintf("%v", v), "'", "''") + "'"
				}
				sb.WriteString("- " + column + " = [" + strings.Join(escaped, ", ") + "]\n")
			}
		}
	}

	sb.WriteString("RULES\n")
	sb.WriteString("- TABLE에 선언된 테이블과 컬럼만 SQL 식별자로 사용한다.\n")
	sb.WriteString("- KNOWN COLUMN VALUES의 값은 해당 컬럼의 비교값으로만 사용한다.\n")
	sb.WriteString("- JOIN은 FOREIGN KEYS에 선언된 관계만 사용한다.")

	return sb.String()
}

// orderedObject is a JSON object that remembers its key insertion order, unlike
// map[string]any.
type orderedObject struct {
	keys   []string
	values map[string]any
}

func (o *orderedObject) get(key string) (any, bool) {
	v, ok := o.values[key]
	return v, ok
}

func parseOrderedObject(raw string) (*orderedObject, error) {
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	v, err := decodeOrderedValue(dec)
	if err != nil {
		return nil, err
	}
	obj, ok := v.(*orderedObject)
	if !ok {
		return nil, fmt.Errorf("root is not a JSON object")
	}
	return obj, nil
}

// decodeOrderedValue reads one JSON value from dec, preserving object key order via
// orderedObject instead of Go's unordered map.
func decodeOrderedValue(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	return decodeOrderedValueFromToken(dec, tok)
}

func decodeOrderedValueFromToken(dec *json.Decoder, tok json.Token) (any, error) {
	switch t := tok.(type) {
	case json.Delim:
		switch t {
		case '{':
			obj := &orderedObject{values: map[string]any{}}
			for dec.More() {
				keyTok, err := dec.Token()
				if err != nil {
					return nil, err
				}
				key, ok := keyTok.(string)
				if !ok {
					return nil, fmt.Errorf("expected string key, got %v", keyTok)
				}
				val, err := decodeOrderedValue(dec)
				if err != nil {
					return nil, err
				}
				obj.keys = append(obj.keys, key)
				obj.values[key] = val
			}
			if _, err := dec.Token(); err != nil { // consume '}'
				return nil, err
			}
			return obj, nil
		case '[':
			var arr []any
			for dec.More() {
				val, err := decodeOrderedValue(dec)
				if err != nil {
					return nil, err
				}
				arr = append(arr, val)
			}
			if _, err := dec.Token(); err != nil { // consume ']'
				return nil, err
			}
			return arr, nil
		}
	}
	return tok, nil
}
