import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

// Compile the real SFC in memory; only external services and child async panels are
// replaced. No production source is rewritten and no HTTP listener is started.
const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/', pretendToBeVisual: true })
globalThis.window = dom.window; globalThis.document = dom.window.document
globalThis.localStorage = dom.window.localStorage
globalThis.Element = dom.window.Element; globalThis.SVGElement = dom.window.SVGElement
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator })
window.scrollTo = () => {}
const Vue = await import('vue')
const i18n = await import('./i18n/index.js')
const focusController = await import('./stores/focusController.js')
const focusShortcut = await import('./utils/focusShortcut.js')
const focusTime = await import('./utils/focusTime.js')
const philosophyQuotes = await import('./utils/philosophyQuotes.js')
const { readPreview, savePreview } = await import('./utils/previewCache.js')
const tick = async () => { for (let i = 0; i < 20; i++) { await Promise.resolve(); await Vue.nextTick() } }
const panel = { render() { return Vue.h('div', { class: 'child-panel' }) } }
const templateElement = { setup(_props, { slots }) { return () => Vue.h('span', {}, slots.default?.()) } }

function compile(source, modules, exposed = '') {
  if (exposed) source = source.replace('</script>', `\ndefineExpose({ ${exposed} })\n</script>`)
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'integration-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    if (!(path in modules)) throw new Error('Unmocked import: ' + path)
    if (binding.startsWith('{')) {
      return 'const ' + binding.replace(/\bas\b/g, ':') + ' = modules[' + JSON.stringify(path) + '];'
    }
    return 'const ' + binding + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function mail(id) { return { id, subject: 'Synthetic subject ' + id, sender: 'test@example.com', source: 'GMAIL', summary: 'Synthetic summary', createdTime: '2026-09-20T10:00:00', inboxRead: false, starred: false } }

async function mountWorkspace(overrides = {}) {
  localStorage.clear()
  i18n.setLocale('zh-CN')
  const calls = [], messages = [], timers = new Map()
  let timerId = 0
  const originalInterval = globalThis.setInterval, originalClear = globalThis.clearInterval
  globalThis.setInterval = fn => { const id = ++timerId; timers.set(id, fn); return id }
  globalThis.clearInterval = id => timers.delete(id)
  const axios = {
    async get(url, config = {}) {
      calls.push({ method: 'get', url, config })
      const override = overrides.get?.(url, config)
      if (override !== undefined) return override
      if (url === '/api/mails/revision') return { data: { totalElements: 25, version: 'v1' } }
      if (url === '/api/mails/summaries') return { data: { content: Array.from({ length: config.params.page === 1 ? 5 : 20 }, (_, n) => mail(config.params.page * 20 + n + 1)), totalElements: 25 } }
      if (url === '/api/outlook/status') return { data: { connected: true } }
      if (url === '/api/dashboard/preferences') return { data: { remindersEnabled: 'false' } }
      if (url === '/api/mails/reminders') return { data: [] }
      if (url === '/api/dashboard/weather') {
        const hour = new Date().toISOString().slice(0, 13) + ':00'
        return { data: { timezone: 'UTC', current: { time: hour, temperature_2m: 20, weather_code: 1 }, hourly: { time: [hour], temperature_2m: [20], weather_code: [1], precipitation_probability: [20] } } }
      }
      if (/^\/api\/mails\/\d+$/.test(url)) return { data: { ...mail(Number(url.split('/').at(-1))), content: 'Synthetic body' } }
      throw new Error('Unexpected GET ' + url)
    },
    async post(url, body, config) {
      calls.push({ method: 'post', url, body, config })
      const override = overrides.post?.(url, body, config)
      if (override !== undefined) return override
      if (url === '/api/outlook/refresh') return { data: { connected: true } }
      throw new Error('Unexpected POST ' + url)
    },
    async patch(url, body) {
      calls.push({ method: 'patch', url, body })
      const override = overrides.patch?.(url, body)
      return override === undefined ? { data: { inboxRead: true } } : override
    },
    async put(url, body) {
      calls.push({ method: 'put', url, body })
      const override = overrides.put?.(url, body)
      return override === undefined ? { data: {} } : override
    }
  }
  const modules = {
    './i18n/index.js': i18n,
    './stores/focusController.js': focusController,
    './utils/focusShortcut.js': focusShortcut,
    '../stores/focusController.js': focusController,
    '../utils/focusTime.js': focusTime,
    '../i18n/index.js': i18n,
    '../utils/philosophyQuotes.js': philosophyQuotes,
    vue: Vue, axios,
    './utils/asyncPanel.js': { createAsyncPanel: () => panel },
    'element-plus': { ElSwitch: templateElement, ElIcon: templateElement, ElMessage: { error(message) { messages.push({ type: 'error', message }) }, success(message) { messages.push({ type: 'success', message }) } }, ElNotification() {}, ElMessageBox: {} },
    '@element-plus/icons-vue': { Loading: panel },
    './stores/taskStore': { useTaskStore: () => ({ summary: Vue.ref({ open: 0 }), refresh: async () => {} }) },
    './utils/previewCache': { readPreview, savePreview }
  }
  if (overrides.realFocus) {
    const focus = compile(await readFile(new URL('./components/FocusCenter.vue', import.meta.url), 'utf8'), modules)
    modules['./utils/asyncPanel.js'].createAsyncPanel = loader => String(loader).includes('FocusCenter.vue') ? focus : panel
  }
  modules['./components/MainDashboard.vue'] = compile(await readFile(new URL('./components/MainDashboard.vue', import.meta.url), 'utf8'), modules)
  const component = compile(await readFile(new URL('./InboxWorkspace.vue', import.meta.url), 'utf8'), modules,
    'navigate, fetchSummaries, loadMoreMail, openMailDetail, closeMailDetail, selectedMail, mailDetailLoading, analyzeSelectedMail, mailInsightResult, mailInsightLoading, enableNotifications, remindersEnabled, reminderSettingBusy, markMailRead, mailPlanRevision, selectMailView')
  const app = Vue.createApp(component, overrides.props || {})
  const vm = app.mount('#root')
  await tick()
  return { vm, calls, messages, timers, cleanup() {
    app.unmount(); document.querySelector('#root').innerHTML = ''
    globalThis.setInterval = originalInterval; globalThis.clearInterval = originalClear
  } }
}

test('language switch updates the dashboard and mail interface without translating content or making requests', async () => {
  const context = await mountWorkspace({
    get(url) {
      if (url === '/api/mails/1') return { data: { ...mail(1), subject: '中文原始邮件主题', originalSubject: '中文原始邮件主题', sender: '中文发件人', summary: '原始中文摘要', content: '请保留中文邮件正文' } }
    }
  })
  try {
    const before = context.calls.length
    assert.equal(document.documentElement.lang, 'zh-CN')
    assert.ok(philosophyQuotes.chinesePhilosophyQuotes.some(quote => document.querySelector('.quote-block h1').textContent.includes(quote.text)))
    const languageButtons = document.querySelectorAll('.dashboard-language button')
    assert.equal(languageButtons.length, 2)
    languageButtons[1].click()
    await tick()
    assert.equal(document.documentElement.lang, 'en-US')
    assert.equal(localStorage.getItem(i18n.LANGUAGE_STORAGE_KEY), 'en-US')
    assert.match(document.querySelector('.dashboard-shell').textContent, /Coding practice/i)
    assert.match(document.querySelector('.dashboard-shell').textContent, /Job applications/i)
    assert.ok(philosophyQuotes.englishPhilosophyQuotes.some(quote =>
      document.querySelector('.quote-block h1').textContent.includes(quote.text)
      && document.querySelector('.quote-author').textContent.includes(quote.author)))
    assert.equal(context.calls.length, before)
    context.vm.navigate('mail')
    await tick()
    assert.equal(document.querySelector('.dashboard-language'), null)
    assert.match(document.querySelector('.inbox-tabs').textContent, /Mail task planner/i)
    assert.equal(document.querySelector('input[maxlength="200"]').placeholder, 'Search subject, sender or body')
    await context.vm.openMailDetail(mail(1))
    await tick()
    assert.match(document.querySelector('.mail-detail-sheet').textContent, /中文原始邮件主题/)
    assert.match(document.querySelector('.mail-detail-sheet').textContent, /中文发件人/)
    assert.match(document.querySelector('.mail-detail-sheet').textContent, /Analyze this email/)
    assert.equal(context.vm.selectedMail.content, '请保留中文邮件正文')
    context.vm.closeMailDetail()
    context.vm.navigate('home')
    await tick()
    const callsBeforeReturn = context.calls.length
    document.querySelector('.dashboard-language button').click()
    await tick()
    assert.match(document.querySelector('.dashboard-shell').textContent, /刷题进度与熟练度/)
    assert.ok(philosophyQuotes.chinesePhilosophyQuotes.some(quote => document.querySelector('.quote-block h1').textContent.includes(quote.text)))
    assert.equal(context.calls.length, callsBeforeReturn)
  } finally { context.cleanup(); i18n.setLocale('zh-CN') }
})

test('phone navigation hides local administration and mail refresh never calls Outlook control endpoints', async () => {
  const context = await mountWorkspace({ props: { mobile: true } })
  try {
    assert.equal(document.querySelector('.credentials-shortcut'), null)
    assert.equal(document.querySelector('.mobile-entry'), null)
    assert.equal(document.querySelector('.today-strip').textContent.includes('运行状态与备份'), false)
    for (const route of ['credentials', 'operations', 'mobile-connection']) { context.vm.navigate(route); await tick(); assert.ok(document.querySelector('.dashboard-shell')) }
    context.vm.navigate('mail'); await tick()
    document.querySelector('.refresh-button').click(); await tick()
    assert.equal(context.calls.some(call => call.url.startsWith('/api/outlook/')), false)
    assert.equal(document.querySelector('.mail-source-notice'), null)
    assert.match(document.querySelector('.mail-sync-status').textContent, /邮件采集在电脑运行/)
    assert.ok(context.calls.some(call => call.url === '/api/mails/summaries'))
  } finally { context.cleanup() }
})

test('desktop mobile connection card opens the setup panel and has an English label', async () => {
  const context = await mountWorkspace()
  try {
    const entry = document.querySelector('.mobile-entry'); assert.ok(entry)
    i18n.setLocale('en-US'); await tick()
    assert.match(entry.textContent, /Mobile connection/)
    entry.click(); await tick()
    assert.match(document.querySelector('.page-identity').textContent, /Mobile connection/)
  } finally { context.cleanup() }
})

test('marking mail read from planner detail reloads suggestions and closes detail', async () => {
  const context = await mountWorkspace()
  try {
    context.vm.navigate('mail')
    context.vm.selectMailView('PLAN')
    await tick()
    const before = document.querySelector('.child-panel')
    await context.vm.openMailDetail(mail(1))
    await context.vm.markMailRead(mail(1))
    await tick()
    assert.equal(context.vm.selectedMail, null)
    assert.equal(context.vm.mailPlanRevision, 1)
    assert.notEqual(document.querySelector('.child-panel'), before)
    assert.deepEqual(context.calls.filter(call => call.method === 'patch'), [{ method: 'patch', url: '/api/mails/1/read', body: { value: true } }])
    assert.ok(!context.calls.some(call => call.url.startsWith('/api/tasks')))
  } finally { context.cleanup() }
})

test('failed local read keeps planner and detail unchanged', async () => {
  const context = await mountWorkspace({ patch: () => Promise.reject(new Error('Synthetic failed save')) })
  try {
    await context.vm.openMailDetail(mail(1))
    await context.vm.markMailRead(mail(1))
    assert.equal(context.vm.mailPlanRevision, 0)
    assert.equal(context.vm.selectedMail.id, 1)
    assert.ok(context.messages.some(message => message.type === 'error'))
  } finally { context.cleanup() }
})

test('home loads lightweight counts/weather without full news/watch lists or weather AI', async () => {
  const context = await mountWorkspace()
  try {
    assert.ok(document.querySelector('.dashboard-shell'))
    assert.ok(context.calls.some(call => call.url === '/api/mails/revision'))
    assert.ok(context.calls.some(call => call.url === '/api/dashboard/weather'))
    for (const path of ['/api/mails/summaries', '/api/dashboard/us-news', '/api/dashboard/trends', '/api/dashboard/watch', '/api/dashboard/weather/analysis'])
      assert.equal(context.calls.some(call => call.url === path), false, 'home must defer ' + path)
  } finally { context.cleanup() }
})

test('home keeps the mail center and a single credentials shortcut without the retired video workspace', async () => {
  const context = await mountWorkspace()
  try {
    assert.equal(document.querySelectorAll('.dashboard-footer .credentials-shortcut').length, 1)
    assert.equal(document.querySelector('.app-dock'), null)
    assert.ok(document.querySelector('.mail-widget'))
    document.querySelector('.credentials-shortcut').click()
    await tick()
    assert.equal(document.querySelector('.page-identity strong').textContent, '密钥管理')
    assert.ok(document.querySelector('.credentials-workspace'))
    document.querySelector('.home-button').click()
    await tick()
    document.querySelector('.mail-widget').click()
    await tick()
    assert.equal(document.querySelectorAll('.mail-card').length, 20)
    assert.ok(context.calls.filter(call => call.url === '/api/mails/summaries').every(call => call.config.params.tab === 'EMAIL'))
    assert.ok(!context.calls.some(call => /youtube|oauth/i.test(call.url)))
  } finally { context.cleanup() }
})

test('calendar home entry opens its own workspace and returns using main menu', async () => {
  const context = await mountWorkspace()
  try {
    document.querySelector('.calendar-entry').click(); await tick()
    assert.equal(document.querySelector('.page-identity strong').textContent, '日历与课程中心')
    assert.ok(document.querySelector('.child-panel'))
    document.querySelector('.home-button').click(); await tick()
    assert.ok(document.querySelector('.calendar-entry'))
    assert.equal(document.querySelector('.workspace-header'), null)
  } finally { context.cleanup() }
})

test('career home entry opens the job application workspace and returns using main menu', async () => {
  const context = await mountWorkspace()
  try {
    document.querySelector('.career-entry').click(); await tick()
    assert.equal(document.querySelector('.page-identity strong').textContent, '求职与申请中心')
    assert.ok(document.querySelector('.child-panel'))
    document.querySelector('.home-button').click(); await tick()
    assert.ok(document.querySelector('.career-entry'))
    assert.equal(document.querySelector('.workspace-header'), null)
  } finally { context.cleanup() }
})

test('focus home entry opens the timer workspace and returns using main menu', async () => {
  const context = await mountWorkspace()
  try {
    document.querySelector('.focus-entry').click(); await tick()
    assert.equal(document.querySelector('.page-identity strong').textContent, '专注与每周复盘')
    assert.ok(document.querySelector('.child-panel'))
    document.querySelector('.home-button').click(); await tick()
    assert.ok(document.querySelector('.focus-entry'))
    assert.equal(document.querySelector('.workspace-header'), null)
  } finally { context.cleanup() }
})

function focusSnapshot() {
  const now = Date.now(), today = focusTime.localDay(now, Intl.DateTimeFormat().resolvedOptions().timeZone)
  return { serverNow: now, today, daily: { EFFECTIVE: 0, INEFFECTIVE: 0 }, historyDays: [], active: { id: 'session-0', category: 'INEFFECTIVE', leaseUntil: now + 45000 } }
}
function enter(target = window, options = {}) { return target.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...options })) }

