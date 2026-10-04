import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createServer, request } from 'node:http'
import { once } from 'node:events'
import { spawnSync } from 'node:child_process'
import { createMobileAccess } from './mobile-access.mjs'

const origin = 'https://pc.test-tail.ts.net'
const login = 'owner@example.test'
const configuration = { enabled: true, origin, allowedLogin: login }
const TOKEN = 'local-runtime-test-capability'
async function listen(server) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port }
async function close(server) { if (server.listening) await new Promise(resolve => { server.close(resolve); server.closeAllConnections() }) }
function call(port, path, { method = 'GET', body = '', headers = {} } = {}) {
  if (typeof body !== 'string' && !Buffer.isBuffer(body)) body = JSON.stringify(body)
  return new Promise((accept, reject) => {
    const req = request({ hostname: '127.0.0.1', port, path, method, headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...headers }, agent: false }, res => {
      let text = ''; res.on('data', c => { text += c }); res.on('end', () => {
        let data; try { data = JSON.parse(text) } catch { /* static file */ }
        accept({ code: res.statusCode, headers: res.headers, text, data })
      }); res.on('error', reject)
    }); req.on('error', reject); req.end(body)
  })
}
async function fixture(t, options = {}) {
  const root = mkdtempSync(join(tmpdir(), 'inbox-mobile-'))
  mkdirSync(join(root, 'smart-web/dist/assets'), { recursive: true })
  writeFileSync(join(root, 'smart-web/dist/index.html'), '<h1>Smart Inbox</h1>')
  writeFileSync(join(root, 'smart-web/dist/offline.html'), '<h1>Offline</h1>')
  writeFileSync(join(root, 'smart-web/dist/manifest.webmanifest'), '{"name":"Smart Inbox"}')
  writeFileSync(join(root, 'smart-web/dist/sw.js'), '// Service worker')
  writeFileSync(join(root, 'smart-web/dist/assets/app.js'), 'console.log("shell")')
  writeFileSync(join(root, 'smart-web/dist/assets/app.js.map'), 'PRIVATE SOURCE MAP')
  writeFileSync(join(root, 'private.txt'), 'PRIVATE')
  const configFile = join(root, '.smart-inbox/mobile/config.json')
  const writeConfig = value => { mkdirSync(join(root, '.smart-inbox/mobile'), { recursive: true }); writeFileSync(configFile, JSON.stringify(value)) }
  if (!options.disabled) writeConfig(configuration)
  const observed = []
  const gateway = createServer((req, res) => {
    let body = ''; req.on('data', c => { body += c }); req.on('end', () => {
      const value = { path: req.url, method: req.method, headers: req.headers, body }
      observed.push(value)
      res.setHeader('Content-Type', 'application/json'); res.setHeader('Set-Cookie', 'upstream-secret=never-forward'); res.setHeader('Access-Control-Allow-Origin', '*')
      if (options.respond) return options.respond(req, res, value)
      res.end(JSON.stringify(value))
    })
  })
  const gatewayPort = await listen(gateway)
  let clock = Date.now(), state = { mode: 'active', token: TOKEN, message: 'PRIVATE .smart-inbox/runtime/errors.log' }, configuredCalls = 0
  const dependencies = { runtime: { snapshot: () => state }, gatewayPort, mobilePort: options.mobilePort ?? 0, now: () => clock,
    inspectNetwork: async () => ({ installed: true, connected: true, needsLogin: false }),
    configureNetwork: async () => { configuredCalls++; writeConfig(configuration); return { configured: true, origin, message: 'Configured' } },
    ...(options.realAcl ? {} : { secureDirectory: directory => mkdirSync(directory, { recursive: true }) }) }
  let mobile = createMobileAccess(root, dependencies)
  await mobile.start()
  const admin = createServer((req, res) => mobile.handleAdmin(req, res, () => { res.writeHead(404); res.end() }))
  const adminPort = await listen(admin)
  t.after(async () => { await mobile.close(); await close(admin); await close(gateway); rmSync(root, { recursive: true, force: true }) })
  const result = { root, gateway, gatewayPort, observed, writeConfig,
    get mobile() { return mobile }, get configuredCalls() { return configuredCalls },
    advance(ms) { clock += ms }, state(mode) { state = { ...state, mode } },
    async restart() { await mobile.close(); mobile = createMobileAccess(root, dependencies); await mobile.start() },
    admin(path, args = {}) { return call(adminPort, path, { ...args, headers: { host: '127.0.0.1:5173', origin: 'http://127.0.0.1:5173', 'x-runtime-token': TOKEN, ...args.headers } }) },
    mobileCall(path, args = {}) { return call(mobile.server.address().port, path, { ...args, headers: { host: new URL(origin).host, origin, 'tailscale-user-login': login, ...args.headers } }) },
    async code() { return (await result.admin('/api/mobile-admin/pair', { method: 'POST' })).data.code },
    async pair(name = 'My iPhone') {
      const code = await result.code()
      const response = await result.mobileCall('/api/mobile/pair', { method: 'POST', body: { code, deviceName: name } })
      assert.equal(response.code, 200)
      return { code, response, cookie: response.headers['set-cookie'][0].split(';')[0] }
    }
  }
  return result
}

