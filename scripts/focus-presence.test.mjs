import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { EventEmitter } from 'node:events'
import { createFocusPresence } from './focus-presence.mjs'
import { createRuntimeControl, runtimePlugin } from './runtime-control.mjs'

function fixture(t, overrides = {}) {
  const root = mkdtempSync(join(tmpdir(), 'inbox-focus-runtime-'))
  const calls = [], order = []
  const runtime = createRuntimeControl(root, async (_root, action) => { order.push(action) })
  let nextId = 0, scheduled, cleared = 0
  const options = { zone: 'America/New_York', makeId: () => `runtime-${++nextId}`,
    post: async (path, body) => { calls.push({ path, ...body }); order.push(path) },
    schedule: callback => { scheduled = callback; return 1 }, unschedule: () => { cleared++ }, ...overrides }
  const presence = createFocusPresence(runtime, options)
  t.after(async () => { await presence.close(); rmSync(root, { recursive: true, force: true }) })
  return { runtime, calls, order, presence, options, scheduled: () => scheduled(), cleared: () => cleared }
}
async function waitFor(check) {
  for (let i = 0; i < 100; i++) {
    if (check()) return
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  assert.fail('Timed out waiting for runtime transition')
}

test('opening a runtime starts presence immediately and renews the same owner independent of pages', async t => {
  const f = fixture(t)
  await f.presence.start()
  assert.deepEqual(f.calls, [{ path: '/api/focus/presence', runtimeId: 'runtime-1', zone: 'America/New_York' }])
  await f.presence.start()
  assert.equal(f.calls.length, 1)
  await f.presence.tick()
  assert.equal(f.calls[1].runtimeId, 'runtime-1')
  await f.presence.close()
  assert.equal(f.calls.at(-1).path, '/api/focus/presence/stop')
  assert.equal(f.cleared(), 1)
  await f.presence.tick()
  assert.equal(f.calls.length, 3)
})

test('standby saves focus before stopping Java and resume uses a new default-time owner', async t => {
  const f = fixture(t)
  await f.presence.start()
  f.runtime.transition('standby')
  await waitFor(() => f.runtime.snapshot().mode === 'standby')
  assert.deepEqual(f.order, ['/api/focus/presence', '/api/focus/presence/stop', 'standby'])
  await f.presence.tick()
  assert.equal(f.calls.length, 2)
  f.runtime.transition('resume')
  await waitFor(() => f.calls.length === 3)
  assert.equal(f.calls[2].runtimeId, 'runtime-2')
  assert.equal(f.order.at(-2), 'resume')
})

test('heartbeat and close never overlap and close waits for the outstanding heartbeat', async t => {
  let resolveHeartbeat
  const requests = []
  const f = fixture(t, { post: (path, body) => {
    requests.push({ path, body })
    return path.endsWith('/stop') ? Promise.resolve() : new Promise(resolve => { resolveHeartbeat = resolve })
  } })
  const first = f.presence.start()
  const second = f.presence.tick()
  await Promise.resolve()
  assert.equal(requests.length, 1)
  const closing = f.presence.close()
  await f.presence.tick()
  assert.equal(requests.length, 1)
  resolveHeartbeat()
  await Promise.all([first, second, closing])
  assert.equal(requests.length, 2)
  assert.equal(requests[1].path, '/api/focus/presence/stop')
  assert.equal(requests[0].body.runtimeId, requests[1].body.runtimeId)
})

test('backend failures do not block startup or standby and retry only at the normal heartbeat', async t => {
  let attempts = 0
  const f = fixture(t, { post: async () => { attempts++; throw new Error('Offline') } })
  await f.presence.start()
  assert.equal(attempts, 1)
  await f.presence.tick()
  assert.equal(attempts, 2)
  f.runtime.transition('standby')
  await waitFor(() => f.runtime.snapshot().mode === 'standby')
  assert.equal(attempts, 3)
  await f.presence.tick()
  assert.equal(attempts, 3)
})

test('a runtime that starts in standby does not create any focus session until resumed', async t => {
  const f = fixture(t)
  f.runtime.transition('standby')
  await waitFor(() => f.runtime.snapshot().mode === 'standby')
  await f.presence.start()
  assert.equal(f.calls.length, 0)
  f.runtime.transition('resume')
  await waitFor(() => f.calls.length === 1)
  assert.equal(f.calls[0].path, '/api/focus/presence')
})

test('Vite presence starts on listening and is stopped when its HTTP server closes', async t => {
  const f = fixture(t)
  const httpServer = new EventEmitter()
  httpServer.address = () => ({ port: 5173 })
  const plugin = runtimePlugin('unused', { runtime: f.runtime, focusOptions: f.options })
  plugin.configureServer({ httpServer, middlewares: { use() {} } })
  assert.equal(f.calls.length, 0)
  httpServer.emit('listening')
  await waitFor(() => f.calls.length === 1)
  httpServer.emit('close')
  await waitFor(() => f.calls.length === 2)
  assert.equal(f.calls[1].path, '/api/focus/presence/stop')
})

test('a Vite preview on another port cannot replace the desktop focus owner', async t => {
  const f = fixture(t)
  const httpServer = new EventEmitter()
  httpServer.address = () => ({ port: 5175 })
  runtimePlugin('unused', { runtime: f.runtime, focusOptions: f.options })
    .configureServer({ httpServer, middlewares: { use() {} } })
  httpServer.emit('listening')
  await Promise.resolve()
  httpServer.emit('close')
  await Promise.resolve()
  assert.equal(f.calls.length, 0)
})