test('one Enter owner switches on home and other pages, shares Focus state, and detaches on workspace exit', async () => {
  const state = focusSnapshot(); let writes = 0
  const context = await mountWorkspace({ realFocus: true,
    get: url => url === '/api/focus' ? { data: structuredClone(state) } : undefined,
    post(url, body) {
      if (url !== '/api/focus/switch') return
      assert.equal(body.id, state.active.id)
      state.active.category = state.active.category === 'EFFECTIVE' ? 'INEFFECTIVE' : 'EFFECTIVE'
      state.active.id = `session-${++writes}`
      return { data: structuredClone(state) }
    }
  })
  try {
    assert.equal(context.calls.some(call => call.url.startsWith('/api/focus')), false, 'Startup makes no extra timer request')
    for (const route of ['home', 'mail', 'tasks', 'calendar', 'practice', 'stocks', 'focus', 'home']) {
      context.vm.navigate(route); await tick()
      const before = writes
      assert.equal(enter(), false); await tick()
      assert.equal(writes, before + 1, `Exactly one switch on ${route}`)
      if (route === 'focus') assert.equal(document.querySelector('.focus-center').dataset.focusState, state.active.category)
      else {
        assert.match(document.querySelector('.focus-shortcut-notice').textContent, /已切换为/)
        assert.ok(document.querySelector('.focus-shortcut-notice').classList.contains(state.active.category.toLowerCase()))
      }
    }
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.focus-shortcut-notice').textContent, /Switched to/)
    assert.equal(context.calls.filter(call => call.method === 'get' && call.url === '/api/focus').length, 2, 'Only initial shortcut and focus mount fetch records')
  } finally { context.cleanup(); i18n.setLocale('zh-CN') }
  enter(); await tick(); assert.equal(writes, 8)
})

