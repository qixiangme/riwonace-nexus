import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';

const urls = process.argv.slice(2);
if (urls.length === 0) {
  console.error('usage: node contract-smoke.mjs <sse-url> [sse-url...]');
  process.exit(2);
}

for (const url of urls) {
  const client = new Client({ name: 'db-mcp-contract-smoke', version: '1.0.0' }, { capabilities: {} });
  await client.connect(new SSEClientTransport(new URL(url)));
  const tools = (await client.listTools()).tools.map((tool) => tool.name).sort();
  const resources = (await client.listResources()).resources.map((resource) => resource.uri).sort();
  const schema = await client.readResource({ uri: 'db://schema' });
  const schemaText = schema.contents.filter((content) => 'text' in content).map((content) => content.text).join('');

  const expectedTools = ['kg_search', 'run_sql', 'vector_search'];
  if (JSON.stringify(tools) !== JSON.stringify(expectedTools)) {
    throw new Error(`${url}: unexpected tools ${JSON.stringify(tools)}`);
  }
  if (!resources.includes('db://schema')) throw new Error(`${url}: db://schema resource missing`);
  const parsed = JSON.parse(schemaText);
  if (!parsed.tables || !parsed.valueHints) throw new Error(`${url}: invalid schema resource`);

  console.log(JSON.stringify({ url, tools, resources, schemaChars: schemaText.length }));
  await client.close();
}