test('mobile access is disabled until all configuration fields are present and valid', async t => {
  const f = await fixture(t, { disabled: true })
  assert.equal(f.mobile.server.listening, false)
  assert.equal(f.mobile.status().configured, false)
  assert.equal(existsSync(join(f.root, '.smart-inbox/mobile/sessions.json')), false)
  for (const config of [{ enabled: true, origin }, { ...configuration, enabled: false }, { ...configuration, origin: 'http://pc.test-tail.ts.net' }, { ...configuration, origin: origin + '/other' }, { ...configuration, allowedLogin: '' }]) {
    f.writeConfig(config); await f.mobile.start(); assert.equal(f.mobile.server.listening, false)
  }
})

test('the app shell and context are public only inside the private perimeter; business data needs pairing', async t => {
  const f = await fixture(t)
  assert.equal((await f.mobileCall('/')).code, 200)
  assert.equal((await f.mobileCall('/assets/app.js')).code, 200)
  assert.match((await f.mobileCall('/offline.html')).text, /Offline/)
  assert.match((await f.mobileCall('/offline.html')).headers['content-type'], /text\/html/)
  assert.equal((await f.mobileCall('/manifest.webmanifest')).headers['content-type'], 'application/manifest+json')
  assert.match((await f.mobileCall('/sw.js')).headers['content-type'], /javascript/)
  const context = await f.mobileCall('/api/mobile/context')
  assert.equal(context.data.mobile, true); assert.equal(context.data.authenticated, false)
  for (const path of ['/api/tasks', '/api/stocks/session', '/api/runtime/status', '/api/mobile-admin/status']) {
    const result = await f.mobileCall(path); assert.equal(result.code, 401); assert.equal(result.data.mobile, true)
  }
  for (const path of ['/actuator/health', '/private.txt', '/assets/app.js.map', '/.env']) assert.equal((await f.mobileCall(path)).code, 404)
  assert.equal(f.observed.length, 0)
})

test('Host, exact Tailscale identity, Origin and Fetch Metadata are checked before all content', async t => {
  const f = await fixture(t)
  const variants = [ { host: 'evil.example' }, { 'tailscale-user-login': '' }, { 'tailscale-user-login': 'another@example.test' }, { origin: 'https://evil.example' }, { 'sec-fetch-site': 'cross-site' }, { 'sec-fetch-site': 'same-site' } ]
  for (const headers of variants) for (const path of ['/', '/api/mobile/context']) assert.equal((await f.mobileCall(path, { headers })).code, 403)
  assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: {}, headers: { origin: '' } })).code, 403)
  // Ordinary navigation and image loads may omit Origin, but still require Serve identity.
  assert.equal((await f.mobileCall('/', { headers: { origin: '' } })).code, 200)
})

test('local administration requires the runtime capability and exact local origin for mutations', async t => {
  const f = await fixture(t)
  for (const headers of [{ 'x-runtime-token': '' }, { origin: '' }, { origin }, { host: 'evil.example' }, { 'sec-fetch-site': 'cross-site' }]) {
    assert.equal((await f.admin('/api/mobile-admin/pair', { method: 'POST', headers })).code, 403)
    assert.equal((await f.admin('/api/mobile-admin/setup', { method: 'POST', headers })).code, 403)
  }
  assert.equal(f.configuredCalls, 0)
  assert.equal((await f.admin('/api/mobile-admin/status')).code, 200)
})

