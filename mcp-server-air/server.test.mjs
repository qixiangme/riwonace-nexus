import test from 'node:test';
import assert from 'node:assert/strict';
import { server } from './server.mjs';

test('과제의 실행 도구 3종과 스키마 리소스를 노출한다', () => {
  assert.deepEqual(
    server.tools().map((tool) => tool.name).sort(),
    ['kg_search', 'run_sql', 'vector_search'],
  );
  assert.deepEqual(server.resources().map((resource) => resource.uri), ['db://schema']);
});
