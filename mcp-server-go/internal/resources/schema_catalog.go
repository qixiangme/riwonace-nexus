// Package resources ports com.riwonace.mcp.resources from the Kotlin/Spring AI baseline.
package resources

import (
	"context"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	// MaxSchemaOutputChars mirrors JdbcSchemaCatalog.MAX_SCHEMA_OUTPUT_CHARS.
	MaxSchemaOutputChars = 8000
	// MaxHintValues mirrors JdbcSchemaCatalog.MAX_HINT_VALUES.
	MaxHintValues = 12
)

type columnRow struct {
	Table    string
	Column   string
	DataType string
}

// BuildSchemaSnapshot ports JdbcSchemaCatalog.buildSnapshot() 1:1: tables (grouped,
// ordered by table then ordinal position), foreignKeys, and low-cardinality valueHints.
//
// Both "tables" and "valueHints" are orderedMap, not map[string]any: encoding/json sorts
// map[string]V keys alphabetically on marshal, but Kotlin's groupBy/linkedMapOf preserve
// query result order (table then ordinal_position). That order measurably affects NL2SQL
// accuracy for the small model reading this schema, so it must survive to the JSON wire
// format, not just within this function.
func BuildSchemaSnapshot(ctx context.Context, pool *pgxpool.Pool) (map[string]any, error) {
	columns, err := fetchColumns(ctx, pool)
	if err != nil {
		return nil, err
	}

	tables := groupColumnsByTable(columns)

	fks, err := fetchForeignKeys(ctx, pool)
	if err != nil {
		return nil, err
	}

	hints, err := fetchValueHints(ctx, pool, columns)
	if err != nil {
		return nil, err
	}

	return map[string]any{
		"tables":      tables,
		"foreignKeys": fks,
		"valueHints":  hints,
	}, nil
}

func fetchColumns(ctx context.Context, pool *pgxpool.Pool) ([]columnRow, error) {
	rows, err := pool.Query(ctx, `
		SELECT table_name, column_name, data_type
		FROM information_schema.columns
		WHERE table_schema = 'public'
		  AND table_name NOT IN ('vector_store', 'kg_triples', 'document_chunks')
		ORDER BY table_name, ordinal_position
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []columnRow
	for rows.Next() {
		var c columnRow
		if err := rows.Scan(&c.Table, &c.Column, &c.DataType); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// groupColumnsByTable mirrors columns.groupBy({table_name}) { "col (type)" }, preserving
// first-seen table order (Kotlin's groupBy preserves encounter order; the query is
// already ORDER BY table_name so this also happens to be alphabetical). Returned as
// *orderedMap, not map[string]any, so that order survives JSON marshalling.
func groupColumnsByTable(columns []columnRow) *orderedMap {
	tables := newOrderedMap()
	grouped := make(map[string][]string)
	for _, c := range columns {
		if _, seen := grouped[c.Table]; !seen {
			tables.set(c.Table, nil) // placeholder to record first-seen order; replaced below
		}
		grouped[c.Table] = append(grouped[c.Table], fmt.Sprintf("%s (%s)", c.Column, c.DataType))
	}
	for _, table := range tables.keys {
		tables.set(table, grouped[table])
	}
	return tables
}

func fetchForeignKeys(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	rows, err := pool.Query(ctx, `
		SELECT tc.table_name AS source_table,
		       kcu.column_name AS source_column,
		       ccu.table_name AS target_table,
		       ccu.column_name AS target_column
		FROM information_schema.table_constraints tc
		JOIN information_schema.key_column_usage kcu
		  ON tc.constraint_name = kcu.constraint_name
		 AND tc.constraint_schema = kcu.constraint_schema
		JOIN information_schema.constraint_column_usage ccu
		  ON tc.constraint_name = ccu.constraint_name
		 AND tc.constraint_schema = ccu.constraint_schema
		WHERE tc.constraint_type = 'FOREIGN KEY'
		  AND tc.table_schema = 'public'
		ORDER BY tc.table_name, kcu.ordinal_position
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []string
	for rows.Next() {
		var srcTable, srcCol, tgtTable, tgtCol string
		if err := rows.Scan(&srcTable, &srcCol, &tgtTable, &tgtCol); err != nil {
			return nil, err
		}
		out = append(out, fmt.Sprintf("%s.%s -> %s.%s", srcTable, srcCol, tgtTable, tgtCol))
	}
	if out == nil {
		out = []string{}
	}
	return out, rows.Err()
}

// fetchValueHints ports valueHints(columns): for every char-type column, distinct
// values are fetched and only included if the count is in [1, MAX_HINT_VALUES]. Returned
// as *orderedMap (mirroring Kotlin's linkedMapOf) so insertion order -- table then
// ordinal_position, from the ORDER BY in fetchColumns -- survives JSON marshalling.
func fetchValueHints(ctx context.Context, pool *pgxpool.Pool, columns []columnRow) (*orderedMap, error) {
	hints := newOrderedMap()
	for _, c := range columns {
		if !strings.Contains(c.DataType, "char") {
			continue
		}
		// table/column names come only from information_schema (not user input), same
		// trust boundary as the Kotlin version's string-interpolated SQL.
		sql := fmt.Sprintf(
			"SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT %d",
			quoteIdent(c.Column), quoteIdent(c.Table), quoteIdent(c.Column), MaxHintValues+1,
		)
		rows, err := pool.Query(ctx, sql)
		if err != nil {
			return nil, err
		}
		var values []string
		for rows.Next() {
			var v string
			if err := rows.Scan(&v); err != nil {
				rows.Close()
				return nil, err
			}
			values = append(values, v)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
		if len(values) >= 1 && len(values) <= MaxHintValues {
			hints.set(fmt.Sprintf("%s.%s", c.Table, c.Column), values)
		}
	}
	return hints, nil
}

func quoteIdent(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}
