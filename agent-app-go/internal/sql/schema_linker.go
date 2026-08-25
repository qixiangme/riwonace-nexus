package sql

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
)

// SchemaHint mirrors data class SchemaHint.
type SchemaHint struct {
	Table        string
	Column       string
	MatchedValue string
	Suggestion   string
	Confidence   float64
}

var koreanWordRe = regexp.MustCompile(`[\x{AC00}-\x{D7A3}]{2,}`)

// SchemaLinker ports SchemaLinker.kt 1:1: matches db://schema valueHints against the
// question for NL2SQL grounding.
type SchemaLinker struct{}

func (l *SchemaLinker) LinkEntities(schemaJSON, question string) []SchemaHint {
	var hints []SchemaHint

	schema, err := parseOrderedObject(schemaJSON)
	if err != nil {
		return nil
	}

	valueHintsRaw, ok := schema.get("valueHints")
	if !ok {
		return nil
	}
	valueHints, ok := valueHintsRaw.(*orderedObject)
	if !ok {
		return nil
	}

	for _, qualifiedColumn := range valueHints.keys {
		rawValues, _ := valueHints.values[qualifiedColumn].([]any)
		sep := strings.Index(qualifiedColumn, ".")
		if sep <= 0 || sep == len(qualifiedColumn)-1 {
			continue
		}
		tableName := qualifiedColumn[:sep]
		columnName := qualifiedColumn[sep+1:]

		for _, rv := range rawValues {
			hint := fmt.Sprintf("%v", rv)
			if rv == nil {
				continue
			}
			exact := containsIgnoreCase(question, hint)
			var partial string
			if !exact {
				partial = findPartialMatch(question, hint)
			}
			if exact || partial != "" {
				confidence := 0.8
				if exact {
					confidence = 1.0
				}
				hints = append(hints, SchemaHint{
					Table:        tableName,
					Column:       columnName,
					MatchedValue: hint,
					Suggestion:   fmt.Sprintf("%s.%s = '%s'", tableName, columnName, strings.ReplaceAll(hint, "'", "''")),
					Confidence:   confidence,
				})
			}
		}
	}

	sort.SliceStable(hints, func(i, j int) bool { return hints[i].Confidence > hints[j].Confidence })
	return hints
}

func containsIgnoreCase(s, substr string) bool {
	return strings.Contains(strings.ToLower(s), strings.ToLower(substr))
}

func findPartialMatch(question, hint string) string {
	for _, word := range koreanWordRe.FindAllString(hint, -1) {
		if strings.Contains(question, word) {
			return word
		}
	}
	return ""
}

func (l *SchemaLinker) FormatHintsForPrompt(hints []SchemaHint) string {
	if len(hints) == 0 {
		return ""
	}

	var high, low []SchemaHint
	for _, h := range hints {
		if h.Confidence >= 0.9 {
			high = append(high, h)
		} else {
			low = append(low, h)
		}
	}

	var sb strings.Builder
	sb.WriteString("\n\n[스키마 힌트 - 질문에서 발견된 값]\n")

	if len(high) > 0 {
		sb.WriteString("확실: ")
		suggestions := make([]string, len(high))
		for i, h := range high {
			suggestions[i] = h.Suggestion
		}
		sb.WriteString(strings.Join(suggestions, ", "))
		sb.WriteString("\n")
	}

	if len(low) > 0 {
		sb.WriteString("참고: ")
		parts := make([]string, len(low))
		for i, h := range low {
			parts[i] = fmt.Sprintf("'%s'이 %s.%s에 있음", h.MatchedValue, h.Table, h.Column)
		}
		sb.WriteString(strings.Join(parts, ", "))
		sb.WriteString("\n")
	}

	return sb.String()
}