test('pairing uses one-use normalized codes and a secure cookie while disk and admin status hold no secrets', async t => {
  const f = await fixture(t)
  const code = await f.code(); assert.match(code, /^[A-F0-9]{12}$/)
  const result = await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code: code.toLowerCase().match(/.{4}/g).join('-'), deviceName: '  iPhone\n  ' } })
  assert.equal(result.code, 200); assert.equal(result.data.deviceName, 'iPhone')
  const cookie = result.headers['set-cookie'][0]
  for (const flag of ['__Host-SmartInboxMobile=', 'Path=/', 'Secure', 'HttpOnly', 'SameSite=Strict', 'Max-Age=2592000']) assert.ok(cookie.includes(flag))
  const token = cookie.split(';')[0].split('=')[1]
  const stored = readFileSync(join(f.root, '.smart-inbox/mobile/sessions.json'), 'utf8')
  assert.ok(!stored.includes(token)); assert.ok(!stored.includes(code)); assert.match(JSON.parse(stored).sessions[0].hash, /^[a-f0-9]{64}$/)
  const status = await f.admin('/api/mobile-admin/status')
  assert.equal(status.data.devices[0].name, 'iPhone')
  for (const secret of [token, code, 'hash', TOKEN, login]) assert.ok(!status.text.includes(secret))
  assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code } })).code, 401)
})

test('sessions survive process recreation and device revocation survives the next restart', async t => {
  const f = await fixture(t)
  const { cookie } = await f.pair()
  await f.restart()
  assert.equal((await f.mobileCall('/api/tasks', { headers: { cookie } })).code, 200)
  const device = (await f.admin('/api/mobile-admin/status')).data.devices[0]
  assert.equal((await f.admin('/api/mobile-admin/devices/' + device.id, { method: 'DELETE' })).code, 200)
  assert.equal((await f.mobileCall('/api/tasks', { headers: { cookie } })).code, 401)
  await f.restart()
  assert.equal((await f.mobileCall('/api/mobile/context', { headers: { cookie } })).data.authenticated, false)
})

test('pair codes expire, repeated guesses invalidate the code and attempts are rate limited', async t => {
  const f = await fixture(t)
  const expired = await f.code(); f.advance(10 * 60 * 1000 + 1)
  assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code: expired } })).code, 401)
  const code = await f.code()
  for (let i = 0; i < 10; i++) assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code: 'WRONG' } })).code, 401)
  assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code } })).code, 401)
  let last
  for (let i = 0; i < 10; i++) last = await f.mobileCall('/api/mobile/pair', { method: 'POST', body: { code } })
  assert.equal(last.code, 429)
})

test('session expiration and logout both require pairing again', async t => {
  const f = await fixture(t)
  const first = await f.pair()
  const logout = await f.mobileCall('/api/mobile/session', { method: 'DELETE', headers: { cookie: first.cookie } })
  assert.equal(logout.code, 200); assert.match(logout.headers['set-cookie'][0], /Max-Age=0/)
  assert.equal((await f.mobileCall('/api/tasks', { headers: { cookie: first.cookie } })).code, 401)
  const second = await f.pair(); f.advance(30 * 86400000 + 1)
  assert.equal((await f.mobileCall('/api/tasks', { headers: { cookie: second.cookie } })).code, 401)
})

test('authenticated mobile requests still cannot reach credentials, backups, auth, controls or unknown methods', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  const forbidden = [ ['GET', '/api/credentials'], ['GET', '/api/dashboard/backup'], ['POST', '/api/dashboard/backup/restore'], ['POST', '/api/stocks/auth/gpt/start'], ['GET', '/api/stocks/auth/ibkr/callback'], ['POST', '/api/stocks/auth/gpt/disconnect'], ['POST', '/api/runtime/resume'], ['POST', '/api/runtime/standby'], ['GET', '/api/mobile-admin/status'], ['POST', '/api/mobile-admin/setup'], ['GET', '/api/dashboard/operations'], ['GET', '/api/outlook/status'], ['POST', '/api/tasks/import'], ['PUT', '/api/tasks/replace'], ['DELETE', '/api/tasks'], ['POST', '/api/ingest/email'], ['GET', '/api/unknown'], ['TRACE', '/api/tasks'] ]
  for (const [method, path] of forbidden) assert.equal((await f.mobileCall(path, { method, headers: { cookie } })).code, 403, method + ' ' + path)
  assert.equal(f.observed.length, 0)
})