test('app shortcut preserves text entry, IME, normal controls, mail-card Enter, dialogs and standby', async () => {
  const state = focusSnapshot(); let writes = 0
  const context = await mountWorkspace({ get: url => url === '/api/focus' ? { data: state } : undefined, post: url => url === '/api/focus/switch' ? (writes++, { data: state }) : undefined })
  const holder = document.createElement('div'); document.body.append(holder)
  try {
    for (const markup of ['<input>', '<textarea></textarea>', '<select></select>', '<button>Action</button>', '<a href="#">Link</a>', '<div contenteditable="true"><span>Text</span></div>', '<div role="combobox">Choose</div>', '<article tabindex="0">Open</article>', '<details><summary>Expand</summary></details>']) {
      holder.innerHTML = markup
      enter(holder.querySelector('span, summary') || holder.firstChild)
    }
    holder.innerHTML = ''
    for (const options of [{ repeat: true }, { isComposing: true }, { keyCode: 229 }, { ctrlKey: true }, { altKey: true }, { shiftKey: true }, { metaKey: true }]) enter(window, options)
    const prevented = new window.KeyboardEvent('keydown', { key: 'Enter', cancelable: true }); prevented.preventDefault(); window.dispatchEvent(prevented)
    document.querySelector('#root').setAttribute('inert', ''); enter(); document.querySelector('#root').removeAttribute('inert')
    holder.innerHTML = '<section role="dialog" aria-modal="true">Visible dialog</section>'; enter(); holder.innerHTML = ''
    context.vm.navigate('mail'); await tick()
    enter(document.querySelector('.mail-card')); await tick()
    assert.ok(document.querySelector('.mail-detail-sheet'), 'Mail-card Enter keeps its original action')
    enter(); await tick()
    assert.equal(writes, 0)
    assert.equal(context.calls.some(call => call.url.startsWith('/api/focus')), false)
    context.vm.closeMailDetail(); await tick()
    holder.innerHTML = '<div style="display:none"><section role="dialog">Hidden dialog</section></div>'
    enter(); await tick(); assert.equal(writes, 1, 'Hidden retained dialogs do not block the shortcut')
  } finally { holder.remove(); document.querySelector('#root').removeAttribute('inert'); context.cleanup() }
})

