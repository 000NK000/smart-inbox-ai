import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/' })
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  Element: dom.window.Element, SVGElement: dom.window.SVGElement,
  localStorage: dom.window.localStorage,
})
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const flush = async () => { for (let n = 0; n < 10; n++) { await Promise.resolve(); await Vue.nextTick() } }
const stub = Vue.defineComponent({ inheritAttrs: false, setup: (_, { slots }) => () => slots.default?.() })
async function compile(name, mocks = {}) {
  const source = await readFile(new URL(`./${name}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const { content } = compileScript(descriptor, { id: name, inlineTemplate: true })
  const modules = { vue: Vue, '../i18n/index.js': i18n, ...mocks }
  const compiled = content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_, binding, path) => {
    assert.ok(path in modules, `Unmocked import: ${path}`)
    return `const ${binding.replace(/\bas\b/g, ':')} = modules[${JSON.stringify(path)}];`
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}

test('UI locale switching preserves news content and never starts AI translation', async () => {
  i18n.setLocale('zh-CN')
  const requests = []
  const article = { title: '中文新闻标题', summary: '中文新闻摘要保留原文。', source: 'CNN', url: 'https://www.cnn.com/example', publishedAt: '2026-09-27T15:00:00Z' }
  const component = await compile('UsNewsPanel', {
    axios: { post: async (...args) => { requests.push(args); return { data: { items: [article] } } } },
    './NewsBriefDialog.vue': stub,
  })
  const app = Vue.createApp(component, { combined: [article], sources: [] })
  try {
    app.mount('#root')
    assert.equal(document.querySelector('h2').textContent, '美国媒体新闻')
    i18n.setLocale('en-US')
    await flush()
    assert.equal(document.querySelector('h2').textContent, 'U.S. media news')
    assert.equal(document.querySelector('h4').textContent, article.title)
    assert.equal(document.querySelector('.news-entry p').textContent, article.summary)
    assert.equal(requests.length, 0)
    // The separate content-translation control still works only when explicitly selected.
    document.querySelector('.language-switch button').click()
    await flush()
    assert.equal(requests.length, 1)
    assert.equal(requests[0][0], '/api/dashboard/us-news/translate')
    i18n.setLocale('zh-CN')
    await flush()
    assert.equal(requests.length, 1)
  } finally { app.unmount(); i18n.setLocale('zh-CN') }
})

test('film details localize controls while retaining Chinese title, synopsis, and metadata', async () => {
  const item = { title: '中文电影名称', description: '这是原始中文剧情简介。', metadata: '中国 / 剧情', source: '豆瓣', sourceDetail: '一周口碑电影榜', url: 'https://movie.douban.com/subject/123/', year: '2026' }
  const component = await compile('WatchDetailDialog', { 'element-plus': { ElDialog: stub }, './WatchPoster.vue': stub })
  const app = Vue.createApp(component, { item, kind: 'MOVIE' })
  try {
    app.mount('#root')
    i18n.setLocale('en-US')
    await flush()
    assert.equal(document.querySelector('.detail-info h2').textContent, item.title)
    assert.equal(document.querySelector('.detail-synopsis p').textContent, item.description)
    assert.equal(document.querySelector('.metadata').textContent, item.metadata)
    assert.equal(document.querySelector('.detail-synopsis h3').textContent, 'Synopsis')
    assert.equal(document.querySelector('.official-link').textContent.trim(), 'Open on Douban ↗')
    i18n.setLocale('zh-CN')
    await flush()
    assert.equal(document.querySelector('.detail-synopsis h3').textContent, '作品简介')
    assert.equal(document.querySelector('.detail-info h2').textContent, item.title)
  } finally { app.unmount(); i18n.setLocale('zh-CN') }
})