test('canonical path rules stop traversal, encoding and matrix-parameter routing bypasses', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  for (const path of ['/api/%73tocks/session', '/api/tasks/../credentials', '/api/tasks%2f..%2fcredentials', '/api/tasks;anything', '//api/tasks', '/api//tasks', '/api/tasks\\..\\credentials', '/%2e%2e/private.txt', '/api/%252e%252e/credentials']) {
    assert.equal((await f.mobileCall(path, { headers: { cookie } })).code, 400, path)
  }
  assert.equal(f.observed.length, 0)
})

test('mobile can inspect focus and switch its category but cannot impersonate the desktop heartbeat', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  assert.equal((await f.mobileCall('/api/focus?zone=America%2FNew_York', { headers: { cookie } })).code, 200)
  assert.equal((await f.mobileCall('/api/focus/switch', { method: 'POST', headers: { cookie }, body: { id: 'current-session', zone: 'America/New_York' } })).code, 200)
  for (const [method, path] of [
    ['POST', '/api/focus/presence'], ['POST', '/api/focus/presence/stop'],
    ['POST', '/api/focus/start'], ['POST', '/api/focus/stop'], ['PUT', '/api/focus/limit'],
    ['GET', '/api/focus/presence'], ['DELETE', '/api/focus']
  ]) assert.equal((await f.mobileCall(path, { method, headers: { cookie } })).code, 403, path)
  assert.equal(f.observed.length, 2)
})

test('the proxy preserves business payloads but scrubs forwarding, credentials, cookies and upstream cookies', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  const response = await f.mobileCall('/api/tasks?zone=America%2FNew_York', { method: 'POST', body: { text: 'Test task' }, headers: { cookie, authorization: 'secret', 'x-forwarded-for': '8.8.8.8', forwarded: 'for=8.8.8.8', 'x-runtime-token': TOKEN, 'x-credential-session': 'secret', 'x-stock-session': 'unrelated', 'x-http-method-override': 'DELETE' } })
  assert.equal(response.code, 200)
  const sent = f.observed[0]
  assert.equal(sent.path, '/api/tasks?zone=America%2FNew_York'); assert.deepEqual(JSON.parse(sent.body), { text: 'Test task' })
  assert.equal(sent.headers.host, `127.0.0.1:${f.gatewayPort}`); assert.equal(sent.headers.origin, 'http://127.0.0.1:5173')
  for (const header of ['cookie', 'authorization', 'forwarded', 'x-forwarded-for', 'tailscale-user-login', 'x-runtime-token', 'x-credential-session', 'x-stock-session', 'x-http-method-override']) assert.equal(sent.headers[header], undefined)
  assert.equal(response.headers['set-cookie'], undefined); assert.equal(response.headers['access-control-allow-origin'], undefined); assert.equal(response.headers['cache-control'], 'no-store')
  await f.mobileCall('/api/stocks/portfolio', { headers: { cookie, 'x-stock-session': 'stock-capability' } })
  assert.equal(f.observed[1].headers['x-stock-session'], 'stock-capability')
})

test('stock status reveals only model and connection fields, preserving the plan permission boolean', async t => {
  const f = await fixture(t, { respond: (_req, res) => res.end(JSON.stringify({ ibkr: { connected: true, accountIds: ['PRIVATE'] }, gpt: { connected: true, configured: true, planEnabled: true, email: 'PRIVATE', accounts: ['PRIVATE'], profile: 'PRIVATE', account: 'PRIVATE' }, models: [{ id: 'gpt-model', name: 'GPT', secret: 'PRIVATE' }], canAnalyze: true, modelMessage: 'PRIVATE', runtimeToken: 'PRIVATE' })) })
  const { cookie } = await f.pair()
  const result = await f.mobileCall('/api/stocks/status', { headers: { cookie } })
  assert.equal(result.code, 200); assert.equal(result.data.gpt.planEnabled, true); assert.equal(result.data.canAnalyze, true)
  assert.deepEqual(result.data.models, [{ id: 'gpt-model', name: 'GPT' }]); assert.ok(!result.text.includes('PRIVATE'))
})

