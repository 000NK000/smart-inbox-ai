import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRuntimeControl, runtimePlugin } from './runtime-control.mjs'

const root = () => mkdtempSync(join(tmpdir(), 'smart-inbox-runtime-test-'))
function request(control, method, url, headers = {}, remoteAddress = '127.0.0.1') {
  let code, data, passed = false
  const req = { method, url, headers: { host: '127.0.0.1:5173', ...headers }, socket: { remoteAddress } }
  const res = { writeHead(value) { code = value }, end(value) { data = JSON.parse(value) } }
  control.middleware(req, res, () => { passed = true })
  return { code, data, passed }
}
const settle = () => new Promise(resolve => setTimeout(resolve, 20))

test('only local same-origin requests with a session token can stop the project', () => {
  let calls = 0
  const control = createRuntimeControl(root(), async () => { calls++ })
  const token = control.snapshot().token
  assert.equal(request(control, 'POST', '/api/runtime/standby').code, 403)
  assert.equal(request(control, 'POST', '/api/runtime/standby', { 'x-runtime-token': token, origin: 'https://evil.test' }).code, 403)
  assert.equal(request(control, 'POST', '/api/runtime/standby', { 'x-runtime-token': token, host: 'evil.test:5173' }).code, 403)
  assert.equal(request(control, 'GET', '/api/runtime/status', {}, '192.168.1.1').code, 403)
  assert.equal(calls, 0)
})

test('standby blocks workload requests, serializes transitions and survives controller restart', async () => {
  const directory = root()
  let finish, calls = 0
  const control = createRuntimeControl(directory, () => { calls++; return new Promise(resolve => { finish = resolve }) })
  assert.equal(control.transition('standby'), true)
  assert.equal(control.transition('resume'), false)
  assert.equal(request(control, 'GET', '/api/tasks').code, 503)
  assert.equal(request(control, 'GET', '/api/runtime/status').code, 200)
  await settle(); finish(); await settle()
  assert.equal(control.snapshot().mode, 'standby')
  assert.equal(calls, 1)
  const restarted = createRuntimeControl(directory, async () => {})
  assert.equal(restarted.snapshot().mode, 'standby')
  restarted.transition('resume'); await settle()
  assert.equal(restarted.snapshot().mode, 'active')
  assert.equal(request(restarted, 'GET', '/api/mails/summaries').passed, true)
})

test('a failed stop is never shown as standby and can be recovered', async () => {
  const control = createRuntimeControl(root(), async (directory, action) => { if (action === 'standby') throw new Error('Database still saving') })
  control.transition('standby'); await settle()
  assert.equal(control.snapshot().mode, 'error')
  assert.equal(request(control, 'GET', '/api/tasks').code, 503)
  control.transition('resume'); await settle()
  assert.equal(control.snapshot().mode, 'active')
})

test('interrupted transitions are explicit recovery states, not false success', () => {
  const directory = root()
  createRuntimeControl(directory, async () => {})
  writeFileSync(join(directory, '.smart-inbox/runtime/state.json'), JSON.stringify({ mode: 'resuming' }))
  assert.equal(createRuntimeControl(directory).snapshot().mode, 'error')
})

test('Docker port binding failures explain the conflict and remain retryable', async () => {
  const control = createRuntimeControl(root(), async () => {
    throw new Error('ports are not available: listen tcp 0.0.0.0:10911: bind: forbidden by its access permissions')
  })
  control.transition('standby'); await settle()
  assert.equal(control.snapshot().mode, 'error')
  assert.match(control.snapshot().message, /Windows/)
  assert.equal(request(control, 'GET', '/api/mails/summaries').code, 503)
  assert.equal(control.transition('resume'), true)
  await settle()
  assert.equal(control.snapshot().mode, 'error')
})

test('Vite identifies the desktop before proxying or standby gating with the same local security checks', () => {
  const directory = root()
  createRuntimeControl(directory, async () => {})
  for (const mode of ['active', 'standby', 'error']) {
    writeFileSync(join(directory, '.smart-inbox/runtime/state.json'), JSON.stringify({ mode }))
    let middleware
    runtimePlugin(directory).configureServer({ middlewares: { use(handler) { middleware = handler } } })
    const control = { middleware }
    for (const method of ['GET', 'HEAD']) {
      const response = request(control, method, '/api/mobile/context')
      assert.equal(response.code, 200); assert.deepEqual(response.data, { mobile: false }); assert.equal(response.passed, false)
    }
    assert.equal(request(control, 'GET', '/api/mobile/context', { origin: 'https://evil.example' }).code, 403)
    assert.equal(request(control, 'GET', '/api/mobile/context', { host: 'evil.example:5173' }).code, 403)
    assert.equal(request(control, 'GET', '/api/mobile/context', { 'sec-fetch-site': 'cross-site' }).code, 403)
    assert.equal(request(control, 'GET', '/api/mobile/context', { 'sec-fetch-site': 'same-site' }).code, 403)
    assert.equal(request(control, 'GET', '/api/mobile/context', {}, '192.168.1.1').code, 403)
    if (mode !== 'active') assert.equal(request(control, 'GET', '/api/tasks').code, 503)
  }
})

test('failed lifecycle listeners are logged without stranding standby or rejecting after-transition work', async () => {
  const directory = root()
  const actions = []
  const control = createRuntimeControl(directory, async (_root, action) => { actions.push(action) })
  control.addLifecycleListener({
    beforeTransition: async () => { throw new Error('Before hook failed') },
    afterTransition: async () => { throw new Error('After hook failed') }
  })
  control.transition('standby'); await settle()
  assert.equal(control.snapshot().mode, 'standby')
  control.transition('resume'); await settle()
  assert.equal(control.snapshot().mode, 'active')
  assert.deepEqual(actions, ['standby', 'resume'])
  const log = readFileSync(join(directory, '.smart-inbox/runtime/errors.log'), 'utf8')
  assert.match(log, /Before hook failed/)
  assert.match(log, /After hook failed/)
})
