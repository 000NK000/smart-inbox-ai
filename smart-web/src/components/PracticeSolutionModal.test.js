import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/', pretendToBeVisual: true })
globalThis.window = dom.window
globalThis.document = dom.window.document
globalThis.Element = dom.window.Element
globalThis.SVGElement = dom.window.SVGElement
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const flush = async () => { for (let i = 0; i < 20; i++) { await Promise.resolve(); await Vue.nextTick() } }

function compile(source, modules) {
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: 'solution-test', inlineTemplate: true }).content
    .replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_line, binding, path) => {
      if (!(path in modules)) throw new Error('Unmocked import ' + path)
      if (binding.startsWith('{')) return 'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];'
      return 'const ' + binding + ' = modules[' + JSON.stringify(path) + '];'
    }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}

test('a separate illustrated solution can be written and saved without a practice attempt', async () => {
  const saved = []
  const axios = {
    get: async () => ({ data: { number: 525, content: '', images: [], version: null } }),
    put: async (_url, body) => {
      saved.push(body)
      return { data: { number: 525, content: body.content, version: 0,
        images: body.images.map((image, index) => ({ id: `image-${index}`, caption: image.caption })) } }
    }
  }
  const source = await readFile(new URL('./PracticeSolutionModal.vue', import.meta.url), 'utf8')
  const app = Vue.createApp(compile(source, { vue: Vue, axios, '../i18n/index.js': i18n }), { problem: { number: 525, title: '连续数组' } })
  try {
    app.mount('#root'); await flush()
    assert.match(document.querySelector('.solution-header h3').textContent, /525.*连续数组/)
    const editor = document.querySelector('textarea')
    editor.value = '前缀和 + 哈希表，记录第一次出现的位置。'
    editor.dispatchEvent(new window.Event('input', { bubbles: true })); await flush()
    const file = new window.File([new Uint8Array([137,80,78,71,13,10,26,10,1])], '推导.png', { type: 'image/png' })
    const input = document.querySelector('input[type=file]')
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    input.dispatchEvent(new window.Event('change', { bubbles: true }))
    await new Promise(resolve => setTimeout(resolve, 60)); await flush()
    assert.equal(document.querySelectorAll('.image-card').length, 1)
    const caption = document.querySelector('.image-card input')
    caption.value = '手写推导'; caption.dispatchEvent(new window.Event('input', { bubbles: true })); await flush()
    i18n.setLocale('en-US'); await flush()
    assert.equal(document.querySelector('.save-button').textContent, 'Save solution')
    assert.match(document.querySelector('.solution-header h3').textContent, /连续数组/)
    assert.equal(editor.value, '前缀和 + 哈希表，记录第一次出现的位置。')
    assert.equal(caption.value, '手写推导')
    assert.equal(saved.length, 0, 'language changes do not save or translate note content')
    document.querySelector('.save-button').click(); await flush()
    assert.equal(saved.length, 1)
    assert.match(saved[0].content, /前缀和/)
    assert.equal(saved[0].images[0].caption, '手写推导')
    assert.equal(saved[0].images[0].mimeType, 'image/png')
    assert.ok(saved[0].images[0].base64)
    assert.equal(document.querySelectorAll('.image-card').length, 1)
  } finally {
    i18n.setLocale('zh-CN')
    app.unmount()
    document.querySelector('#root').innerHTML = ''
  }
})
