import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as plan from '../utils/practicePlan.js'

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
  const compiled = compileScript(descriptor, { id: 'practice-test', inlineTemplate: true }).content
    .replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_line, binding, path) => {
      if (!(path in modules)) throw new Error('Unmocked import ' + path)
      if (binding.startsWith('{')) return 'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];'
      return 'const ' + binding + ' = modules[' + JSON.stringify(path) + '];'
    }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}

test('personal records use two levels, random review, and keep the GPT list optional', async () => {
  const calls = [], rows = [], groups = [], moves = []
  const axios = {
    get: async (url) => ({ data: url.endsWith('/groups') ? [...groups] : url.endsWith('/solutions') ? [999] : [...rows] }),
    post: async (url, body) => {
      if (url.endsWith('/groups')) {
        const saved = { ...body, version: (body.version ?? -1) + 1 }
        const index = groups.findIndex(group => group.name === saved.name)
        if (index >= 0) groups.splice(index, 1)
        groups.push(saved)
        return { data: saved }
      }
      calls.push(body)
      const saved = { ...body, status: body.result, attempts: 1, totalMinutes: body.minutes, version: 0,
        nextReviewAt: null }
      rows.push(saved)
      return { data: saved }
    },
    patch: async (_url, body) => {
      moves.push(body)
      const row = rows.find(item => item.number === 236)
      row.topic = body.topic; row.version++
      return { data: { ...row } }
    }
  }
  const source = await readFile(new URL('./PracticeCenter.vue', import.meta.url), 'utf8')
  const app = Vue.createApp(compile(source, { vue: Vue, axios, '../i18n/index.js': i18n, '../utils/practicePlan': plan,
    './PracticeSolutionModal.vue': { props: ['problem'], template: '<div class="solution-stub">{{ problem.number }}</div>' } }))
  try {
    app.mount('#root'); await flush()
    assert.match(document.querySelector('.stat').textContent, /0/)
    assert.match(document.querySelector('.note-only').textContent, /999/)
    assert.equal(document.querySelector('.suggestions').open, false)
    document.querySelector('.suggestions').open = true
    const first = [...document.querySelectorAll('.suggestion-day button')].find(button => button.textContent.includes('525'))
    assert.ok(first)
    first.click(); await flush()
    document.querySelector('.result-option.yellow input').click()
    const minutes = document.querySelector('.practice-modal input[type=number]')
    minutes.value = '35'
    minutes.dispatchEvent(new window.Event('input', { bubbles: true }))
    await flush()
    document.querySelector('.practice-modal .primary').click(); await flush()
    assert.equal(calls[0].number, 525)
    assert.equal(calls[0].result, 'YELLOW')
    assert.match(document.querySelector('.stat-grid .stat').textContent, /1/)
    document.querySelector('.draw-button').click(); await flush()
    assert.match(document.querySelector('.drawn').textContent, /525.*连续数组/)

    const fields = document.querySelectorAll('.add-form input')
    for (const [node, value] of [[fields[0], '236'], [fields[1], '二叉树的最近公共祖先'], [fields[2], 'Hot 100 · 二叉树']]) {
      node.value = value; node.dispatchEvent(new window.Event('input', { bubbles: true }))
    }
    await flush()
    document.querySelector('.add-form button').click(); await flush()
    assert.match(document.querySelector('.practice-modal h3').textContent, /236/)
    document.querySelector('.result-option.green input').click(); await flush()
    document.querySelector('.practice-modal .primary').click(); await flush()
    assert.equal(calls[1].number, 236)
    assert.match(document.querySelectorAll('.stat')[0].textContent, /2/)
    assert.match(document.querySelectorAll('.stat')[1].textContent, /1/)
    assert.match(document.querySelectorAll('.stat')[2].textContent, /1/)
    assert.equal(document.querySelectorAll('.record-row').length, 2)
    assert.match(document.querySelector('.drawn').textContent, /525/)
    const groupButton = [...document.querySelectorAll('.group-tabs button')].find(button => button.textContent.includes('Hot 100 · 二叉树'))
    assert.ok(groupButton)
    groupButton.click(); await flush()
    assert.equal(document.querySelectorAll('.record-row').length, 1)
    assert.match(document.querySelector('.record-row').textContent, /236/)
    document.querySelector('.solution-launch').click(); await flush()
    assert.match(document.querySelector('.solution-stub').textContent, /236/)
    document.querySelector('.group-actions button').click(); await flush()
    const note = document.querySelector('.group-notes textarea')
    note.value = '树形 DP：先明确状态，再枚举子树。'
    note.dispatchEvent(new window.Event('input', { bubbles: true })); await flush();
    [...document.querySelectorAll('.group-actions button')].at(-1).click(); await flush()
    assert.equal(groups[0].note, '树形 DP：先明确状态，再枚举子树。')
    assert.match(document.querySelector('.group-notes p').textContent, /树形 DP/)
    const requestsBeforeLocaleChange = calls.length
    i18n.setLocale('en-US'); await flush()
    assert.equal(document.querySelector('.hero h2').textContent, 'My Coding Practice')
    assert.equal(document.querySelector('.group-notes p').textContent, '树形 DP：先明确状态，再枚举子树。')
    assert.match(document.querySelector('.record-row').textContent, /二叉树的最近公共祖先/)
    assert.match(document.querySelector('.record-row').textContent, /Solved independently/)
    assert.equal(calls.length, requestsBeforeLocaleChange, 'changing UI language must not save or reanalyze records')
    i18n.setLocale('zh-CN'); await flush()
    document.querySelector('.record-row').click(); await flush()
    const topic = document.querySelector('.practice-modal input[list]')
    topic.value = '动态规划'; topic.dispatchEvent(new window.Event('input', { bubbles: true })); await flush()
    document.querySelector('.move-button').click(); await flush()
    assert.equal(moves[0].topic, '动态规划')
    assert.equal(calls.length, 2, 'moving between groups must not log a practice attempt')
    assert.equal(document.querySelectorAll('.record-row').length, 0)
    assert.ok([...document.querySelectorAll('.group-tabs button')].some(button => button.textContent.includes('动态规划')))
    document.querySelector('.note-only button').click(); await flush()
    assert.match(document.querySelector('.solution-stub').textContent, /999/)
  } finally {
    i18n.setLocale('zh-CN')
    app.unmount()
    document.querySelector('#root').innerHTML = ''
  }
})
