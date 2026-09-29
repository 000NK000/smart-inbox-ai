import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const source = readFileSync(new URL('./bridge.js', import.meta.url), 'utf8')
function page({ origin = 'http://127.0.0.1:5173', frame = false, native = true } = {}) {
  const sent = []
  const window = { chrome: native ? { webview: { postMessage: value => sent.push(value) } } : {} }
  window.top = frame ? {} : window
  runInNewContext(source, { window, location: { origin }, navigator: { userActivation: { isActive: false } } })
  return { window, sent }
}
test('locale bridge accepts only the two supported UI languages, without requiring a startup gesture', () => {
  const { window, sent } = page()
  const bridge = window.smartInboxDesktop
  for (const invalid of ['', 'fr', 'EN-us', 'en-US;exit', '../exit', null, undefined, {}, ['en-US']]) {
    assert.equal(bridge.setLocale(invalid), false)
  }
  assert.deepEqual(sent, [])
  assert.equal(bridge.setLocale('en-US'), true)
  assert.equal(bridge.setLocale('zh-CN'), true)
  assert.deepEqual(sent, ['smart-inbox:locale:en-US', 'smart-inbox:locale:zh-CN'])
  assert.equal(bridge.exit(), false, 'Locale updates must not relax the native exit gesture requirement')
  assert.equal(Object.isFrozen(bridge), true)
})
test('locale bridge is not exposed to email frames, external origins, or ordinary browsers', () => {
  for (const settings of [{ frame: true }, { origin: 'https://127.0.0.1:5173' }, { origin: 'http://localhost:5173' }, { origin: 'https://example.com' }, { native: false }]) {
    const { window, sent } = page(settings)
    assert.equal(window.smartInboxDesktop, undefined)
    assert.deepEqual(sent, [])
  }
})