test('pending lazy focus read blocks a second Enter and late read after workspace exit cannot switch', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ get: url => url === '/api/focus' ? pending.promise : undefined })
  enter(); enter(); await tick()
  assert.equal(context.calls.filter(call => call.url === '/api/focus').length, 1)
  assert.match(document.querySelector('.focus-shortcut-notice').textContent, /正在切换/)
  context.cleanup()
  pending.resolve({ data: focusSnapshot() }); await tick()
  assert.equal(context.calls.some(call => call.url === '/api/focus/switch'), false)
  assert.equal(document.querySelector('.focus-shortcut-notice'), null)
})

test('standby during a lazy read clears its pending notice without sending a switch', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ get: url => url === '/api/focus' ? pending.promise : undefined })
  try {
    enter(); await tick()
    assert.ok(document.querySelector('.focus-shortcut-notice'))
    document.querySelector('#root').setAttribute('inert', '')
    pending.resolve({ data: focusSnapshot() }); await tick()
    assert.equal(context.calls.some(call => call.url === '/api/focus/switch'), false)
    assert.equal(document.querySelector('.focus-shortcut-notice'), null)
  } finally { document.querySelector('#root').removeAttribute('inert'); context.cleanup() }
})

test('practice home entry opens the practice workspace and returns using main menu', async () => {
  const context = await mountWorkspace()
  try {
    document.querySelector('.practice-entry').click(); await tick()
    assert.equal(document.querySelector('.page-identity strong').textContent, '刷题进度与熟练度')
    assert.ok(document.querySelector('.child-panel'))
    document.querySelector('.home-button').click(); await tick()
    assert.ok(document.querySelector('.practice-entry'))
    assert.equal(document.querySelector('.workspace-header'), null)
  } finally { context.cleanup() }
})

