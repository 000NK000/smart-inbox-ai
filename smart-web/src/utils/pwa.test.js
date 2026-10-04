import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { runInNewContext } from 'node:vm'

const source = await readFile(new URL('../../public/sw.js', import.meta.url), 'utf8')
function worker({ offline = false } = {}) {
  const handlers = {}, cached = [], network = [], responses = []
  const cache = { async addAll(paths) { cached.push(...paths) }, async match() { return null }, async put(request) { cached.push(request.url) } }
  const fallback = { offline: true }
  const context = {
    URL, Set,
    self: { location: { origin: 'https://private.example' }, addEventListener: (name, handler) => { handlers[name] = handler }, skipWaiting: async () => {}, clients: { claim: async () => {} } },
    caches: { async open() { return cache }, async match(url) { assert.equal(url, '/offline.html'); return fallback }, async keys() { return [] }, async delete() {} },
    fetch: async request => { network.push(request.url); if (offline) throw new Error('offline'); return { ok: true, type: 'basic', clone() { return this } } }
  }
  runInNewContext(source, context)
  async function fetchEvent(url, options = {}) {
    let response
    handlers.fetch({ request: { method: 'GET', mode: 'cors', url, ...options }, respondWith(promise) { response = promise; responses.push(url) } })
    return response ? await response : undefined
  }
  return { handlers, cached, network, responses, fetchEvent, fallback }
}

test('service worker installs only public assets and never precaches business routes', async () => {
  const api = worker(); let install
  api.handlers.install({ waitUntil(promise) { install = promise } }); await install
  assert.ok(api.cached.includes('/offline.html'))
  assert.ok(api.cached.includes('/manifest.webmanifest'))
  assert.ok(api.cached.every(path => path.startsWith('/icons/') || ['/offline.html', '/manifest.webmanifest'].includes(path)))
})
test('API reads, writes, auth callbacks, and external resources bypass the cache entirely', async () => {
  const api = worker()
  for (const path of ['/api/stocks/portfolio', '/api/mails/1', '/api/mobile/context', '/api/mobile/pair', '/api/stocks/auth/gpt/callback']) await api.fetchEvent(`https://private.example${path}`, { mode: 'navigate' })
  await api.fetchEvent('https://private.example/assets/upload.js', { method: 'POST' })
  await api.fetchEvent('https://other.example/assets/image.png')
  assert.equal(api.responses.length, 0)
  assert.equal(api.cached.length, 0)
})
test('only allowlisted same-origin static files are cached, not arbitrary content', async () => {
  const api = worker()
  await api.fetchEvent('https://private.example/assets/app-123.js')
  assert.deepEqual(api.cached, ['https://private.example/assets/app-123.js'])
  for (const path of ['/reports/private.png', '/api%2fstocks/portfolio', '/assets/private.json', '/assets/app-123.js?token=private']) await api.fetchEvent(`https://private.example${path}`)
  assert.equal(api.cached.length, 1)
})
test('offline navigation returns only the public offline screen and never stores navigation responses', async () => {
  const online = worker(); await online.fetchEvent('https://private.example/', { mode: 'navigate' })
  assert.equal(online.cached.length, 0)
  const offline = worker({ offline: true })
  assert.equal(await offline.fetchEvent('https://private.example/', { mode: 'navigate' }), offline.fallback)
  assert.equal(offline.cached.length, 0)
})