test('stock history requires pairing and forwards only a read request with its stock session', async t => {
  const f = await fixture(t)
  const path = '/api/stocks/history?scope=STOCK&range=2H&contractId=123'
  assert.equal((await f.mobileCall(path)).code, 401)
  const { cookie } = await f.pair()
  const result = await f.mobileCall(path, { headers: { cookie, 'x-stock-session': 'stock-capability' } })
  assert.equal(result.code, 200)
  assert.equal(f.observed[0].path, path)
  assert.equal(f.observed[0].headers['x-stock-session'], 'stock-capability')
  for (const method of ['POST', 'PUT', 'DELETE']) {
    assert.equal((await f.mobileCall(path, { method, headers: { cookie } })).code, 403)
  }
  assert.equal((await f.mobileCall('/api/stocks/history/export', { headers: { cookie } })).code, 403)
  assert.equal(f.observed.length, 1)
})

test('runtime status is sanitized, standby is read-only and stopped backend reports offline', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  for (const mode of ['active', 'standby', 'entering', 'resuming', 'error']) {
    f.state(mode)
    const result = await f.mobileCall('/api/runtime/status', { headers: { cookie } })
    assert.equal(result.code, 200); assert.equal(result.data.mode, mode); assert.equal(result.data.mobile, true)
    assert.ok(!result.text.includes(TOKEN)); assert.ok(!result.text.includes('errors.log'))
    if (mode !== 'active') assert.equal((await f.mobileCall('/api/tasks', { headers: { cookie } })).code, 503)
  }
  f.state('active'); await close(f.gateway); f.advance(3000)
  assert.equal((await f.mobileCall('/api/runtime/status', { headers: { cookie } })).data.mode, 'offline')
})

test('request sizes are bounded while solution image uploads have enough room', async t => {
  const f = await fixture(t); const { cookie } = await f.pair()
  assert.equal((await f.mobileCall('/api/mobile/pair', { method: 'POST', body: 'x'.repeat(4097) })).code, 413)
  assert.equal((await f.mobileCall('/api/tasks', { method: 'POST', body: 'x'.repeat(1024 * 1024 + 1), headers: { cookie } })).code, 413)
  assert.equal((await f.mobileCall('/api/practice/1/solution', { method: 'PUT', body: 'x'.repeat(2 * 1024 * 1024), headers: { cookie } })).code, 200)
  assert.equal((await f.mobileCall('/api/practice/1/solution', { method: 'PUT', headers: { cookie, 'content-length': 25 * 1024 * 1024 } })).code, 413)
})

test('local setup loads the new configuration and disabled configuration closes the listener', async t => {
  const f = await fixture(t, { disabled: true })
  const response = await f.admin('/api/mobile-admin/setup', { method: 'POST' })
  assert.equal(response.code, 200); assert.equal(response.data.running, true); assert.equal(response.data.message, 'Configured')
  assert.equal(f.configuredCalls, 1)
  f.writeConfig({ ...configuration, allowedLogin: 'changed@example.test' }); await f.mobile.start()
  assert.equal((await f.mobileCall('/')).code, 403)
  assert.equal((await f.mobileCall('/', { headers: { 'tailscale-user-login': 'changed@example.test' } })).code, 200)
  f.writeConfig({ ...configuration, enabled: false }); await f.mobile.start()
  assert.equal(f.mobile.server.listening, false)
})

test('a mobile port conflict does not throw or stop the desktop controller', async t => {
  const blocker = createServer(); const mobilePort = await listen(blocker)
  t.after(() => close(blocker))
  const f = await fixture(t, { mobilePort })
  assert.equal(f.mobile.status().running, false)
  assert.equal((await f.admin('/api/mobile-admin/status')).code, 200)
  assert.equal((await f.admin('/api/mobile-admin/pair', { method: 'POST' })).code, 409)
})

test('production storage protection restricts Windows ACL to the current user', { skip: process.platform !== 'win32' }, async t => {
  const f = await fixture(t, { realAcl: true })
  assert.equal(f.mobile.status().running, true)
  await f.pair()
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', "$ErrorActionPreference='Stop'; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $acls=@([System.IO.Directory]::GetAccessControl($env:SMART_INBOX_MOBILE_DIRECTORY),[System.IO.File]::GetAccessControl([System.IO.Path]::Combine($env:SMART_INBOX_MOBILE_DIRECTORY,'sessions.json'))); foreach($acl in $acls){foreach($rule in $acl.Access){if($rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $sid){throw 'Unexpected ACL identity'}}}; 'OK'"], { windowsHide: true, encoding: 'utf8', env: { ...process.env, SMART_INBOX_MOBILE_DIRECTORY: join(f.root, '.smart-inbox/mobile') } })
  assert.equal(result.status, 0, result.stderr); assert.match(result.stdout, /OK/)
})
