import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

const dom = new JSDOM('<div id="career-test"></div>', { url: 'http://localhost/' })
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, SVGElement: dom.window.SVGElement })
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const tick = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await Vue.nextTick() } }
const source = await readFile(new URL('./JobApplicationCenter.vue', import.meta.url), 'utf8')
function compile(modules) {
  const exposed = 'applications, suggestions, choices, explicitChoices, draft, editing, editor, editorStale, linkTargetId, linkMailId, load, openEditor, save'
  const { descriptor } = parse(source.replace('</script>', `\ndefineExpose({ ${exposed} })\n</script>`))
  const script = compileScript(descriptor, { id: 'career-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    assert.ok(path in modules, 'Unmocked import: ' + path)
    return 'const ' + (binding.startsWith('{') ? binding.replace(/\bas\b/g, ':') : binding) + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function seed() {
  const application = (id, company, version) => ({ id, company, version, role: 'Software Engineer Intern', stage: 'APPLIED', result: null, location: '', jobUrl: '', notes: '', appliedAt: null, nextActionAt: null, linkedMails: [] })
  const rbc = application('rbc', 'RBC', 3), ibm = application('ibm', 'IBM', 7)
  rbc.linkedMails = Array.from({ length: 4 }, (_, index) => ({ id: 100 + index, subject: `Recruiting email ${index + 1}`, receivedAt: '2026-09-20T12:00:00Z' }))
  return {
    applications: [rbc, ibm], stageCounts: { APPLIED: 2 }, analysis: { status: 'IDLE' },
    suggestions: [{ mailId: 50, mailSubject: 'IBM assessment', sender: 'hiring@example.com', receivedAt: '2026-09-20T12:00:00Z', company: 'IBM', role: ibm.role, suggestedStage: 'ASSESSMENT', summary: 'Assessment invitation', evidence: 'Complete the assessment', preparations: [{ title: 'Practice', details: 'Review the assessment format' }], matchedApplicationId: 'ibm', version: 9 }]
  }
}
async function mountCareer(options = {}) {
  i18n.setLocale('zh-CN')
  const state = options.state || seed(), calls = [], messages = [], events = []
  const axios = {
    async get(url, config) { calls.push({ method: 'get', url, config }); return options.get ? options.get(state, calls) : { data: structuredClone(state) } },
    async post(url, body, config) { calls.push({ method: 'post', url, body, config }); return { data: url.endsWith('/analysis') ? { status: 'IDLE' } : { tasksCreated: 1 } } },
    async put(url, body, config) {
      calls.push({ method: 'put', url, body, config })
      if (options.put) return options.put(url, body, state)
      const parts = url.split('/'), source = state.applications.find(item => item.id === parts[3])
      if (parts[4] === 'mails') {
        const target = state.applications.find(item => item.id === body.targetApplicationId)
        const mail = source.linkedMails.find(item => item.id === Number(parts[5]))
        source.linkedMails = source.linkedMails.filter(item => item.id !== mail.id); source.version++
        target.linkedMails.push(mail); target.version++
        return { data: structuredClone({ source, target }) }
      }
      Object.assign(source, body, { version: source.version + 1 })
      return { data: structuredClone(source) }
    },
    async delete(url, config) {
      calls.push({ method: 'delete', url, config })
      if (options.delete) return options.delete(url, config, state)
      const parts = url.split('/'), source = state.applications.find(item => item.id === parts[3])
      source.linkedMails = source.linkedMails.filter(item => item.id !== Number(parts[5])); source.version++
      return { data: structuredClone(source) }
    }
  }
  const component = compile({
    vue: Vue, axios, '../i18n/index.js': i18n,
    'element-plus': {
      ElMessage: Object.fromEntries(['error', 'success', 'warning'].map(type => [type, message => messages.push({ type, message })])),
      ElMessageBox: { async confirm(message, title, config) { calls.push({ method: 'confirm', message, title, config }); if (options.confirm) return options.confirm() } }
    }
  })
  const app = Vue.createApp(component, { onOpenMail: value => events.push(value) }), vm = app.mount('#career-test')
  await tick()
  return { vm, calls, messages, events, state, cleanup() { app.unmount(); document.querySelector('#career-test').innerHTML = ''; i18n.setLocale('zh-CN') } }
}
async function select(element, value) { element.value = value; element.dispatchEvent(new dom.window.Event('change', { bubbles: true })); await tick() }
async function click(element) { assert.ok(element, 'Expected an actionable element'); element.click(); await tick() }
const suggestionSelect = () => document.querySelector('.suggestion-decision > label select')
const suggestionConfirm = () => document.querySelector('.suggestion-actions .confirm')
const firstMailButtons = () => document.querySelectorAll('.editor-mail > .mail-link-actions button')

test('an ambiguous suggestion has no default, asks for a manual choice, and cannot apply while empty', async () => {
  const state = seed(); state.suggestions[0].matchedApplicationId = null
  const app = await mountCareer({ state })
  try {
    assert.equal(suggestionSelect().value, '')
    assert.equal(suggestionConfirm().disabled, true)
    assert.equal(document.querySelector('.match-hint').textContent, '请手动选择公司与岗位')
    await click(suggestionConfirm())
    assert.equal(app.calls.some(call => call.url?.endsWith('/apply')), false)
    i18n.setLocale('en-US'); await tick()
    assert.equal(document.querySelector('.match-hint').textContent, 'Select a company and role manually')
    assert.match(document.querySelector('.suggestion-main h4').textContent, /IBM assessment/)
    await select(suggestionSelect(), 'ibm')
    assert.equal(suggestionConfirm().disabled, false)
  } finally { app.cleanup() }
})

test('saving a company correction replaces a previous automatic choice with the refreshed match', async () => {
  const state = seed(); state.applications[1].company = 'IDM'; state.suggestions[0].matchedApplicationId = 'rbc'
  const app = await mountCareer({ state, put: async (_url, body, current) => {
    Object.assign(current.applications[1], body, { version: 8 }); current.suggestions[0].matchedApplicationId = 'ibm'
    return { data: structuredClone(current.applications[1]) }
  } })
  try {
    assert.equal(suggestionSelect().value, 'rbc')
    await click(document.querySelectorAll('.application-card header > button')[1])
    const companyInput = document.querySelector('.editor form input')
    companyInput.value = 'IBM'; companyInput.dispatchEvent(new dom.window.Event('input', { bubbles: true })); await tick()
    await app.vm.save(); await tick()
    assert.equal(app.vm.editor, false)
    assert.equal(suggestionSelect().value, 'ibm')
    assert.deepEqual(Object.keys(app.vm.explicitChoices), [])
    await click(suggestionConfirm())
    const apply = app.calls.find(call => call.url?.endsWith('/apply'))
    assert.equal(apply.body.applicationId, 'ibm')
    assert.equal(apply.body.applicationVersion, 8)
    assert.equal(apply.body.suggestionVersion, 9)
  } finally { app.cleanup() }
})

test('refresh retains a deliberate valid choice, but discards it when the application identity changes', async () => {
  const state = seed(); state.applications.push({ ...state.applications[1], id: 'ibm-other', role: 'Backend Intern', linkedMails: [] })
  const app = await mountCareer({ state })
  try {
    await select(suggestionSelect(), 'ibm-other')
    state.suggestions[0].matchedApplicationId = null; state.applications[2].version++
    await app.vm.load(); await tick()
    assert.equal(suggestionSelect().value, 'ibm-other')
    assert.equal(suggestionConfirm().disabled, false)
    state.applications[2].company = 'Different company'
    await app.vm.load(); await tick()
    assert.equal(suggestionSelect().value, '')
    assert.equal(suggestionConfirm().disabled, true)
    assert.deepEqual(Object.keys(app.vm.explicitChoices), [])
    await select(suggestionSelect(), 'ibm')
    state.applications = state.applications.filter(item => item.id !== 'ibm')
    await app.vm.load(); await tick()
    assert.equal(suggestionSelect().value, '')
  } finally { app.cleanup() }
})

test('an intentional empty choice is preserved while invalid automatic IDs remain empty', async () => {
  const app = await mountCareer()
  try {
    await select(suggestionSelect(), '')
    await app.vm.load(); await tick()
    assert.equal(suggestionSelect().value, '')
    app.state.suggestions[0].company = 'New company'; app.state.suggestions[0].matchedApplicationId = 'removed-application'
    await app.vm.load(); await tick()
    assert.equal(suggestionSelect().value, '')
    assert.equal(suggestionConfirm().disabled, true)
  } finally { app.cleanup() }
})

test('a delayed refresh cannot restore an old automatic selection after a newer response', async () => {
  let resolveOld, requests = 0
  const app = await mountCareer({ get: (state) => {
    if (++requests === 2) return new Promise(resolve => { resolveOld = resolve })
    return { data: structuredClone(state) }
  } })
  try {
    const old = structuredClone(app.state), pending = app.vm.load()
    app.state.suggestions[0].matchedApplicationId = null
    await app.vm.load(); await tick()
    resolveOld({ data: old }); await pending; await tick()
    assert.equal(suggestionSelect().value, '')
    assert.equal(suggestionConfirm().disabled, true)
  } finally { app.cleanup() }
})

test('the editor lists every linked email and moves one with source and target versions while preserving its draft', async () => {
  const app = await mountCareer()
  try {
    await click(document.querySelector('.application-card header > button'))
    assert.equal(document.querySelectorAll('.editor-mail').length, 4)
    assert.match(document.querySelector('.editor-linked-mails > p').textContent, /不会更改申请阶段或已有准备任务/)
    app.vm.draft.notes = 'Unsaved preparation notes'
    await click(document.querySelectorAll('.editor-mail-subject')[3])
    assert.deepEqual(app.events, [{ id: 103 }])
    await click(firstMailButtons()[0])
    const selector = document.querySelector('.mail-link-editor select')
    assert.equal(selector.querySelector('option[value="rbc"]'), null)
    assert.equal(document.querySelector('.mail-link-editor .primary').disabled, true)
    await select(selector, 'ibm'); await click(document.querySelector('.mail-link-editor .primary'))
    const write = app.calls.find(call => call.method === 'put')
    assert.equal(write.url, '/api/job-applications/rbc/mails/100')
    assert.deepEqual(write.body, { targetApplicationId: 'ibm', applicationVersion: 3, targetApplicationVersion: 7 })
    assert.equal(app.vm.editing.version, 4)
    assert.equal(app.vm.editor, true)
    assert.equal(app.vm.draft.notes, 'Unsaved preparation notes')
    assert.equal(document.querySelectorAll('.editor-mail').length, 3)
    assert.equal(document.querySelector('.mail-link-editor'), null)
    assert.equal(app.state.applications[1].linkedMails[0].id, 100)
    assert.ok(app.state.applications.every(item => item.stage === 'APPLIED'))
    assert.equal(app.calls.some(call => call.url?.endsWith('/apply')), false)
    await app.vm.save()
    assert.equal(app.calls.filter(call => call.method === 'put').at(-1).body.version, 4)
  } finally { app.cleanup() }
})

test('unlink requires confirmation, honors cancellation, and sends the displayed application version', async () => {
  let cancel = true
  const app = await mountCareer({ confirm: () => cancel ? Promise.reject('cancel') : Promise.resolve() })
  try {
    await click(document.querySelector('.application-card header > button'))
    app.vm.draft.company = 'Draft company'
    await click(firstMailButtons()[1])
    assert.equal(app.calls.some(call => call.method === 'delete'), false)
    assert.equal(document.querySelectorAll('.editor-mail').length, 4)
    cancel = false
    await click(firstMailButtons()[1])
    const confirmation = app.calls.find(call => call.method === 'confirm'), write = app.calls.find(call => call.method === 'delete')
    assert.match(confirmation.message, /申请阶段和已有准备任务会保留/)
    assert.equal(write.url, '/api/job-applications/rbc/mails/100')
    assert.equal(write.config.params.version, 3)
    assert.equal(app.vm.editing.version, 4)
    assert.equal(app.vm.draft.company, 'Draft company')
    assert.equal(app.vm.editor, true)
    assert.equal(document.querySelectorAll('.editor-mail').length, 3)
    assert.ok(app.state.applications.every(item => item.stage === 'APPLIED'))
    assert.equal(app.calls.some(call => call.url?.endsWith('/apply')), false)
  } finally { app.cleanup() }
})

test('a rejected move shows its translated error and keeps the selected target and draft for correction', async () => {
  const app = await mountCareer({ put: () => Promise.reject({ response: { status: 400, data: { message: '邮件中的公司与所选申请不一致，请核对公司后再关联' } } }) })
  try {
    await click(document.querySelector('.application-card header > button'))
    app.vm.draft.notes = 'Keep my draft'
    await click(firstMailButtons()[0]); await select(document.querySelector('.mail-link-editor select'), 'ibm')
    await click(document.querySelector('.mail-link-editor .primary'))
    assert.match(document.querySelector('.editor [role="alert"]').textContent, /公司与所选申请不一致/)
    assert.equal(app.vm.linkTargetId, 'ibm')
    assert.equal(app.vm.draft.notes, 'Keep my draft')
    assert.equal(document.querySelectorAll('.editor-mail').length, 4)
    assert.equal(app.vm.editor, true)
    assert.equal(app.messages.some(message => message.type === 'success'), false)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.editor [role="alert"]').textContent, /email company does not match/)
  } finally { app.cleanup() }
})

test('an unlink version conflict refreshes the board, preserves the draft, and requires reopening current data', async () => {
  const app = await mountCareer({ delete: (_url, _config, state) => {
    state.applications[0].version = 12; state.applications[0].notes = 'Updated elsewhere'
    return Promise.reject({ response: { status: 409, data: { message: '申请记录已被其他页面修改，请刷新后重试' } } })
  } })
  try {
    await click(document.querySelector('.application-card header > button'))
    app.vm.draft.notes = 'Keep this draft'
    await click(firstMailButtons()[1])
    assert.equal(app.calls.filter(call => call.method === 'get').length, 2)
    assert.equal(app.vm.applications[0].version, 12)
    assert.equal(app.vm.editing.version, 3)
    assert.equal(app.vm.draft.notes, 'Keep this draft')
    assert.equal(app.vm.editor, true)
    assert.equal(app.vm.editorStale, true)
    assert.equal(document.querySelector('.editor button[type="submit"]').disabled, true)
    assert.match(document.querySelector('.editor').textContent, /草稿已保留/)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.editor').textContent, /Close and reopen the application/)
  } finally { app.cleanup() }
})
