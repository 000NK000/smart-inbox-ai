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

test('desktop and mobile share runtime state while local mobile administration stays available in standby', async t => {
  const root = mkdtempSync(join(tmpdir(), 'inbox-desktop-mobile-'))
  const origin = 'https://pc.test-tail.ts.net'
  mkdirSync(join(root, '.smart-inbox/mobile'), { recursive: true })
  writeFileSync(join(root, '.smart-inbox/mobile/config.json'), JSON.stringify({ enabled: true, origin, allowedLogin: 'owner@example.test' }))
  const runtime = { snapshot: () => ({ mode: 'standby', token: 'shared-runtime-token', message: 'private-errors.log' }), middleware: (_req, res) => { res.writeHead(503); res.end('Standby') } }
  const server = createDesktopServer(root, { runtime, mobileOptions: { mobilePort: 0, secureDirectory: () => {}, inspectNetwork: async () => ({ installed: true, connected: true }) } })
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); await server.mobileAccess.start()
  t.after(async () => { await new Promise(r => server.close(r)); await server.mobileAccess.close(); rmSync(root, { recursive: true, force: true }) })
  const invoke = (port, path, method, headers, body = '') => new Promise((accept, reject) => {
    const req = request({ hostname: '127.0.0.1', port, path, method, headers, agent: false }, res => {
      let data = ''; res.on('data', chunk => { data += chunk }); res.on('end', () => accept({ code: res.statusCode, data: JSON.parse(data), headers: res.headers }))
    }); req.on('error', reject); req.end(body)
  })
  const local = { host: '127.0.0.1:5173', origin: 'http://127.0.0.1:5173', 'x-runtime-token': 'shared-runtime-token' }
  const status = await invoke(server.address().port, '/api/mobile-admin/status', 'GET', local)
  assert.equal(status.code, 200); assert.equal(status.data.running, true)
  const desktopContext = await invoke(server.address().port, '/api/mobile/context', 'GET', local)
  assert.equal(desktopContext.code, 200); assert.deepEqual(desktopContext.data, { mobile: false })
  const pairing = await invoke(server.address().port, '/api/mobile-admin/pair', 'POST', local)
  assert.equal(pairing.code, 200)
  const mobileHeaders = { host: new URL(origin).host, origin, 'tailscale-user-login': 'owner@example.test', 'content-type': 'application/json' }
  const paired = await invoke(server.mobileAccess.server.address().port, '/api/mobile/pair', 'POST', mobileHeaders, JSON.stringify({ code: pairing.data.code }))
  const cookie = paired.headers['set-cookie'][0].split(';')[0]
  const mobileStatus = await invoke(server.mobileAccess.server.address().port, '/api/runtime/status', 'GET', { ...mobileHeaders, cookie })
  assert.equal(mobileStatus.data.mode, 'standby'); assert.equal(mobileStatus.data.token, undefined); assert.ok(!mobileStatus.data.message.includes('private-errors.log'))
})

test('desktop identification stays available when the backend is offline and rejects foreign origins', async t => {
  const root = mkdtempSync(join(tmpdir(), 'inbox-desktop-context-'))
  const closedGateway = createServer(); closedGateway.listen(0, '127.0.0.1'); await once(closedGateway, 'listening')
  const gatewayPort = closedGateway.address().port; await new Promise(resolve => closedGateway.close(resolve))
  const runtime = { snapshot: () => ({ mode: 'active', token: 'local-token' }), middleware: (_req, _res, next) => next() }
  const server = createDesktopServer(root, { runtime, gatewayPort })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await server.mobileAccess.close(); rmSync(root, { recursive: true, force: true }) })
  const invoke = (path, method = 'GET', headers = {}) => new Promise((accept, reject) => {
    const req = request({ hostname: '127.0.0.1', port: server.address().port, path, method, headers: { host: '127.0.0.1:5173', ...headers }, agent: false }, res => {
      let body = ''; res.on('data', chunk => { body += chunk }); res.on('end', () => accept({ code: res.statusCode, body }))
    }); req.on('error', reject); req.end()
  })
  assert.equal((await invoke('/api/tasks')).code, 502)
  assert.deepEqual(JSON.parse((await invoke('/api/mobile/context')).body), { mobile: false })
  assert.equal((await invoke('/api/mobile/context', 'HEAD')).code, 200)
  assert.equal((await invoke('/api/mobile/context', 'GET', { origin: 'https://evil.example' })).code, 403)
  assert.equal((await invoke('/api/mobile/context', 'GET', { 'sec-fetch-site': 'cross-site' })).code, 403)
})

test('opening the desktop server starts focus before visiting a page and closing saves its session', async t => {
  const root = mkdtempSync(join(tmpdir(), 'inbox-desktop-focus-'))
  const calls = []
  const runtime = { snapshot: () => ({ mode: 'active', token: 'local-token' }), middleware: (_req, _res, next) => next() }
  const server = createDesktopServer(root, { runtime, focusOptions: { post: async (path, body) => { calls.push({ path, body }) } } })
  t.after(async () => {
    if (server.listening) await new Promise(resolve => server.close(resolve))
    await server.focusPresence.close(); await server.mobileAccess.close()
    rmSync(root, { recursive: true, force: true })
  })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  await server.focusPresence.tick()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, '/api/focus/presence')
  assert.match(calls[0].body.runtimeId, /^[0-9a-f-]{36}$/)
  await new Promise(resolve => server.close(resolve))
  await server.focusPresence.close()
  assert.equal(calls.length, 2)
  assert.equal(calls[1].path, '/api/focus/presence/stop')
  assert.equal(calls[1].body.runtimeId, calls[0].body.runtimeId)
})
