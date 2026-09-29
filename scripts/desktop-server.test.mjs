import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createServer, request } from 'node:http'
import { once } from 'node:events'
import { createDesktopServer } from './desktop-server.mjs'

async function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'inbox-desktop-'))
  mkdirSync(join(root, 'smart-web/dist'), { recursive: true })
  writeFileSync(join(root, 'smart-web/dist/index.html'), '<h1>Smart Inbox</h1>')
  writeFileSync(join(root, 'private.txt'), 'PRIVATE')
  const gateway = createServer((req, res) => { let body = ''; req.on('data', chunk => body += chunk); req.on('end', () => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ path: req.url, method: req.method, body })) }) })
  gateway.listen(0, '127.0.0.1'); await once(gateway, 'listening')
  const server = createDesktopServer(root, { gatewayPort: gateway.address().port })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  t.after(async () => { await Promise.all([new Promise(r => server.close(r)), new Promise(r => gateway.close(r))]); rmSync(root, { recursive: true, force: true }) })
  return (path, method = 'GET', body = '', host = '127.0.0.1:5173') => new Promise((accept, reject) => {
    const req = request({ hostname: '127.0.0.1', port: server.address().port, path, method, headers: { host } }, res => { let data = ''; res.on('data', c => data += c); res.on('end', () => accept({ code: res.statusCode, body: data })) })
    req.on('error', reject); req.end(body)
  })
}
test('desktop serves built pages, supports navigation and rejects path traversal and foreign hosts', async t => {
  const call = await fixture(t)
  assert.match((await call('/')).body, /Smart Inbox/)
  assert.equal((await call('/watch')).code, 200)
  assert.equal((await call('/assets/missing.js')).code, 404)
  assert.equal((await call('/%2e%2e/%2e%2e/private.txt')).code, 403)
  assert.equal((await call('/%5c..%5cprivate.txt')).code, 400)
  assert.equal((await call('/', 'GET', '', 'evil.example:5173')).code, 403)
})
test('desktop proxies methods, query strings and bodies, and keeps runtime writes token-protected', async t => {
  const call = await fixture(t)
  const response = await call('/api/tasks?test=1', 'POST', '{"title":"test"}')
  assert.deepEqual(JSON.parse(response.body), { path: '/api/tasks?test=1', method: 'POST', body: '{"title":"test"}' })
  assert.equal((await call('/api/runtime/status')).code, 200)
  assert.equal((await call('/api/runtime/standby', 'POST')).code, 403)
})
