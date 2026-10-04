import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'
import english from '../i18n/mobile.en.js'

const dom = new JSDOM('<div id="mobile-admin-test"></div>', { url: 'http://localhost/' })
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, SVGElement: dom.window.SVGElement })
const Vue = await import('vue')
const locale = Vue.ref('zh-CN')
const i18n = { useI18n: () => ({ dateLocale: locale, t: (message, params = {}) => {
  const value = locale.value === 'en-US' ? (english[message] ?? message) : message
  return value.replace(/\{(\w+)\}/g, (match, key) => Object.hasOwn(params, key) ? String(params[key]) : match)
} }) }
const source = await readFile(new URL('./MobileConnectionPanel.vue', import.meta.url), 'utf8')
const tick = async () => { for (let index = 0; index < 25; index++) { await Promise.resolve(); await Vue.nextTick() } }
const expiresLater = () => new Date(Date.now() + 600000).toISOString()
const seed = () => ({ configured: true, running: true, origin: 'https://my-inbox.example.ts.net', message: '连接已准备就绪。', network: { installed: true, connected: true, needsLogin: false }, devices: [{ id: 'phone-1', name: '我的 iPhone', createdAt: '2026-09-30T12:00:00Z', lastSeenAt: '2026-09-30T13:00:00Z' }] })

