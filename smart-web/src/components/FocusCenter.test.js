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
const flush = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await Vue.nextTick() } }

function compile(source, modules) {
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'focus-shortcut-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    if (!(path in modules)) throw new Error('Unmocked import: ' + path)
    if (binding.startsWith('{')) return 'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];'
    return 'const ' + binding + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}

test('digits toggle their own timer only on the focus page, without intercepting editing or shortcuts', async () => {
  const today = new Date().toLocaleDateString('sv-SE')
  const state = {
    today, weekStart: today, daily: { JOB: 0, FRENCH: 0, COURSE: 0, ENTERTAINMENT: 0 },
    weekly: { JOB: 0, FRENCH: 0, COURSE: 0, ENTERTAINMENT: 0 },
    weekDays: { [today]: { JOB: 0, FRENCH: 0, COURSE: 0, ENTERTAINMENT: 0 } },
    entertainmentLimitMinutes: 300, taskTimes: [], active: null
  }
  const calls = []
  const axios = {
    get: async () => ({ data: structuredClone(state) }),
    post: async (url, body) => {
      calls.push({ url, body })
      if (url.endsWith('/start')) state.active = { id: 17, category: body.category }
      else state.active = null
      return { data: structuredClone(state) }
    }
  }
  const modules = {
    vue: Vue, axios,
    '../i18n/index.js': i18n,
    '../stores/taskStore': { useTaskStore: () => ({ tasks: Vue.ref([]), openTasks: Vue.ref([]), refresh: async () => {} }) }
  }
  const source = await readFile(new URL('./FocusCenter.vue', import.meta.url), 'utf8')
  const app = Vue.createApp(compile(source, modules))
  const key = (value, options = {}, target = window) => target.dispatchEvent(new window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }))
  try {
    app.mount('#root')
    await flush()
    assert.match(document.querySelector('.shortcut-hint').textContent, /1.*招工.*4.*娱乐/)
    assert.equal(key('1'), false)
    await flush()
    assert.equal(calls.at(-1).body.category, 'JOB')
    assert.equal(document.querySelector('.job .timer-button').textContent, '结束并保存')

    i18n.setLocale('en-US'); await flush()
    assert.equal(document.querySelector('.job h3').textContent, 'Job search')
    assert.equal(document.querySelector('.job .timer-button').textContent, 'Stop & save')
    assert.match(document.querySelector('.job .big-time').textContent, /\dh \d{2}m/)
    assert.equal(calls.length, 1, 'Switching language must not start or stop a timer')
    i18n.setLocale('zh-CN'); await flush()

    key('1', { repeat: true })
    key('1', { ctrlKey: true })
    key('2') // Another category cannot displace the active timer.
    key('1', {}, document.querySelector('.limit-form input'))
    await flush()
    assert.equal(calls.length, 1)

    key('1')
    await flush()
    assert.equal(calls.at(-1).url, '/api/focus/stop')
    key('2')
    await flush()
    assert.equal(calls.at(-1).body.category, 'FRENCH')
    key('2')
    await flush()
    key('4')
    await flush()
    assert.equal(calls.at(-1).body.category, 'ENTERTAINMENT')

    i18n.setLocale('en-US'); await flush()
    const quota = document.querySelector('.limit-form input')
    quota.value = '25'; quota.dispatchEvent(new window.Event('input', { bubbles: true }))
    document.querySelector('.limit-form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }))
    await flush()
    assert.equal(document.querySelector('.notice').textContent, 'Enter a value from 0.25 to 24 hours.')
    i18n.setLocale('zh-CN'); await flush()
    assert.equal(document.querySelector('.notice').textContent, '请输入 0.25 到 24 小时')

    app.unmount()
    const count = calls.length
    key('4')
    await flush()
    assert.equal(calls.length, count)
  } finally {
    if (app._instance) app.unmount()
    document.querySelector('#root').innerHTML = ''
    i18n.setLocale('zh-CN')
  }
})
