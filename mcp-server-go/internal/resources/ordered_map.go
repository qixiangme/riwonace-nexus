package resources

import (
	"bytes"
	"encoding/json"
)

// orderedMap is a JSON object that marshals its keys in insertion order instead of Go's
// default alphabetical map-key sort (encoding/json sorts map[string]V keys before
// marshalling). Kotlin's LinkedHashMap / groupBy preserve encounter order, and that order
// is load-bearing for NL2SQL prompt accuracy: reordering "tables" or "valueHints"
// measurably changes what SQL a small model generates for an unrelated question (verified
// end-to-end -- see agent-app-go's schema_prompt_formatter_test.go for the regression this
// guards against).
type orderedMap struct {
	keys   []string
	values map[string]any
}

func newOrderedMap() *orderedMap {
	return &orderedMap{values: map[string]any{}}
}

func (m *orderedMap) set(key string, value any) {
	if _, exists := m.values[key]; !exists {
		m.keys = append(m.keys, key)
	}
	m.values[key] = value
}

func (m *orderedMap) MarshalJSON() ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteByte('{')
	for i, k := range m.keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		keyBytes, err := json.Marshal(k)
		if err != nil {
			return nil, err
		}
		buf.Write(keyBytes)
		buf.WriteByte(':')
		valBytes, err := json.Marshal(m.values[k])
		if err != nil {
			return nil, err
		}
		buf.Write(valBytes)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}