function compile(modules) {
  const expose = 'status, loading, busy, ready, fresh, error, notice, pairing, expiredAt, approvalUrl, revokingId, canPair, needsSetup, safeOrigin, safeApprovalUrl, updateExpiry, load, generatePairing, revokeDevice, setupNetwork'
  const { descriptor } = parse(source.replace('</script>', `\ndefineExpose({ ${expose} })\n</script>`))
  const script = compileScript(descriptor, { id: 'mobile-admin-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    assert.ok(path in modules, `Unmocked import: ${path}`)
    return 'const ' + (binding.startsWith('{') ? binding.replace(/\bas\b/g, ':') : binding) + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
async function mountPanel(options = {}) {
  locale.value = 'zh-CN'
  const state = options.state || seed(), calls = [], opened = []
  window.open = (...args) => { opened.push(args); return null }
  let tokenNumber = 0, disposed = false
  const axios = {
    async get(url, config) {
      calls.push({ method: 'get', url, config })
      if (options.get) { const result = await options.get(url, config, state); if (result !== undefined) return result }
      if (url === '/api/runtime/status') return { data: { token: `runtime-${++tokenNumber}` } }
      assert.equal(url, '/api/mobile-admin/status')
      return { data: structuredClone(state) }
    },
    async post(url, body, config) {
      calls.push({ method: 'post', url, body, config })
      if (options.post) { const result = await options.post(url, body, config, state); if (result !== undefined) return result }
      assert.equal(url, '/api/mobile-admin/pair')
      return { data: { code: '57290418', expiresAt: expiresLater(), origin: state.origin } }
    },
    async delete(url, config) {
      calls.push({ method: 'delete', url, config })
      if (options.delete) { const result = await options.delete(url, config, state); if (result !== undefined) return result }
      state.devices = state.devices.filter(device => `/api/mobile-admin/devices/${encodeURIComponent(device.id)}` !== url)
      return { data: {} }
    }
  }
  const app = Vue.createApp(compile({ vue: Vue, axios, '../i18n/index.js': i18n }))
  const vm = app.mount('#mobile-admin-test')
  await tick()
  return { vm, calls, opened, state, cleanup() { if (disposed) return; disposed = true; app.unmount(); document.querySelector('#mobile-admin-test').innerHTML = ''; locale.value = 'zh-CN' } }
}
async function click(selector) { const element = typeof selector === 'string' ? document.querySelector(selector) : selector; assert.ok(element, `Missing element: ${selector}`); element.click(); await tick() }

test('unconfigured status displays the backend explanation and never initiates setup or pairing', async () => {
  const state = seed(); Object.assign(state, { configured: false, running: false, origin: '', message: '请完成电脑端配置。<img src=x onerror=alert(1)>', devices: [], network: { installed: false, connected: false, needsLogin: true } })
  const app = await mountPanel({ state })
  try {
    assert.deepEqual(app.calls.map(call => call.url), ['/api/mobile-admin/status'])
    assert.equal(document.querySelector('.generate-code').disabled, true)
    assert.equal(document.querySelector('.setup-network').disabled, false)
    assert.match(document.querySelector('.backend-message').textContent, /<img src=x/)
    assert.equal(document.querySelector('img'), null)
    assert.equal(document.querySelector('.mobile-origin'), null)
    assert.match(document.querySelector('.empty').textContent, /还没有配对设备/)
    assert.match(document.querySelector('.network-setup').textContent, /同一个 Tailscale 账号/)
  } finally { app.cleanup() }
})

test('all requests are bounded and every pairing mutation fetches a fresh runtime token', async () => {
  const app = await mountPanel()
  try {
    assert.equal(app.calls.some(call => call.method !== 'get'), false)
    const link = document.querySelector('.mobile-origin')
    assert.equal(link.href, app.state.origin + '/')
    assert.equal(link.rel, 'noopener noreferrer')
    await click('.generate-code'); await click('.generate-code')
    const mutations = app.calls.filter(call => call.method === 'post')
    assert.equal(mutations.length, 2)
    assert.deepEqual(mutations.map(call => call.config.headers['X-Runtime-Token']), ['runtime-1', 'runtime-2'])
    assert.deepEqual(mutations[0].body, {})
    for (const call of mutations) assert.equal(app.calls[app.calls.indexOf(call) - 1].url, '/api/runtime/status')
    for (const call of app.calls) { assert.equal(call.config.timeout, 15000); assert.ok(call.config.signal) }
    assert.equal(document.querySelector('.pairing-code').textContent, '57290418')
    assert.equal(document.querySelector('.pairing-expiry time').getAttribute('datetime'), app.vm.pairing.expiresAt)
    assert.equal(app.opened.length, 0)
    assert.doesNotMatch(source, /localStorage|sessionStorage|navigator\.clipboard|document\.cookie/)
  } finally { app.cleanup() }
})

test('only HTTPS origins are clickable and provider sign-in URLs enforce the exact Tailscale host', async () => {
  const app = await mountPanel()
  try {
    for (const raw of ['javascript:alert(1)', 'http://my-inbox.example.ts.net', 'https://user:pass@my-inbox.example.ts.net', 'https://my-inbox.example.ts.net/pair', 'https://my-inbox.example.ts.net/?code=secret', 'https://my-inbox.example.ts.net/#code', ' https://my-inbox.example.ts.net', 'https://my-inbox.example.ts.net\\@evil.test']) assert.equal(app.vm.safeOrigin(raw), null, raw)
    for (const raw of ['javascript:alert(1)', 'http://login.tailscale.com/a', 'https://login.tailscale.com.evil.test/a', 'https://login.tailscale.com@evil.test/a', 'https://evil.test@login.tailscale.com/a', 'https://login.tailscale.com:8443/a']) assert.equal(app.vm.safeApprovalUrl(raw), null, raw)
    assert.equal(app.vm.safeApprovalUrl('https://login.tailscale.com/a/abc'), 'https://login.tailscale.com/a/abc')
    app.state.origin = 'javascript:alert(1)'; await app.vm.load(); await tick()
    assert.equal(document.querySelector('.mobile-origin'), null)
    assert.equal(document.querySelector('.generate-code').disabled, true)
    assert.match(document.querySelector('.unavailable-origin').textContent, /javascript:/)
  } finally { app.cleanup() }
})

test('network setup uses runtime authorization and shows a user-clicked official sign-in link', async () => {
  const state = seed(); Object.assign(state, { configured: false, running: false, origin: '', network: { installed: true, connected: false, needsLogin: true } })
  const app = await mountPanel({ state, post: async url => {
    assert.equal(url, '/api/mobile-admin/setup')
    return { data: { needsLogin: true, approvalUrl: 'https://login.tailscale.com/a/abc', message: '请完成账号登录。' } }
  } })
  try {
    await click('.setup-network')
    const mutation = app.calls.find(call => call.method === 'post')
    assert.equal(mutation.config.headers['X-Runtime-Token'], 'runtime-1')
    assert.equal(mutation.config.timeout, 90000)
    assert.equal(app.calls[app.calls.indexOf(mutation) - 1].url, '/api/runtime/status')
    const link = document.querySelector('.approval-link')
    assert.equal(link.href, 'https://login.tailscale.com/a/abc')
    assert.equal(link.rel, 'noopener noreferrer')
    assert.equal(app.opened.length, 0)
    assert.equal(app.calls.some(call => call.url === '/api/mobile-admin/pair'), false)
    assert.equal(document.querySelector('.generate-code').disabled, true)
  } finally { app.cleanup() }
})

test('successful network setup refreshes status and enables explicit pairing without generating a code', async () => {
  const state = seed(); Object.assign(state, { configured: false, running: false, network: { installed: true, connected: false } })
  const app = await mountPanel({ state, post: async (url, _body, _config, current) => {
    assert.equal(url, '/api/mobile-admin/setup'); Object.assign(current, seed())
    return { data: { configured: true, running: true, origin: current.origin } }
  } })
  try {
    await click('.setup-network')
    assert.equal(app.vm.canPair, true)
    assert.equal(document.querySelector('.setup-network'), null)
    assert.equal(document.querySelector('.generate-code').disabled, false)
    assert.equal(document.querySelector('.pairing-code'), null)
    assert.equal(app.calls.filter(call => call.url === '/api/mobile-admin/status').length, 2)
  } finally { app.cleanup() }
})

test('unverified Tailscale sign-in responses cannot create a link or open a browser', async () => {
  const state = seed(); state.configured = false
  const app = await mountPanel({ state, post: async () => ({ data: { approvalUrl: 'https://login.tailscale.com.evil.test/secret' } }) })
  try {
    await click('.setup-network')
    assert.equal(document.querySelector('.approval-link'), null)
    assert.equal(app.vm.approvalUrl, '')
    assert.equal(app.opened.length, 0)
    assert.match(document.querySelector('[role=alert]').textContent, /登录地址未通过验证/)
  } finally { app.cleanup() }
})

test('device revocation requires inline confirmation, permits cancellation, and encodes the selected ID', async () => {
  const state = seed(); state.devices[0].id = 'phone/with?special'
  const app = await mountPanel({ state })
  try {
    await app.vm.revokeDevice(app.state.devices[0]); assert.equal(app.calls.some(call => call.method === 'delete'), false)
    await click('.revoke-device')
    assert.match(document.querySelector('.revoke-confirm').textContent, /我的 iPhone/)
    assert.equal(app.calls.some(call => call.url === '/api/runtime/status'), false)
    await click('.cancel-revoke'); assert.equal(document.querySelector('.revoke-confirm'), null)
    await click('.revoke-device'); await click('.confirm-revoke')
    const deleted = app.calls.find(call => call.method === 'delete')
    assert.equal(deleted.url, '/api/mobile-admin/devices/phone%2Fwith%3Fspecial')
    assert.equal(deleted.config.headers['X-Runtime-Token'], 'runtime-1')
    assert.equal(app.calls[app.calls.indexOf(deleted) - 1].url, '/api/runtime/status')
    assert.equal(document.querySelector('.device-card'), null)
    assert.match(app.vm.notice, /已撤销/)
    assert.equal(app.calls.filter(call => call.url === '/api/mobile-admin/status').length, 2)
  } finally { app.cleanup() }
})

test('pairing codes disappear at expiration and remain hidden after unmount', async context => {
  const app = await mountPanel()
  try {
    await click('.generate-code')
    const expiry = app.vm.pairing.expiresAt
    context.mock.method(Date, 'now', () => new Date(expiry).getTime() + 1)
    app.vm.updateExpiry(); await tick()
    assert.equal(app.vm.pairing, null)
    assert.equal(document.querySelector('.pairing-code'), null)
    assert.match(document.querySelector('.pairing-placeholder').textContent, /已过期/)
    assert.equal(document.querySelector('.generate-code').disabled, false)
    context.mock.restoreAll()
    await click('.generate-code')
    assert.ok(app.vm.pairing)
    const signal = app.calls[0].config.signal
    app.cleanup()
    assert.equal(signal.aborted, true)
    assert.equal(app.vm.pairing, null)
  } finally { app.cleanup() }
})

test('expired, mismatched-origin, and invalid-expiration responses never display a pairing code', async () => {
  for (const data of [
    { code: 'secret', origin: seed().origin, expiresAt: '2000-01-01T00:00:00Z' },
    { code: 'secret', origin: 'https://another.example.ts.net', expiresAt: expiresLater() },
    { code: 'secret', origin: seed().origin, expiresAt: 'not-a-date' }
  ]) {
    const app = await mountPanel({ post: async () => ({ data }) })
    try { await click('.generate-code'); assert.equal(document.querySelector('.pairing-code'), null); assert.equal(app.vm.pairing, null) }
    finally { app.cleanup() }
  }
})

test('a status refresh clears the pairing code when its origin or network availability changes', async () => {
  for (const change of [state => { state.origin = 'https://new-inbox.example.ts.net' }, state => { state.network.connected = false }, state => { state.running = false }]) {
    const app = await mountPanel()
    try {
      await click('.generate-code'); assert.ok(app.vm.pairing)
      change(app.state); await click('.refresh-status')
      assert.equal(app.vm.pairing, null)
      assert.equal(document.querySelector('.pairing-code'), null)
      if (!app.state.running || !app.state.network.connected) {
        assert.equal(document.querySelector('.generate-code').disabled, true)
        assert.doesNotMatch(document.querySelector('.connection-status').className, /connected/)
      }
    } finally { app.cleanup() }
  }
})

test('initial status timeouts exit loading and a read-only retry restores the panel', async () => {
  let fail = true
  const app = await mountPanel({ get: async () => { if (fail) throw { code: 'ECONNABORTED' } } })
  try {
    assert.equal(app.vm.loading, false)
    assert.equal(app.vm.ready, false)
    assert.match(document.querySelector('[role=alert]').textContent, /请求超时/)
    fail = false; await click('.retry-status')
    assert.equal(app.vm.ready, true)
    assert.equal(app.vm.error, '')
    assert.equal(app.calls.some(call => call.method !== 'get'), false)
  } finally { app.cleanup() }
})

test('timed out pairing is never automatically retried and requires a status refresh before another attempt', async () => {
  let fail = true
  const app = await mountPanel({ post: async () => { if (fail) throw { code: 'ETIMEDOUT' } } })
  try {
    await click('.generate-code')
    assert.equal(app.calls.filter(call => call.method === 'post').length, 1)
    assert.equal(app.vm.busy, '')
    assert.equal(app.vm.pairing, null)
    assert.equal(document.querySelector('.generate-code').disabled, true)
    fail = false; await click('.retry-status')
    assert.equal(app.calls.filter(call => call.method === 'post').length, 1)
    await click('.generate-code')
    assert.equal(app.calls.filter(call => call.method === 'post').length, 2)
    assert.ok(app.vm.pairing)
  } finally { app.cleanup() }
})

test('missing runtime authorization prevents mutations, while in-flight authorization is canceled on unmount', async () => {
  const missing = await mountPanel({ get: async url => url === '/api/runtime/status' ? { data: {} } : undefined })
  try { await click('.generate-code'); assert.equal(missing.calls.some(call => call.method === 'post'), false); assert.ok(missing.vm.error) }
  finally { missing.cleanup() }
  let resolveToken
  const app = await mountPanel({ get: async url => url === '/api/runtime/status' ? new Promise(resolve => { resolveToken = resolve }) : undefined })
  try {
    const pending = app.vm.generatePairing(); await tick()
    app.cleanup(); resolveToken({ data: { token: 'late-token' } }); await pending
    assert.equal(app.calls.some(call => call.method === 'post'), false)
    assert.equal(app.vm.pairing, null)
  } finally { app.cleanup() }
})

test('language switching translates interface copy while preserving device names and safe plain text', async () => {
  const state = seed(); state.devices[0].name = '我的手机 <script>alert(1)</script>'; state.devices[0].lastSeenAt = null
  const app = await mountPanel({ state })
  try {
    locale.value = 'en-US'; await tick()
    assert.equal(document.querySelector('.mobile-intro h2').textContent, 'Your workspace, connected to your phone.')
    assert.equal(document.querySelector('.generate-code').textContent, 'Generate pairing code')
    assert.match(document.querySelector('.device-info').textContent, /我的手机 <script>alert\(1\)<\/script>/)
    assert.match(document.querySelector('.device-info').textContent, /No record yet/)
    assert.equal(document.querySelector('script'), null)
    await click('.revoke-device')
    assert.match(document.querySelector('.revoke-confirm').textContent, /Revoke access for/)
    for (const match of source.matchAll(/\bt\('([^']+)'/g)) assert.ok(Object.hasOwn(english, match[1]), `Missing translation: ${match[1]}`)
    for (const match of source.matchAll(/(?:return|error\.value =|notice\.value =) '([^']*[\u3400-\u9fff][^']*)'/g)) assert.ok(Object.hasOwn(english, match[1]), `Missing state translation: ${match[1]}`)
  } finally { app.cleanup() }
})