test('mail load-more requests page 1 and appends without discarding the first page', async () => {
  const context = await mountWorkspace()
  try {
    document.querySelector('.mail-widget').click(); await tick()
    assert.equal(document.querySelectorAll('.mail-card').length, 20)
    const button = [...document.querySelectorAll('button')].find(node => node.textContent.includes('加载更多'))
    assert.ok(button); button.click(); await tick()
    const requests = context.calls.filter(call => call.url === '/api/mails/summaries')
    assert.equal(requests.at(-1).config.params.page, 1)
    assert.equal(document.querySelectorAll('.mail-card').length, 25)
    assert.equal(new Set([...document.querySelectorAll('.mail-title h3')].map(node => node.textContent)).size, 25)
  } finally { context.cleanup() }
})

test('a background revision poll cannot cancel a pending explicit load-more', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ get: (url, config) => url === '/api/mails/summaries' && config.params.page === 1 ? pending.promise : undefined })
  try {
    context.vm.navigate('mail'); await tick()
    context.vm.loadMoreMail(); await tick()
    await context.vm.fetchSummaries(true)
    pending.resolve({ data: { content: Array.from({ length: 5 }, (_, n) => mail(n + 21)), totalElements: 25 } })
    await tick()
    assert.equal(document.querySelectorAll('.mail-card').length, 25)
  } finally { context.cleanup() }
})

