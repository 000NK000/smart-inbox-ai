import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

test('shared subscribers coalesce polling, skip unchanged lists, pause hidden and release timer', async () => {
  const dom = new JSDOM('<div id="one"></div><div id="two"></div>', { pretendToBeVisual: true })
  globalThis.window = dom.window; globalThis.document = dom.window.document
  globalThis.Element = dom.window.Element; globalThis.SVGElement = dom.window.SVGElement
  const { createApp, h } = await import('vue')
  const { default: axios } = await import('axios')
  const { useTaskStore, refreshTasks, completeTask } = await import('./taskStore.js')
  const originalGet = axios.get, originalPatch = axios.patch
  const originalInterval = globalThis.setInterval, originalClear = globalThis.clearInterval
  const timers = new Map(), calls = []
  let revision = 'first', hidden = false, nextTimer = 0, completed = false, state
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  globalThis.setInterval = fn => { const id = ++nextTimer; timers.set(id, fn); return id }
  globalThis.clearInterval = id => timers.delete(id)
  axios.get = async url => {
    calls.push(url)
    await Promise.resolve()
    return { data: url.endsWith('/summary') ? { version: revision, total: 1, open: completed ? 0 : 1, completed: completed ? 1 : 0 } :
      [{ id: 'task', text: 'Synthetic task', version: completed ? 1 : 0, status: completed ? 'COMPLETED' : 'OPEN' }] }
  }
  axios.patch = async (_url, body) => { assert.equal(body.completed, true); completed = true; revision = 'completed'; return { data: {} } }
  const one = createApp({ setup() { useTaskStore({ items: false }); return () => h('div') } })
  const two = createApp({ setup() { state = useTaskStore(); return () => h('div') } })
  try {
    one.mount('#one'); two.mount('#two'); await refreshTasks()
    assert.equal(timers.size, 1)
    assert.deepEqual(calls, ['/api/tasks/summary', '/api/tasks'])
    await refreshTasks(); assert.equal(calls.filter(url => url === '/api/tasks').length, 1)
    hidden = true; const count = calls.length
    for (const tick of timers.values()) tick()
    await Promise.resolve(); assert.equal(calls.length, count)
    revision = 'second'; hidden = false; document.dispatchEvent(new dom.window.Event('visibilitychange')); await refreshTasks()
    assert.equal(calls.filter(url => url === '/api/tasks').length, 2)
    await completeTask(state.tasks.value[0], true)
    assert.equal(state.summary.value.open, 0); assert.equal(state.tasks.value[0].status, 'COMPLETED')
    one.unmount(); assert.equal(timers.size, 1)
    two.unmount(); assert.equal(timers.size, 0)
    const after = calls.length; window.dispatchEvent(new dom.window.Event('focus')); await Promise.resolve(); assert.equal(calls.length, after)
  } finally {
    if (one._instance) one.unmount(); if (two._instance) two.unmount()
    axios.get = originalGet; axios.patch = originalPatch
    globalThis.setInterval = originalInterval; globalThis.clearInterval = originalClear
    dom.window.close()
  }
})
