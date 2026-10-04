import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as focusTime from '../utils/focusTime.js'

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/', pretendToBeVisual: true })
globalThis.window = dom.window
globalThis.document = dom.window.document
globalThis.Element = dom.window.Element
globalThis.SVGElement = dom.window.SVGElement
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const focusController = await import('../stores/focusController.js')
const { installFocusShortcut } = await import('../utils/focusShortcut.js')
const source = await readFile(new URL('./FocusCenter.vue', import.meta.url), 'utf8')
const flush = async () => { for (let i = 0; i < 18; i++) { await Promise.resolve(); await Vue.nextTick() } }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const key = (value = 'Enter', options = {}, target = window) => target.dispatchEvent(new window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }))
const query = selector => document.querySelector(selector)

function compile(modules) {
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'focus-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    if (!(path in modules)) throw new Error('Unmocked import: ' + path)
    if (binding.startsWith('{')) return 'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];'
    return 'const ' + binding + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function snapshot(now = Date.now()) {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const today = focusTime.localDay(now, zone)
  const historyDays = Array.from({ length: 93 }, (_, index) => {
    const day = new Date(`${today}T12:00:00Z`); day.setUTCDate(day.getUTCDate() - 92 + index)
    return { date: day.toISOString().slice(0, 10), totals: { EFFECTIVE: 60000, INEFFECTIVE: 120000 } }
  })
  return { serverNow: now, today, weekStart: today, daily: { EFFECTIVE: 60000, INEFFECTIVE: 120000 }, weekly: { EFFECTIVE: 60000, INEFFECTIVE: 120000 }, weekDays: historyDays.slice(-7), historyDays, historyStart: historyDays[0].date, active: { id: 'initial', category: 'INEFFECTIVE', startedAt: now - 120000, leaseUntil: now + 45000 } }
}
async function mount(axios) {
  const controller = focusController.createFocusController(axios, Intl.DateTimeFormat().resolvedOptions().timeZone)
  const app = Vue.createApp(compile({ vue: Vue, axios, '../i18n/index.js': i18n, '../utils/focusTime.js': focusTime, '../stores/focusController.js': focusController }))
  app.provide(focusController.focusControllerKey, controller)
  const removeShortcut = installFocusShortcut({ window, document, controller, isEnabled: () => !query('#root').closest('[inert]') })
  app.mount('#root'); await flush()
  return () => { removeShortcut(); controller.dispose(); app.unmount(); query('#root').innerHTML = ''; query('#root').removeAttribute('inert'); i18n.setLocale('zh-CN') }
}

test('Enter switches two categories, ignores editing/interactive/repeated keys, and detaches on unmount', async () => {
  const state = snapshot(), calls = []
  const close = await mount({
    get: async () => ({ data: structuredClone(state) }),
    post: async (url, body) => { calls.push({ url, body }); state.active.category = state.active.category === 'EFFECTIVE' ? 'INEFFECTIVE' : 'EFFECTIVE'; state.active.id = `switch-${calls.length}`; return { data: structuredClone(state) } }
  })
  try {
    assert.equal(calls.length, 0, 'Mount must never start or reset desktop tracking')
    assert.equal(document.querySelectorAll('.category-card').length, 2)
    assert.equal(key(), false); await flush()
    assert.deepEqual(calls[0], { url: '/api/focus/switch', body: { id: 'initial', zone: Intl.DateTimeFormat().resolvedOptions().timeZone } })
    assert.ok(query('.effective.running'))
    for (const options of [{ repeat: true }, { ctrlKey: true }, { altKey: true }, { metaKey: true }, { shiftKey: true }, { isComposing: true }]) key('Enter', options)
    key('1'); key('Enter', {}, query('.switch-button'))
    for (const markup of ['<input>', '<textarea></textarea>', '<select></select>', '<div contenteditable="true"><span>edit</span></div>', '<a href="#">link</a>']) {
      const holder = document.createElement('div'); holder.innerHTML = markup; query('#root').append(holder); key('Enter', {}, holder.firstChild); holder.remove()
    }
    query('#root').setAttribute('inert', ''); key(); query('#root').removeAttribute('inert')
    await flush(); assert.equal(calls.length, 1)
    i18n.setLocale('en-US'); await flush()
    assert.equal(query('.effective h3').textContent, 'Effective time')
    assert.match(query('.switch-button').textContent, /Switch to Ineffective time/)
    assert.equal(calls.length, 1, 'Language change must not switch the timer')
    key(); await flush(); assert.ok(query('.ineffective.running'))
  } finally { close() }
  const count = calls.length; key(); await flush(); assert.equal(calls.length, count)
})

test('three-month daily history is reachable by pagination and refresh preserves selected range', async () => {
  const state = snapshot()
  const close = await mount({ get: async () => ({ data: structuredClone(state) }) })
  try {
    document.querySelectorAll('.range-picker button')[2].click(); await flush()
    assert.match(query('.pagination').textContent, /93 天/)
    assert.equal(document.querySelectorAll('.chart-column').length, 14)
    let pages = 0
    while (!document.querySelectorAll('.pagination button')[1].disabled) { document.querySelectorAll('.pagination button')[1].click(); await flush(); pages++ }
    assert.equal(pages, 13)
    assert.equal(document.querySelectorAll('.daily-records tbody tr').length, 2)
    query('.refresh-button').click(); await flush()
    assert.equal(document.querySelectorAll('.range-picker button')[2].getAttribute('aria-pressed'), 'true')
    assert.match(query('.pagination').textContent, /14 \/ 14/)
    document.querySelectorAll('.range-picker button')[0].click(); await flush()
    assert.match(query('.pagination').textContent, /7 天.*1 \/ 1/)
  } finally { close() }
})

test('a stale GET response cannot overwrite a completed switch', async () => {
  const state = snapshot(), oldRead = deferred(); let reads = 0
  const close = await mount({
    get: () => ++reads === 2 ? oldRead.promise : Promise.resolve({ data: structuredClone(state) }),
    post: async () => { state.active.category = 'EFFECTIVE'; state.active.id = 'new'; return { data: structuredClone(state) } }
  })
  try {
    query('.refresh-button').click(); await flush()
    const stale = structuredClone(state)
    key(); await flush(); assert.ok(query('.effective.running'))
    oldRead.resolve({ data: stale }); await flush()
    assert.ok(query('.effective.running'))
    assert.equal(query('.refresh-button').disabled, false)
  } finally { close() }
})

test('ambiguous switch forces a new GET even with an old GET pending, blocks another Enter, and does not retry the write', async () => {
  const state = snapshot(), oldRead = deferred(), newRead = deferred(); let reads = 0, writes = 0
  const close = await mount({
    get: () => { reads++; return reads === 2 ? oldRead.promise : reads === 3 ? newRead.promise : Promise.resolve({ data: structuredClone(state) }) },
    post: async () => { writes++; throw { response: { status: 409 } } }
  })
  try {
    query('.refresh-button').click(); await flush()
    key(); await flush(); assert.equal(reads, 3, 'Reconciliation must bypass an in-flight stale read')
    assert.equal(query('.switch-button').disabled, true)
    key(); await flush(); assert.equal(writes, 1)
    state.active.category = 'EFFECTIVE'; state.active.id = 'other-window'
    newRead.resolve({ data: structuredClone(state) }); await flush()
    assert.ok(query('.effective.running')); assert.match(query('.notice').textContent, /计时状态已更新/)
    oldRead.resolve({ data: snapshot() }); await flush()
    assert.ok(query('.effective.running')); assert.equal(writes, 1)
  } finally { close() }
})

test('missing desktop active session is read-only and cannot be started from this page', async () => {
  const state = snapshot(); state.active = null; let writes = 0
  const close = await mount({ get: async () => ({ data: state }), post: async () => { writes++ } })
  try { key(); await flush(); assert.equal(writes, 0); assert.equal(query('.switch-button').disabled, true); assert.match(query('.active-hint').textContent, /等待软件/) }
  finally { close() }
})
