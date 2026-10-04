import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as mobileAccess from '../utils/mobileAccess.js'

const dom = new JSDOM('<div id="app"></div>', { url: 'https://inbox.example.ts.net/', pretendToBeVisual: true })
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, SVGElement: dom.window.SVGElement, Event: dom.window.Event, MutationObserver: dom.window.MutationObserver })
const Vue = await import('vue')
const locale = Vue.ref('zh-CN')
const i18n = { useI18n: () => ({ t: value => String(value ?? ''), locale, setLocale: value => { locale.value = value } }) }
const shellSource = await readFile(new URL('./MobileAccessShell.vue', import.meta.url), 'utf8')
const runtimeSource = await readFile(new URL('../RuntimeWorkspace.vue', import.meta.url), 'utf8')
const tick = async () => { for (let index = 0; index < 30; index++) { await Promise.resolve(); await Vue.nextTick() } }
const rejection = (status, message = 'Request failed') => Object.assign(new Error(message), { status })

function compile(source, modules, expose) {
  const { descriptor } = parse(source.replace('</script>', `\ndefineExpose({ ${expose} })\n</script>`))
  const script = compileScript(descriptor, { id: 'mobile-shell-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    assert.ok(path in modules, `Unmocked import: ${path}`)
    return 'const ' + (binding.startsWith('{') ? binding.replace(/\bas\b/g, ':') : binding) + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function interceptors() {
  const create = () => ({ handlers: [], use(fulfilled, rejected) { this.handlers.push({ fulfilled, rejected }); return this.handlers.length - 1 }, eject(id) { this.handlers[id] = null } })
  return { interceptors: { request: create(), response: create() } }
}
function draftComponent({ teleport = false } = {}) {
  const lifecycle = { mounted: 0, unmounted: 0 }
  const component = { setup() {
    const draft = Vue.ref('')
    Vue.onMounted(() => { lifecycle.mounted++ }); Vue.onBeforeUnmount(() => { lifecycle.unmounted++ })
    return () => {
      const editor = Vue.h('textarea', { class: 'test-draft', value: draft.value, onInput: event => { draft.value = event.target.value } })
      return teleport ? Vue.h('section', { class: 'test-workspace' }, [Vue.h(Vue.Teleport, { to: 'body' }, [Vue.h('div', { class: 'test-modal', role: 'dialog' }, [editor])])]) : Vue.h('section', { class: 'test-workspace' }, [editor])
    }
  } }
  return { component, lifecycle }
}
async function mount(component, options = {}) {
  const props = Vue.reactive(options.props || {}), panel = Vue.ref(null)
  const app = Vue.createApp({ setup: () => () => Vue.h(component, { ...props, ref: panel }, options.slots || {}) })
  app.mount('#app'); await tick()
  let disposed = false
  return { vm: panel.value, props, cleanup() { if (disposed) return; disposed = true; app.unmount(); document.querySelector('#app').innerHTML = ''; locale.value = 'zh-CN' } }
}
async function mountShell(options = {}) {
  dom.reconfigure({ url: options.url || 'https://inbox.example.ts.net/' })
  const axios = interceptors(), calls = [], draft = draftComponent(options)
  const readJson = async (url, config) => { calls.push({ url, config }); return options.readJson ? options.readJson(url, config) : { mobile: true, authenticated: false } }
  const component = compile(shellSource, { vue: Vue, axios, '../i18n/index.js': i18n, '../utils/mobileAccess.js': { ...mobileAccess, readJson } }, 'mobile, ready, authenticated, started, checking, pairing, error, pairCode, deviceName, initialize, pair')
  const app = await mount(component, { slots: { default: () => Vue.h(draft.component) } })
  return { ...app, calls, axios, lifecycle: draft.lifecycle }
}
async function mountRuntime(options = {}) {
  dom.reconfigure({ url: options.mobile === false ? 'http://localhost/' : 'https://inbox.example.ts.net/' })
  const calls = [], draft = draftComponent(options), readJson = async (url, config) => { calls.push({ url, config }); return options.readJson ? options.readJson(url, config) : { mode: 'active', token: 'desktop-token' } }
  const passthrough = { setup: (_props, { slots }) => () => Vue.h('div', {}, slots.default?.()) }
  const component = compile(runtimeSource, {
    vue: Vue, './i18n/index.js': i18n, 'element-plus': { ElConfigProvider: passthrough }, 'element-plus/es/locale/lang/en': {}, 'element-plus/es/locale/lang/zh-cn': {},
    './InboxWorkspace.vue': options.workspace || draft.component,
    './components/StandbySwitch.vue': { emits: ['toggle'], setup: (_props, { emit }) => () => Vue.h('button', { class: 'test-standby-toggle', onClick: () => emit('toggle') }, 'standby') },
    './components/DesktopExitButton.vue': { render: () => Vue.h('button', { class: 'test-desktop-exit' }, 'exit') },
    './utils/mobileAccess.js': { ...mobileAccess, readJson }
  }, 'runtime, requesting, error, workspaceStarted, polling, status, poll, change, toggle, recover, refreshMobile')
  return { ...await mount(component, { props: { mobile: options.mobile !== false, paused: false } }), calls, lifecycle: draft.lifecycle }
}
async function typeDraft(value) { const textarea = document.querySelector('.test-draft'); assert.ok(textarea); textarea.value = value; textarea.dispatchEvent(new Event('input', { bubbles: true })); await tick(); return textarea }
function hiddenOrInert(element) {
  for (let current = element; current; current = current.parentElement) {
    if (current.hasAttribute('inert') || current.hidden || window.getComputedStyle(current).display === 'none' || window.getComputedStyle(current).visibility === 'hidden') return true
  }
  return false
}

test('anonymous mobile context never mounts protected content and pairing must verify the new context', async () => {
  let authenticated = false
  const app = await mountShell({ readJson: async url => {
    if (url === '/api/mobile/pair') { authenticated = true; return { paired: true } }
    return { mobile: true, authenticated }
  } })
  try {
    assert.equal(app.vm.ready, true); assert.equal(app.vm.started, false)
    assert.equal(document.querySelector('.test-draft'), null)
    assert.equal(document.querySelectorAll('.pairing-card form').length, 1)
    app.vm.pairCode = ' ABC123 '; app.vm.deviceName = ' My iPhone '
    await app.vm.pair(); await tick()
    assert.equal(app.vm.authenticated, true)
    assert.equal(app.vm.pairCode, '')
    assert.equal(app.lifecycle.mounted, 1)
    assert.deepEqual(app.calls.map(call => call.url), ['/api/mobile/context', '/api/mobile/pair', '/api/mobile/context'])
    assert.deepEqual(JSON.parse(app.calls[1].config.body), { code: 'ABC123', deviceName: 'My iPhone' })
    assert.equal(document.querySelector('.pairing-screen'), null)
  } finally { app.cleanup() }
})

test('a successful pairing response without authenticated context cannot reveal the workspace', async () => {
  const app = await mountShell()
  try {
    app.vm.pairCode = 'ABC123'; await app.vm.pair(); await tick()
    assert.equal(app.vm.authenticated, false)
    assert.equal(document.querySelector('.test-draft'), null)
    assert.match(document.querySelector('[role=alert]').textContent, /尚未完成设备验证/)
  } finally { app.cleanup() }
})

test('legacy 404 desktop fallback is accepted only on exact loopback hosts', async () => {
  for (const url of ['http://localhost/', 'http://127.0.0.1/', 'http://[::1]/', 'https://inbox.example.ts.net/', 'https://localhost.evil.test/']) {
    const app = await mountShell({ url, readJson: async () => { throw rejection(404) } })
    try {
      const expected = ['localhost', '127.0.0.1', '[::1]'].includes(new URL(url).hostname)
      assert.equal(app.vm.authenticated, expected, url)
      assert.equal(app.lifecycle.mounted, expected ? 1 : 0, url)
    } finally { app.cleanup() }
  }
  const remoteDesktop = await mountShell({ readJson: async () => ({ mobile: false }) })
  try { assert.equal(remoteDesktop.vm.authenticated, false); assert.equal(remoteDesktop.lifecycle.mounted, 0) }
  finally { remoteDesktop.cleanup() }
})

test('401 locks same-origin business requests while preserving the mounted draft for verified re-pairing', async () => {
  const app = await mountShell({ readJson: async () => ({ mobile: true, authenticated: true }) })
  try {
    const textarea = await typeDraft('未保存的日程草稿')
    const responseGuard = app.axios.interceptors.response.handlers.find(Boolean).rejected
    const failure = { config: { url: '/api/tasks' }, response: { status: 401 } }
    await assert.rejects(responseGuard(failure)); await tick()
    assert.equal(app.vm.authenticated, false)
    assert.equal(app.lifecycle.unmounted, 0)
    assert.equal(document.querySelector('.test-draft'), textarea)
    assert.equal(hiddenOrInert(textarea), true)
    const requestGuard = app.axios.interceptors.request.handlers.find(Boolean).fulfilled
    await assert.rejects(requestGuard({ url: '/api/tasks', method: 'post' }), error => error.mobileLocked === true)
    assert.deepEqual(requestGuard({ url: 'https://external.test/api/tasks' }), { url: 'https://external.test/api/tasks' })
    app.vm.pairCode = 'NEW123'; await app.vm.pair(); await tick()
    assert.equal(app.vm.authenticated, true)
    assert.equal(textarea.value, '未保存的日程草稿')
    assert.equal(hiddenOrInert(textarea), false)
    assert.equal(app.lifecycle.mounted, 1)
    const handlers = app.axios.interceptors; app.cleanup()
    assert.ok(handlers.request.handlers.every(handler => handler === null))
    assert.ok(handlers.response.handlers.every(handler => handler === null))
  } finally { app.cleanup() }
})

test('cross-origin 401 and ordinary server errors do not lock the mobile session', async () => {
  const app = await mountShell({ readJson: async () => ({ mobile: true, authenticated: true }) })
  try {
    const reject = app.axios.interceptors.response.handlers.find(Boolean).rejected
    for (const failure of [{ config: { url: 'https://external.test/api/tasks' }, response: { status: 401 } }, { config: { url: '/api/tasks' }, response: { status: 503 } }]) await assert.rejects(reject(failure))
    assert.equal(app.vm.authenticated, true)
  } finally { app.cleanup() }
})

test('desktop context remains unlocked on a business 401 and leaves its guard transparent', async () => {
  const app = await mountShell({ url: 'http://localhost/', readJson: async () => ({ mobile: false }) })
  try {
    const failure = { config: { url: '/api/tasks' }, response: { status: 401 } }
    await assert.rejects(app.axios.interceptors.response.handlers.find(Boolean).rejected(failure))
    assert.equal(app.vm.authenticated, true)
    assert.equal(document.documentElement.classList.contains('mobile-client'), false)
    const config = { url: '/api/runtime/resume', method: 'post' }
    assert.equal(app.axios.interceptors.request.handlers.find(Boolean).fulfilled(config), config)
  } finally { app.cleanup() }
})

test('mobile runtime never submits power actions and preserves drafts through offline and standby states', async context => {
  const posts = []
  context.mock.method(globalThis, 'fetch', async (...args) => { posts.push(args); throw new Error('Unexpected runtime write') })
  let runtime = { mode: 'active', token: 'must-not-be-retained' }
  const app = await mountRuntime({ readJson: async () => { if (runtime instanceof Error) throw runtime; return runtime } })
  try {
    const textarea = await typeDraft('draft survives downtime')
    assert.equal(app.vm.runtime.token, undefined)
    await app.vm.change('standby'); await app.vm.toggle(); await app.vm.recover()
    assert.equal(posts.length, 0)
    assert.equal(document.querySelector('.test-desktop-exit'), null)
    runtime = new Error('Network unavailable'); await app.vm.poll(); await tick()
    assert.equal(app.vm.runtime.mode, 'offline')
    assert.equal(document.querySelector('.test-draft'), textarea)
    assert.equal(hiddenOrInert(textarea), true)
    runtime = { mode: 'standby' }; await app.vm.poll(); await tick()
    assert.equal(document.querySelector('.test-draft'), textarea)
    runtime = { mode: 'active' }; await app.vm.refreshMobile(); await tick()
    assert.equal(textarea.value, 'draft survives downtime')
    assert.equal(hiddenOrInert(textarea), false)
    assert.equal(app.lifecycle.mounted, 1)
    assert.equal(app.lifecycle.unmounted, 0)
  } finally { app.cleanup() }
})

test('mobile runtime stops new polls while locked and resumes on visible foreground or online events', async () => {
  const app = await mountRuntime()
  let resumes = 0
  const resumed = () => { resumes++ }
  window.addEventListener(mobileAccess.MOBILE_RESUME, resumed)
  try {
    app.props.paused = true; await tick()
    const lockedCalls = app.calls.length
    window.dispatchEvent(new Event('online')); window.dispatchEvent(new Event('focus')); await app.vm.poll(); await tick()
    assert.equal(app.calls.length, lockedCalls)
    app.props.paused = false; await tick()
    assert.equal(app.calls.length, lockedCalls + 1)
    assert.ok(resumes > 0)
    const beforeFocus = app.calls.length
    window.dispatchEvent(new Event('focus')); await tick()
    assert.equal(app.calls.length, beforeFocus + 1)
    const beforeOnline = app.calls.length
    window.dispatchEvent(new Event('online')); await tick()
    assert.equal(app.calls.length, beforeOnline + 1)
    app.cleanup(); const beforeCleanup = app.calls.length
    window.dispatchEvent(new Event('online')); await tick()
    assert.equal(app.calls.length, beforeCleanup)
  } finally { window.removeEventListener(mobileAccess.MOBILE_RESUME, resumed); app.cleanup() }
})

test('runtime 401 requests shell locking without unmounting the workspace', async () => {
  let fail = false, expired = 0
  const onExpired = () => { expired++ }
  window.addEventListener(mobileAccess.MOBILE_AUTH_REQUIRED, onExpired)
  const app = await mountRuntime({ readJson: async () => { if (fail) throw rejection(401); return { mode: 'active' } } })
  try {
    const textarea = await typeDraft('keep auth draft')
    fail = true; await app.vm.poll(); await tick()
    assert.equal(expired, 1)
    assert.equal(document.querySelector('.test-draft'), textarea)
    assert.equal(app.lifecycle.unmounted, 0)
  } finally { window.removeEventListener(mobileAccess.MOBILE_AUTH_REQUIRED, onExpired); app.cleanup() }
})

test('desktop runtime preserves the original fresh-token resume flow', async context => {
  const posts = []
  context.mock.method(globalThis, 'fetch', async (url, config) => { posts.push({ url, config }); return { ok: true, status: 200, json: async () => ({ mode: 'resuming' }) } })
  let token = 0
  const app = await mountRuntime({ mobile: false, readJson: async () => ({ mode: 'standby', token: `desktop-${++token}` }) })
  try {
    assert.equal(document.querySelector('.test-desktop-exit') !== null, true)
    await app.vm.recover(); await tick()
    assert.deepEqual(posts, [{ url: '/api/runtime/resume', config: { method: 'POST', headers: { 'X-Runtime-Token': 'desktop-2' } } }])
    assert.equal(app.vm.runtime.mode, 'resuming')
  } finally { app.cleanup() }
})

test('review regression: locking also isolates body-teleported editors while preserving their drafts', async () => {
  const app = await mountShell({ teleport: true, readJson: async () => ({ mobile: true, authenticated: true }) })
  try {
    const textarea = await typeDraft('private teleported draft')
    window.dispatchEvent(new Event(mobileAccess.MOBILE_AUTH_REQUIRED)); await tick()
    assert.equal(app.vm.authenticated, false)
    assert.equal(app.lifecycle.unmounted, 0)
    assert.equal(hiddenOrInert(textarea), true, 'A body Teleport escapes v-show/inert on the workspace wrapper')
    app.vm.pairCode = 'NEW123'; await app.vm.pair(); await tick()
    assert.equal(textarea.value, 'private teleported draft')
    assert.equal(hiddenOrInert(textarea), false)
  } finally { app.cleanup() }
})

test('offline runtime isolates an existing Teleport and restores the same editor when the computer returns', async () => {
  let offline = false
  const app = await mountRuntime({ teleport: true, readJson: async () => { if (offline) throw new Error('Computer offline'); return { mode: 'active' } } })
  try {
    const textarea = await typeDraft('offline portal draft')
    offline = true; await app.vm.poll(); await tick()
    assert.equal(hiddenOrInert(textarea), true)
    assert.equal(document.querySelector('.test-modal').getAttribute('aria-hidden'), 'true')
    offline = false; await app.vm.refreshMobile(); await tick()
    assert.equal(document.querySelector('.test-draft'), textarea)
    assert.equal(textarea.value, 'offline portal draft')
    assert.equal(hiddenOrInert(textarea), false)
    assert.equal(document.querySelector('.test-modal').hasAttribute('aria-hidden'), false)
  } finally { app.cleanup() }
})

test('overlapping auth and runtime locks isolate newly inserted portals and restore original accessibility attributes', async () => {
  const previousPortal = document.createElement('div'), newPortal = document.createElement('div')
  previousPortal.setAttribute('inert', ''); previousPortal.setAttribute('aria-hidden', 'false')
  document.body.append(previousPortal)
  try {
    mobileAccess.setMobileContentLock('test-auth', true)
    mobileAccess.setMobileContentLock('test-runtime', true)
    newPortal.innerHTML = '<input value="keep this draft">'; document.body.append(newPortal); await tick()
    assert.equal(newPortal.hasAttribute('inert'), true)
    assert.equal(newPortal.getAttribute('aria-hidden'), 'true')
    assert.equal(previousPortal.getAttribute('aria-hidden'), 'true')
    assert.equal(document.querySelector('#app').hasAttribute('inert'), false)
    mobileAccess.setMobileContentLock('test-auth', false)
    assert.equal(newPortal.hasAttribute('inert'), true)
    mobileAccess.setMobileContentLock('test-runtime', false)
    assert.equal(newPortal.hasAttribute('inert'), false)
    assert.equal(newPortal.hasAttribute('aria-hidden'), false)
    assert.equal(previousPortal.hasAttribute('inert'), true)
    assert.equal(previousPortal.getAttribute('aria-hidden'), 'false')
    assert.equal(newPortal.querySelector('input').value, 'keep this draft')
    assert.equal(document.documentElement.classList.contains('mobile-content-locked'), false)
  } finally { mobileAccess.setMobileContentLock('test-auth', false); mobileAccess.setMobileContentLock('test-runtime', false); previousPortal.remove(); newPortal.remove() }
})

test('returning from hidden background refreshes mobile runtime only after the page becomes visible', async () => {
  const app = await mountRuntime()
  let hidden = true
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  try {
    const beforeHidden = app.calls.length
    document.dispatchEvent(new Event('visibilitychange')); await tick()
    assert.equal(app.calls.length, beforeHidden)
    hidden = false; document.dispatchEvent(new Event('visibilitychange')); await tick()
    assert.equal(app.calls.length, beforeHidden + 1)
  } finally { delete document.hidden; app.cleanup() }
})

test('readJson uses uncached same-origin cookies, preserves HTTP status, and bounds stalled requests', async context => {
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, config) => {
    calls.push({ url, config })
    if (url === '/api/mobile/context') return { ok: true, json: async () => ({ mobile: true, authenticated: false }) }
    if (url === '/api/runtime/status') return { ok: false, status: 401, json: async () => ({ message: '设备已撤销' }) }
    return new Promise((_resolve, reject) => config.signal.addEventListener('abort', () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })), { once: true }))
  })
  assert.deepEqual(await mobileAccess.readJson('/api/mobile/context'), { mobile: true, authenticated: false })
  assert.equal(calls[0].config.credentials, 'same-origin')
  assert.equal(calls[0].config.cache, 'no-store')
  await assert.rejects(mobileAccess.readJson('/api/runtime/status'), error => error.status === 401 && error.message === '设备已撤销')
  await assert.rejects(mobileAccess.readJson('/api/mobile/pair', { method: 'POST' }, 5), /连接超时/)
  assert.equal(calls.at(-1).config.signal.aborted, true)
})

