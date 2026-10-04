import test from 'node:test'
import assert from 'node:assert/strict'
import { createFocusController } from './focusController.js'

const state = (stamp, active = { id: 'old', category: 'INEFFECTIVE', leaseUntil: stamp + 45000 }) => ({ serverNow: stamp, active })
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('missing and expired desktop leases never create or toggle a session', async () => {
  for (const snapshot of [state(100000, null), state(100000, { id: 'old', category: 'EFFECTIVE', leaseUntil: 99999 })]) {
    let writes = 0
    const controller = createFocusController({ get: async () => ({ data: snapshot }), post: async () => { writes++ } }, 'UTC', () => 100000)
    assert.equal((await controller.switchCategory()).status, 'unavailable')
    assert.equal(writes, 0)
    assert.match(controller.error.value, /等待软件/)
    controller.dispose()
  }
})

test('leaving a page for longer than the lease triggers a fresh read before switching', async () => {
  let now = 100000, reads = 0
  const controller = createFocusController({
    get: async () => ({ data: state(now, { id: ++reads === 1 ? 'old' : 'renewed', category: 'INEFFECTIVE', leaseUntil: now + 45000 }) }),
    post: async (_url, body) => { assert.equal(body.id, 'renewed'); return { data: state(now, { id: 'new', category: 'EFFECTIVE', leaseUntil: now + 45000 }) } }
  }, 'UTC', () => now)
  await controller.refresh(); now += 60000
  assert.equal((await controller.switchCategory()).category, 'EFFECTIVE')
  assert.equal(reads, 2)
  controller.dispose()
})

test('ambiguous write plus failed reconciliation cannot send a second write using stale state', async () => {
  let reads = 0, writes = 0
  const controller = createFocusController({
    get: async () => { if (++reads === 1) return { data: state(100000) }; throw new Error('Synthetic outage') },
    post: async () => { writes++; throw new Error('Synthetic lost response') }
  }, 'UTC', () => 100000)
  assert.equal((await controller.switchCategory()).status, 'error')
  assert.match(controller.error.value, /尚未确认/)
  assert.equal((await controller.switchCategory()).status, 'error')
  assert.equal(reads, 3)
  assert.equal(writes, 1, 'Next key must retry the read, never blindly repeat the toggle')
  controller.dispose()
})

test('standby reached during a pending read cancels the write even if the workspace stays mounted', async () => {
  let enabled = true, writes = 0
  const pending = deferred()
  const controller = createFocusController({ get: () => pending.promise, post: async () => { writes++ } }, 'UTC', () => 100000)
  const switching = controller.switchCategory({ canSwitch: () => enabled })
  enabled = false; pending.resolve({ data: state(100000) })
  assert.equal((await switching).status, 'ignored')
  assert.equal(writes, 0)
  assert.equal(controller.busy.value, false)
  controller.dispose()
})
