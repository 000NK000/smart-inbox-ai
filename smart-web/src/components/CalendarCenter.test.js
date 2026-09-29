import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as calendar from '../utils/calendar.js'

const dom = new JSDOM('<div id="calendar-test"></div>', { url: 'http://localhost/', pretendToBeVisual: true })
globalThis.window = dom.window; globalThis.document = dom.window.document
globalThis.Element = dom.window.Element; globalThis.SVGElement = dom.window.SVGElement
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const taskHelpers = await import('../stores/taskStore.js')
const tick = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await Vue.nextTick() } }
const source = await readFile(new URL('./CalendarCenter.vue', import.meta.url), 'utf8')
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function compile(modules) {
  const exposed = 'view, anchor, selectedDay, occurrences, definitions, range, loading, modal, draft, editing, taskDue, modalError, move, newEvent, openItem, closeModal, saveEvent, removeEvent, saveDeadline, finishTask, loadCalendar'
  const { descriptor } = parse(source.replace('</script>', `\ndefineExpose({ ${exposed} })\n</script>`))
  const script = compileScript(descriptor, { id: 'calendar-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    if (!(path in modules)) throw new Error('Unmocked import: ' + path)
    return 'const ' + (binding.startsWith('{') ? binding.replace(/\bas\b/g, ':') : binding) + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function seed() {
  const day = calendar.dayKey(), startAt = calendar.localDay(day).getTime() + 9 * 3600000
  const definition = { id: 'course-1', version: 4, title: '合成课程', kind: 'COURSE', startLocal: day + 'T09:00:00', endLocal: day + 'T10:00:00', zone: 'Pacific/Auckland', recurrence: 'WEEKLY', daysOfWeek: [1, 3], repeatUntil: calendar.addDays(day, 60), location: '合成教室', notes: '' }
  const occurrence = { key: 'course-1:' + day, eventId: 'course-1', version: 4, title: definition.title, kind: 'COURSE', startAt, endAt: startAt + 3600000, zone: definition.zone, recurring: true, location: definition.location }
  return { day, definition, occurrence }
}
async function mountCalendar(overrides = {}) {
  const calls = [], messages = [], timers = new Map(), data = seed(), taskList = Vue.ref(overrides.tasks || []), events = []
  let intervalId = 0, hidden = false
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  const originalInterval = globalThis.setInterval, originalClear = globalThis.clearInterval
  globalThis.setInterval = (fn, ms) => { const id = ++intervalId; timers.set(id, { fn, ms }); return id }
  globalThis.clearInterval = id => timers.delete(id)
  const axios = {
    async get(url, config) { calls.push({ method: 'get', url, config }); return overrides.get ? overrides.get(url, config, calls) : { data: { events: [data.definition], occurrences: [data.occurrence] } } },
    async post(url, body, config) { calls.push({ method: 'post', url, body, config }); return { data: {} } },
    async put(url, body, config) { calls.push({ method: 'put', url, body, config }); if (overrides.put) return overrides.put(url, body); return { data: {} } },
    async delete(url, config) { calls.push({ method: 'delete', url, config }); return { data: {} } }
  }
  const modules = {
    vue: Vue, axios,
    '../i18n/index.js': i18n,
    'element-plus': { ElMessage: Object.fromEntries(['error', 'success', 'warning', 'info'].map(type => [type, message => messages.push({ type, message })])), ElMessageBox: { async confirm(message, title, options) { calls.push({ method: 'confirm', message, title, options }); if (overrides.confirm) return overrides.confirm() } } },
    '../utils/calendar': calendar,
    '../stores/taskStore': { ...taskHelpers, useTaskStore: () => ({ tasks: taskList, error: Vue.ref(''), refresh: async () => {} }), async editTask(task, input) { calls.push({ method: 'editTask', task, input }); taskList.value = taskList.value.map(row => row.id === task.id ? { ...row, ...input, version: row.version + 1 } : row) }, async completeTask(task, completed) { calls.push({ method: 'completeTask', task, completed }); taskList.value = taskList.value.map(row => row.id === task.id ? { ...row, status: 'COMPLETED' } : row) } }
  }
  const component = compile(modules)
  const app = Vue.createApp(component, { onNavigate: value => events.push(['navigate', value]), onOpenMail: value => events.push(['open-mail', value]) })
  const vm = app.mount('#calendar-test')
  await tick()
  let cleaned = false
  return { vm, calls, messages, timers, data, events, hide(value) { hidden = value }, cleanup() { if (cleaned) return; cleaned = true; app.unmount(); globalThis.setInterval = originalInterval; globalThis.clearInterval = originalClear; document.querySelector('#calendar-test').innerHTML = '' } }
}

test('switching the calendar UI language preserves event content and formats dates without refetching', async () => {
  i18n.setLocale('zh-CN')
  const app = await mountCalendar()
  try {
    const requests = app.calls.filter(call => call.method === 'get').length
    assert.match(document.querySelector('.calendar-intro').textContent, /日历与课程/)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.calendar-intro').textContent, /Calendar & Courses/)
    assert.match(document.querySelector('.calendar-filters').textContent, /Courses/)
    assert.match(document.querySelector('.calendar-center').textContent, /合成课程/)
    assert.equal(document.querySelector('.daily-agenda h3').textContent, calendar.localDay(app.data.day).toLocaleDateString('en-US', { month: 'long', day: 'numeric' }))
    assert.equal(app.calls.filter(call => call.method === 'get').length, requests)
    app.vm.openItem(app.data.occurrence); await tick()
    assert.equal(document.querySelector('.event-form input').value, '合成课程')
    assert.match(document.querySelector('.event-form').textContent, /Weekly/)
    app.vm.draft.title = ''; await app.vm.saveEvent(); await tick()
    assert.equal(document.querySelector('[role="dialog"] .notice').textContent, 'Enter an event title.')
    i18n.setLocale('zh-CN'); await tick()
    assert.match(document.querySelector('.event-form').textContent, /每周重复/)
    assert.equal(document.querySelector('[role="dialog"] .notice').textContent, '请填写日程名称')
    assert.equal(app.calls.filter(call => call.method === 'get').length, requests)
  } finally { app.cleanup(); i18n.setLocale('zh-CN') }
})

test('stale range responses are ignored, old requests abort, hidden polling pauses and unmount releases listeners', async () => {
  const first = deferred(), second = deferred()
  let count = 0
  const app = await mountCalendar({ get: () => ++count === 1 ? first.promise : second.promise })
  try {
    assert.equal(app.timers.size, 1)
    assert.equal([...app.timers.values()][0].ms, 30000)
    const firstRequest = app.calls[0]
    app.vm.move(1); await tick()
    assert.equal(firstRequest.config.signal.aborted, true)
    assert.notEqual(app.calls[1].config.params.from, firstRequest.config.params.from)
    second.resolve({ data: { events: [], occurrences: [{ ...app.data.occurrence, title: '最新周期' }] } }); await tick()
    first.resolve({ data: { events: [], occurrences: [{ ...app.data.occurrence, title: '过期周期' }] } }); await tick()
    assert.equal(app.vm.occurrences[0].title, '最新周期')
    app.hide(true); for (const timer of app.timers.values()) timer.fn(); await tick()
    assert.equal(count, 2)
    app.hide(false); window.dispatchEvent(new dom.window.Event('focus')); await tick()
    assert.equal(count, 3)
    const latestRequest = app.calls.filter(call => call.method === 'get').at(-1)
    app.cleanup()
    assert.equal(app.timers.size, 0)
    assert.equal(latestRequest.config.signal.aborted, true)
    window.dispatchEvent(new dom.window.Event('focus')); await tick(); assert.equal(count, 3)
  } finally { app.cleanup() }
})

test('weekly editor preserves event zone/version, validates weekdays, saves entire series and confirms delete', async () => {
  const app = await mountCalendar()
  try {
    app.vm.openItem(app.data.occurrence); await tick()
    assert.match(document.querySelector('[role="dialog"]').textContent, /整个每周系列/)
    assert.equal(app.vm.draft.zone, 'Pacific/Auckland')
    app.vm.draft.daysOfWeek = []; await app.vm.saveEvent()
    assert.match(app.vm.modalError, /至少一个/)
    assert.equal(app.calls.some(call => call.method === 'put'), false)
    app.vm.draft.daysOfWeek = [5, 1]; app.vm.draft.title = '更新的课程'
    await app.vm.saveEvent(); await tick()
    const write = app.calls.find(call => call.method === 'put')
    assert.equal(write.url, '/api/calendar/events/course-1')
    assert.equal(write.body.zone, 'Pacific/Auckland')
    assert.equal(write.body.version, 4)
    assert.deepEqual(write.body.daysOfWeek, [1, 5])
    assert.equal(app.vm.modal, null)
    app.vm.openItem(app.data.occurrence); await tick(); await app.vm.removeEvent(); await tick()
    assert.match(app.calls.find(call => call.method === 'confirm').message, /整个重复系列/)
    assert.equal(app.calls.find(call => call.method === 'delete').config.params.version, 4)
    assert.equal(app.vm.modal, null)
  } finally { app.cleanup() }
})

test('new events use clicked day, month renders 42 cells and Escape restores focus', async () => {
  const app = await mountCalendar()
  try {
    app.vm.view = 'month'; await tick()
    assert.equal(document.querySelectorAll('.month-day').length, 42)
    const trigger = document.querySelector('.add-event'); trigger.focus()
    const clicked = calendar.addDays(app.data.day, 3)
    app.vm.newEvent(clicked); await tick()
    assert.equal(app.vm.draft.startLocal, clicked + 'T09:00')
    assert.equal(app.vm.draft.endLocal, clicked + 'T10:00')
    assert.equal(app.vm.draft.zone, Intl.DateTimeFormat().resolvedOptions().timeZone)
    document.querySelector('[role="dialog"]').dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await tick()
    assert.equal(app.vm.modal, null)
    assert.equal(document.activeElement, trigger)
    app.vm.newEvent(clicked); await tick(); app.vm.draft.title = '跨日安排'; app.vm.draft.startLocal = clicked + 'T23:00'; app.vm.draft.endLocal = calendar.addDays(clicked, 1) + 'T01:00'
    await app.vm.saveEvent(); await tick()
    const write = app.calls.find(call => call.method === 'post')
    assert.equal(write.body.recurrence, 'NONE')
    assert.deepEqual(write.body.daysOfWeek, [])
    assert.equal(write.body.repeatUntil, null)
  } finally { app.cleanup() }
})

test('unrelated edits preserve seconds and fractional precision even within one minute', async () => {
  const app = await mountCalendar()
  try {
    const original = { ...app.data.definition, startLocal: app.data.day + 'T09:00:15.234567891', endLocal: app.data.day + 'T09:00:45.987654321' }
    app.vm.definitions = [original]
    app.vm.openItem(app.data.occurrence); await tick()
    assert.equal(document.querySelector('.event-form input[placeholder]').maxLength, 300)
    assert.equal(document.querySelector('.event-form textarea').maxLength, 4000)
    app.vm.draft.title = '仅修改标题'
    await app.vm.saveEvent(); await tick()
    const unchanged = app.calls.find(call => call.method === 'put')
    assert.equal(unchanged.body.startLocal, original.startLocal)
    assert.equal(unchanged.body.endLocal, original.endLocal)
    app.vm.definitions = [original]
    app.vm.openItem(app.data.occurrence); await tick()
    app.vm.draft.endLocal = app.data.day + 'T09:30'
    await app.vm.saveEvent(); await tick()
    const changed = app.calls.filter(call => call.method === 'put').at(-1)
    assert.equal(changed.body.startLocal, original.startLocal)
    assert.equal(changed.body.endLocal, app.data.day + 'T09:30')
  } finally { app.cleanup() }
})

test('deadline edits use shared task version, clearing removes marker, completion removes busy hint', async () => {
  const data = seed(), task = { id: 'task-1', text: '提交作业', priority: 'HIGH', notes: '合成备注', version: 7, status: 'OPEN', dueAt: data.occurrence.startAt + 1800000, sourceMailId: 123 }
  const app = await mountCalendar({ tasks: [task, { ...task, id: 'completed', status: 'COMPLETED' }] })
  try {
    assert.match(document.querySelector('.conflict-panel').textContent, /截止时有/)
    const marker = calendar.deadlineItems([task], app.vm.range)[0]
    app.vm.openItem(marker); await tick(); app.vm.taskDue = ''; await app.vm.saveDeadline(); await tick()
    const write = app.calls.find(call => call.method === 'editTask')
    assert.equal(write.task.version, 7)
    assert.equal(write.input.dueAt, null)
    assert.equal(write.input.text, task.text)
    assert.equal(write.input.priority, 'HIGH')
    assert.equal(document.querySelectorAll('.event-card.TASK').length, 0)
    app.vm.openItem(marker); await tick()
    assert.equal(app.vm.taskDue, '')
    await app.vm.finishTask(); await tick()
    assert.equal(app.calls.find(call => call.method === 'completeTask').task.version, 8)
    assert.equal(app.calls.find(call => call.method === 'completeTask').completed, true)
    assert.doesNotMatch(document.querySelector('.conflict-panel').textContent, /截止时有/)
  } finally { app.cleanup() }
})

test('version conflict keeps the draft and asks to reopen current data; cancelled deletion writes nothing', async () => {
  const app = await mountCalendar({ put: () => Promise.reject({ response: { status: 409, data: { message: '日程已被修改' } } }), confirm: () => Promise.reject('cancel') })
  try {
    app.vm.openItem(app.data.occurrence); await tick(); app.vm.draft.title = '保留草稿'
    await app.vm.saveEvent(); await tick()
    assert.equal(app.vm.modal, 'event')
    assert.equal(app.vm.draft.title, '保留草稿')
    assert.match(document.querySelector('[role="dialog"] .notice').textContent, /重新打开最新日程/)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('[role="dialog"] .notice').textContent, /Close and reopen the updated event/)
    i18n.setLocale('zh-CN'); await tick()
    assert.match(document.querySelector('[role="dialog"] .notice').textContent, /重新打开最新日程/)
    await app.vm.removeEvent()
    assert.equal(app.calls.some(call => call.method === 'delete'), false)
  } finally { app.cleanup(); i18n.setLocale('zh-CN') }
})