test('returning home during a mail request cancels its loading state and refreshes the home count', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ get: url => url === '/api/mails/summaries' ? pending.promise : undefined })
  try {
    context.vm.navigate('mail'); await tick()
    const before = context.calls.filter(call => call.url === '/api/mails/revision').length
    context.vm.navigate('home'); await tick()
    assert.equal(context.calls.filter(call => call.url === '/api/mails/revision').length, before + 1)
    pending.resolve({ data: { content: [mail(99)], totalElements: 1 } }); await tick()
    await context.vm.fetchSummaries(true); await tick()
    assert.equal(context.calls.filter(call => call.url === '/api/mails/revision').length, before + 2)
    assert.equal(document.querySelector('.mail-detail-sheet'), null)
    assert.ok(document.querySelector('.dashboard-shell'))
  } finally { context.cleanup() }
})

test('late detail response cannot overwrite a newer selected mail or a closed dialog', async () => {
  const first = deferred(), second = deferred(), third = deferred()
  const responses = { '/api/mails/1': first, '/api/mails/2': second, '/api/mails/3': third }
  const context = await mountWorkspace({ get: url => responses[url]?.promise })
  try {
    const one = context.vm.openMailDetail(mail(1)); const two = context.vm.openMailDetail(mail(2))
    second.resolve({ data: { ...mail(2), content: 'Latest body' } }); await two; await tick()
    first.resolve({ data: { ...mail(1), content: 'Stale body' } }); await one; await tick()
    assert.equal(context.vm.selectedMail.id, 2)
    assert.match(document.querySelector('.mail-detail-header h2').textContent, /subject 2/)
    const three = context.vm.openMailDetail(mail(3)); context.vm.closeMailDetail()
    third.resolve({ data: { ...mail(3), content: 'Closed body' } }); await three; await tick()
    assert.equal(context.vm.selectedMail, null)
    assert.equal(document.querySelector('.mail-detail-sheet'), null)
    assert.equal(context.vm.mailDetailLoading, false)
  } finally { context.cleanup() }
})