test('the pairing screen can scroll while a preserved Element Plus dialog has locked body scrolling', async () => {
  const dialogCss = await readFile(new URL('../../node_modules/element-plus/theme-chalk/el-dialog.css', import.meta.url), 'utf8')
  const siteCss = await readFile(new URL('../style.css', import.meta.url), 'utf8')
  const popupRule = dialogCss.match(/\.el-popup-parent--hidden\s*\{[^}]+\}/)?.[0]
  assert.ok(popupRule, 'Expected the installed Element Plus body scroll-lock rule')
  const style = document.createElement('style')
  style.textContent = popupRule + '\n' + siteCss
  document.head.append(style)
  document.body.classList.add('el-popup-parent--hidden')
  try {
    assert.equal(window.getComputedStyle(document.body).overflow, 'hidden')
    mobileAccess.setMobileContentLock('test-scroll', true)
    assert.notEqual(window.getComputedStyle(document.body).overflow, 'hidden', 'A preserved dialog must not prevent the pairing screen from scrolling')
    mobileAccess.setMobileContentLock('test-scroll', false)
    assert.equal(window.getComputedStyle(document.body).overflow, 'hidden', 'The open dialog regains its original scroll lock after authentication')
  } finally { mobileAccess.setMobileContentLock('test-scroll', false); document.body.classList.remove('el-popup-parent--hidden'); style.remove() }
})
