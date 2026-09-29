import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/' })
globalThis.window = dom.window
globalThis.document = dom.window.document
globalThis.Element = dom.window.Element
globalThis.SVGElement = dom.window.SVGElement
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const mailBody = await import('../utils/mailBody.js')

test('mail controls switch language while Chinese email content and links stay unchanged', async () => {
  const source = await readFile(new URL('./MailBody.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: 'mail-locale-test', inlineTemplate: true }).content
    .replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_line, binding, path) =>
      'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];')
    .replace('export default', 'return')
  const component = new Function('modules', compiled)({ vue: Vue, '../i18n/index.js': i18n, '../utils/mailBody': mailBody })
  const props = Vue.reactive({ html: '<h2>你的订单已发货</h2><p>明天送达。金额：45.19 元。</p><a href="https://example.com/order">追踪订单</a>', text: '' })
  const app = Vue.createApp({ render: () => Vue.h(component, props) })
  try {
    i18n.setLocale('zh-CN')
    app.mount('#root'); await Vue.nextTick()
    const originalDocument = document.querySelector('iframe').getAttribute('srcdoc')
    i18n.setLocale('en-US'); await Vue.nextTick()
    assert.equal(document.querySelector('.body-toolbar strong').textContent, 'Email Body')
    assert.equal(document.querySelector('.body-modes button').textContent, 'Main content')
    assert.equal(document.querySelector('iframe').getAttribute('srcdoc'), originalDocument)
    assert.match(originalDocument, /你的订单已发货|追踪订单/)
    props.html = ''; await Vue.nextTick()
    assert.match(document.querySelector('iframe').getAttribute('srcdoc'), /No email body is available/)
  } finally {
    app.unmount()
    i18n.setLocale('zh-CN')
  }
})
