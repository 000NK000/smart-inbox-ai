import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const source = readFileSync(new URL('../desktop/bridge.js', import.meta.url), 'utf8')
function page({ origin = 'http://127.0.0.1:5173', frame = false, active = true, native = true } = {}) {
  const sent = []
  const window = { chrome: native ? { webview: { postMessage: value => sent.push(value) } } : {} }
  window.top = frame ? {} : window
  runInNewContext(source, { window, location: { origin }, navigator: { userActivation: { isActive: active } } })
  return { window, sent }
}
test('external pages, email frames and ordinary browsers cannot use native exit', () => {
  for (const settings of [{ origin: 'https://example.com' }, { frame: true }, { native: false }]) {
    const context = page(settings)
    assert.equal(context.window.smartInboxDesktop, undefined)
    assert.equal(context.sent.length, 0)
  }
})
test('native exit requires a current user gesture and sends only the fixed command', () => {
  const inactive = page({ active: false })
  assert.equal(inactive.window.smartInboxDesktop.exit(), false)
  assert.equal(inactive.sent.length, 0)
  const active = page()
  assert.equal(active.window.smartInboxDesktop.exit(), true)
  assert.deepEqual(active.sent, ['smart-inbox:exit'])
  assert.equal(Object.isFrozen(active.window.smartInboxDesktop), true)
})