test('mail detail generates a Chinese analysis only when requested', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ post: url => url === '/api/mails/1/analysis' ? pending.promise : undefined })
  try {
    await context.vm.openMailDetail(mail(1)); await tick()
    assert.ok(!context.calls.some(call => call.url.endsWith('/analysis')))
    assert.equal(document.querySelector('.mail-insight-card h3').textContent, 'AI 分析')
    document.querySelector('.mail-insight-heading button').click(); await tick()
    assert.equal(context.vm.mailInsightLoading, true)
    assert.equal(context.calls.filter(call => call.url === '/api/mails/1/analysis').length, 1)
    pending.resolve({ data: { purpose: '学校介绍移民信息说明会。', keyPoints: ['可了解毕业工签和学签延期。'], actionStatus: 'OPTIONAL', actionExplanation: '如有兴趣，可以参加。', importantTimes: ['9月25日下午2点。'], notes: [], scopeNote: '' } })
    await tick()
    assert.match(document.querySelector('.mail-insight-report').textContent, /学校介绍移民信息说明会/)
    assert.match(document.querySelector('.mail-insight-report').textContent, /可自愿选择/)
    assert.equal(context.vm.mailInsightLoading, false)
  } finally { context.cleanup() }
})

test('a closed detail never displays a late analysis for another mail', async () => {
  const pending = deferred()
  const context = await mountWorkspace({ post: url => url === '/api/mails/1/analysis' ? pending.promise : undefined })
  try {
    await context.vm.openMailDetail(mail(1)); await tick()
    const analyzing = context.vm.analyzeSelectedMail(); await tick()
    context.vm.closeMailDetail()
    await context.vm.openMailDetail(mail(2)); await tick()
    pending.resolve({ data: { purpose: '旧邮件分析', keyPoints: [], actionStatus: 'NONE', actionExplanation: '无需处理。', importantTimes: [], notes: [], scopeNote: '' } })
    await analyzing; await tick()
    assert.equal(context.vm.selectedMail.id, 2)
    assert.equal(context.vm.mailInsightResult, null)
    assert.equal(context.vm.mailInsightLoading, false)
  } finally { context.cleanup() }
})

test('denied desktop notification permission uses in-app reminders and permits disabling', async () => {
  const original = globalThis.Notification, originalWindow = window.Notification
  const notification = { permission: 'denied', requestPermission: async () => 'denied' }
  globalThis.Notification = notification; window.Notification = notification
  const context = await mountWorkspace()
  try {
    context.vm.navigate('mail'); await tick()
    await context.vm.enableNotifications(); await tick()
    assert.equal(context.vm.remindersEnabled, true)
    assert.equal(context.vm.reminderSettingBusy, false)
    assert.ok(context.messages.some(item => item.type === 'success' && item.message.includes('未获得桌面通知权限')))
    assert.ok([...document.querySelectorAll('button')].some(node => node.textContent.includes('关闭邮件提醒')))
    await context.vm.enableNotifications(); await tick()
    assert.equal(context.vm.remindersEnabled, false)
    const settings = context.calls.filter(call => call.method === 'put' && call.url === '/api/dashboard/preferences')
    assert.deepEqual(settings.map(call => call.body.remindersEnabled), ['true', 'false'])
    assert.ok([...document.querySelectorAll('button')].some(node => node.textContent.includes('启用邮件提醒')))
  } finally {
    context.cleanup(); globalThis.Notification = original
    if (originalWindow === undefined) delete window.Notification; else window.Notification = originalWindow
  }
})

test('reminder setting save failure leaves the setting unchanged and allows retry', async () => {
  const context = await mountWorkspace({ put: () => Promise.reject(new Error('Synthetic offline')) })
  try {
    await context.vm.enableNotifications(); await tick()
    assert.equal(context.vm.remindersEnabled, false)
    assert.equal(context.vm.reminderSettingBusy, false)
    assert.ok(context.messages.some(item => item.type === 'error' && item.message.includes('保存失败')))
    assert.equal(context.messages.some(item => item.type === 'success'), false)
  } finally { context.cleanup() }
})

async function mountOperations(overrides = {}) {
  const calls = [], messages = []
  const originalInterval = globalThis.setInterval, originalClear = globalThis.clearInterval
  globalThis.setInterval = () => 1; globalThis.clearInterval = () => {}
  const axios = {
    async get(url) { calls.push({ method: 'get', url }); return { data: url.endsWith('/sources') ? { channels: [] } : {} } },
    async post(url, body) {
      calls.push({ method: 'post', url, body })
      const override = overrides.post?.(url, body)
      return override === undefined ? { data: { tasks: 1, watchlist: 0, mailStates: 0, matchedMails: 0, missingMails: 0 } } : override
    }
  }
  const modules = { '../i18n/index.js': i18n, vue: Vue, axios, 'element-plus': {
    ElMessage: { error(message) { messages.push({ type: 'error', message }) }, success(message) { messages.push({ type: 'success', message }) } },
    ElMessageBox: { confirm: overrides.confirm || (async () => {}) }
  } }
  const component = compile(await readFile(new URL('./components/OperationsPanel.vue', import.meta.url), 'utf8'), modules,
    'selectBackup, restoreBackup, cancelBackup, backup, preview, busy')
  const app = Vue.createApp(component), vm = app.mount('#root')
  await tick()
  return { vm, calls, messages, cleanup() {
    app.unmount(); document.querySelector('#root').innerHTML = ''
    globalThis.setInterval = originalInterval; globalThis.clearInterval = originalClear
  } }
}
function backupEvent(id) { return { target: { files: [{ size: 20, text: async () => JSON.stringify({ id }) }], value: id } } }

test('late backup validation cannot replace the latest file or restore a cancelled preview', async () => {
  const first = deferred(), second = deferred(), third = deferred()
  const validations = { first, second, third }
  const context = await mountOperations({ post: (url, body) => url.endsWith('/validate') ? validations[body.id].promise : undefined })
  try {
    const a = context.vm.selectBackup(backupEvent('first')); await tick()
    const b = context.vm.selectBackup(backupEvent('second')); await tick()
    second.resolve({ data: { tasks: 2 } }); await b
    first.resolve({ data: { tasks: 1 } }); await a; await tick()
    assert.equal(context.vm.backup.id, 'second')
    assert.equal(context.vm.preview.tasks, 2)
    assert.equal(context.vm.busy.validation, false)
    const c = context.vm.selectBackup(backupEvent('third')); await tick()
    context.vm.cancelBackup()
    third.resolve({ data: { tasks: 3 } }); await c; await tick()
    assert.equal(context.vm.backup, null)
    assert.equal(context.vm.preview, null)
  } finally { context.cleanup() }
})

test('backup restore waits for confirmation and submits the locked validated payload', async () => {
  const confirmation = deferred()
  const context = await mountOperations({ confirm: () => confirmation.promise })
  try {
    await context.vm.selectBackup(backupEvent('approved'))
    const restoring = context.vm.restoreBackup(); await tick()
    assert.equal(context.vm.busy.restore, true)
    assert.equal(context.calls.some(call => call.url.endsWith('/restore')), false)
    await context.vm.selectBackup(backupEvent('replacement')); context.vm.cancelBackup()
    assert.equal(context.vm.backup.id, 'approved')
    confirmation.resolve(); await restoring; await tick()
    const requests = context.calls.filter(call => call.url.endsWith('/restore'))
    assert.equal(requests.length, 1)
    assert.equal(requests[0].body.id, 'approved')
    assert.equal(context.vm.busy.restore, false)
    assert.equal(context.vm.preview, null)
  } finally { context.cleanup() }
})
