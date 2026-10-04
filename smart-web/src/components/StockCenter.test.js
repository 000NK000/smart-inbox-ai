import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript } from '@vue/compiler-sfc'

const dom = new JSDOM('<div id="stocks-test"></div>', { url: 'http://localhost/' })
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, SVGElement: dom.window.SVGElement })
const Vue = await import('vue')
const i18n = await import('../i18n/index.js')
const chartHelpers = await import('./stockHistory.js')
const source = await readFile(new URL('./StocksCenter.vue', import.meta.url), 'utf8')
const chartSource = await readFile(new URL('./StockHistoryChart.vue', import.meta.url), 'utf8')
const tick = async () => { for (let index = 0; index < 30; index++) { await Promise.resolve(); await Vue.nextTick() } }
function compile(modules, inputSource = source) {
  const expose = 'tab, status, portfolio, watchlist, reports, loading, busy, sessionToken, selectedModel, canAnalyze, editor, draft, error, notice, selectedReportId, openEditor, saveWatch, deleteWatch, analyze, connect, disconnect, refreshPortfolio, load, deleteReport, safeAuthUrl, concentration, history, historyTarget, historyRange, historyLoading, historyError, loadHistory, refreshHistory'
  const { descriptor } = parse(inputSource === source ? source.replace('</script>', `\ndefineExpose({ ${expose} })\n</script>`) : inputSource)
  const script = compileScript(descriptor, { id: 'stocks-test', inlineTemplate: true })
  const compiled = script.content.replace(/^import\s+(.+?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm, (_statement, binding, path) => {
    assert.ok(path in modules, `Unmocked import: ${path}`)
    return 'const ' + (binding.startsWith('{') ? binding.replace(/\bas\b/g, ':') : binding) + ' = modules[' + JSON.stringify(path) + '];'
  }).replace('export default', 'return')
  return new Function('modules', compiled)(modules)
}
function seed(connected = true) {
  return {
    status: { ibkr: { connected, loginSupported: true }, gpt: { connected, planEnabled: connected, loginSupported: true }, models: connected ? [{ id: 'account-model', name: 'Account model' }] : [], canAnalyze: connected },
    portfolio: { asOf: '2026-09-30T14:00:00Z', source: 'IBKR', stale: false, positions: [
      { id: 'apple', contractId: 101, symbol: 'AAPL', name: 'Apple', currency: 'USD', quantity: 1, averageCost: 80, price: 100, marketValue: 100, unrealizedPnl: 20 },
      { id: 'short', contractId: 102, symbol: 'SHORT', currency: 'USD', quantity: -1, averageCost: 80, price: 100, marketValue: -100, unrealizedPnl: -20 },
      { id: 'canada', contractId: 103, symbol: 'CAN', name: 'Canada', currency: 'CAD', quantity: 1, price: 300, marketValue: 300, unrealizedPnl: null }
    ], totals: [{ currency: 'USD', marketValue: 0, unrealizedPnl: 0 }, { currency: 'CAD', marketValue: 300, unrealizedPnl: null }], accountSummary: { currency: 'CAD', netLiquidation: 950, totalCash: 650, availableFunds: 300, buyingPower: null }, balances: [{ currency: 'USD', cashBalance: 0, settledCash: 0, stockMarketValue: 0 }, { currency: 'CAD', cashBalance: 650, settledCash: 400, stockMarketValue: 300 }] },
    history: { scope: 'PORTFOLIO', range: '1M', currency: 'CAD', metric: 'NAV', source: 'IBKR', asOf: '2026-09-30T14:00:00Z', available: true, stale: false, resolution: '1d', points: [{ time: '2026-09-28T00:00:00Z', value: 900 }, { time: '2026-09-29T00:00:00Z', value: 930 }, { time: '2026-09-30T00:00:00Z', value: 950 }] },
    watchlist: [{ id: 'w1', symbol: 'NVDA', exchange: 'NASDAQ', name: '英伟达', notes: '中文研究问题', version: 3 }],
    reports: []
  }
}
async function mountStocks(options = {}) {
  i18n.setLocale('zh-CN')
  const state = options.state || seed(), calls = [], opened = []
  window.open = (...args) => { opened.push(args); return null }
  let tokenNumber = 0
  const axios = {
    async get(url, config) {
      calls.push({ method: 'get', url, config })
      if (options.get) { const result = await options.get(url, config, state); if (result !== undefined) return result }
      if (url.endsWith('/session')) return { data: { token: `session-${++tokenNumber}` } }
      if (url.startsWith('/api/stocks/history?')) return { data: structuredClone(state.history) }
      const key = url.split('/').at(-1)
      assert.ok(key in state, `Unexpected GET ${url}`)
      return { data: structuredClone(state[key]) }
    },
    async post(url, body, config) {
      calls.push({ method: 'post', url, body, config })
      if (options.post) { const result = await options.post(url, body, config, state); if (result !== undefined) return result }
      if (url.endsWith('/watchlist')) return { data: { id: 'w2', ...body, version: 0 } }
      if (url.endsWith('/reports')) return { data: { id: 'r1', title: '中文研究报告', body: '中文正文 <img src=x onerror=alert(1)>', model: body.model, scope: body.scope, createdAt: '2026-09-30T14:30:00Z' } }
      if (url.endsWith('/portfolio/refresh')) return { data: structuredClone(state.portfolio) }
      if (url.endsWith('/start')) return { data: { url: 'https://auth.openai.com/authorize?state=abc' } }
      return { data: {} }
    },
    async put(url, body, config) {
      calls.push({ method: 'put', url, body, config })
      if (options.put) return options.put(url, body)
      return { data: { id: 'w1', ...body, version: body.version + 1 } }
    },
    async delete(url, config) { calls.push({ method: 'delete', url, config }); return { data: {} } }
  }
  const chart = compile({ vue: Vue, '../i18n/index.js': i18n, './stockHistory.js': chartHelpers }, chartSource)
  const component = compile({ vue: Vue, axios, '../i18n/index.js': i18n, './StockHistoryChart.vue': chart })
  const app = Vue.createApp(component, options.props || {}), vm = app.mount('#stocks-test')
  await tick()
  return { vm, calls, state, opened, cleanup() { app.unmount(); document.querySelector('#stocks-test').innerHTML = ''; i18n.setLocale('zh-CN') } }
}
async function click(element) { assert.ok(element, 'Expected a button'); element.click(); await tick() }
async function input(element, value) { element.value = value; element.dispatchEvent(new dom.window.Event('input', { bubbles: true })); await tick() }

test('disconnected state never fetches holdings or starts research and attaches an in-memory session to protected requests', async () => {
  const app = await mountStocks({ state: seed(false) })
  try {
    assert.equal(app.calls[0].url, '/api/stocks/session')
    assert.equal(app.calls.some(call => call.url === '/api/stocks/portfolio'), false)
    assert.equal(app.calls.some(call => call.method !== 'get'), false)
    for (const call of app.calls.slice(1)) {
      assert.equal(call.config.headers['X-Stock-Session'], 'session-1')
      assert.ok(call.config.timeout > 0)
    }
    assert.equal(document.querySelector('.analysis-bar button').disabled, true)
    assert.match(document.querySelector('.empty').textContent, /连接 IBKR/)
    assert.doesNotMatch(source, /localStorage|sessionStorage/)
    const signal = app.calls[0].config.signal
    app.cleanup()
    assert.equal(signal.aborted, true)
  } catch (error) { app.cleanup(); throw error }
})

test('portfolio keeps currencies separate, calculates within-currency absolute exposure, and preserves unknown numbers', async () => {
  const app = await mountStocks()
  try {
    const cards = document.querySelectorAll('.currency-card')
    assert.equal(cards.length, 2)
    assert.match(cards[0].textContent, /USD/)
    assert.match(cards[1].textContent, /CAD/)
    assert.equal(app.vm.concentration(app.state.portfolio.positions[0]), 50)
    assert.equal(app.vm.concentration(app.state.portfolio.positions[2]), 100)
    const canadianCells = document.querySelectorAll('.positions-table tbody tr')[2].querySelectorAll('td')
    assert.equal(canadianCells[2].textContent, '—')
    assert.equal(canadianCells[5].textContent, '—')
    assert.match(document.querySelector('.snapshot-meta').textContent, /IBKR/)
    app.vm.portfolio.positions[0].marketValue = null; await tick()
    assert.equal(app.vm.concentration(app.state.portfolio.positions[1]), null)
  } finally { app.cleanup() }
})

test('watchlist supports add, versioned edit, explicit remove, and locale changes preserve personal notes', async () => {
  const app = await mountStocks()
  try {
    await click(document.querySelectorAll('.stock-tabs button')[1])
    await click(document.querySelector('.section-heading > button'))
    await input(document.querySelector('input[name=symbol]'), ' msft ')
    await input(document.querySelector('input[name=exchange]'), 'nasdaq')
    await input(document.querySelector('textarea[name=notes]'), '我的新问题')
    document.querySelector('.watch-editor').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })); await tick()
    const created = app.calls.find(call => call.method === 'post' && call.url.endsWith('/watchlist'))
    assert.deepEqual(created.body, { symbol: 'MSFT', exchange: 'NASDAQ', name: '', notes: '我的新问题' })
    await click(document.querySelector('[aria-label="编辑 NVDA"]'))
    await input(document.querySelector('textarea[name=notes]'), '保留中文笔记')
    await app.vm.saveWatch(); await tick()
    assert.equal(app.calls.find(call => call.method === 'put').body.version, 3)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.section-heading h3').textContent, /My watchlist/)
    assert.match(document.querySelector('.watch-card').textContent, /保留中文笔记/)
    await click(document.querySelector('[aria-label="Delete NVDA"]'))
    assert.equal(app.calls.some(call => call.method === 'delete'), false)
    await click(document.querySelector('.delete-confirm button'))
    const removed = app.calls.find(call => call.method === 'delete')
    assert.equal(removed.config.params.version, 4)
    assert.equal(app.vm.watchlist.some(item => item.id === 'w1'), false)
  } finally { app.cleanup() }
})

test('version conflicts keep edits visible and report the backend explanation', async () => {
  const app = await mountStocks({ put: async () => { throw { response: { status: 409, data: { message: '该自选已更新，请刷新后重试。' } } } } })
  try {
    app.vm.tab = 'watchlist'; app.vm.openEditor(app.vm.watchlist[0]); app.vm.draft.notes = 'unsaved notes'
    await app.vm.saveWatch(); await tick()
    assert.equal(app.vm.editor, true)
    assert.equal(app.vm.draft.notes, 'unsaved notes')
    assert.match(document.querySelector('[role=alert]').textContent, /该自选已更新/)
    assert.equal(app.vm.watchlist[0].notes, '中文研究问题')
  } finally { app.cleanup() }
})

test('reports require an explicit click and selected account model, keep Chinese text, and never render response HTML', async () => {
  const app = await mountStocks()
  try {
    assert.equal(app.calls.filter(call => call.method === 'post').length, 0)
    assert.match(document.querySelector('.analysis-bar').textContent, /发送给 OpenAI/)
    await click(document.querySelector('.analysis-bar button'))
    const reportCall = app.calls.find(call => call.method === 'post')
    assert.deepEqual(reportCall.body, { scope: 'PORTFOLIO', model: 'account-model' })
    assert.equal(reportCall.config.timeout, 180000)
    assert.equal(app.vm.tab, 'reports')
    assert.match(document.querySelector('.report-body').textContent, /中文正文 <img/)
    assert.equal(document.querySelector('.report-body img'), null)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.section-heading h3').textContent, /Research reports/)
    assert.match(document.querySelector('.report-detail h3').textContent, /中文研究报告/)
    app.vm.selectedModel = 'unavailable-model'; await tick()
    assert.equal(app.vm.canAnalyze, false)
    await app.vm.analyze('WATCHLIST', 'w1')
    assert.equal(app.calls.filter(call => call.method === 'post').length, 1)
    await click(document.querySelector('.report-meta button'))
    assert.equal(app.calls.some(call => call.method === 'delete'), false)
    await click(document.querySelector('.delete-confirm button'))
    assert.equal(app.calls.find(call => call.method === 'delete').url, '/api/stocks/reports/r1')
    assert.equal(app.vm.reports.length, 0)
  } finally { app.cleanup() }
})

test('watchlist research submits exactly the selected entry and shows disclosure before generation', async () => {
  const app = await mountStocks()
  try {
    await click(document.querySelectorAll('.stock-tabs button')[1])
    assert.match(document.querySelector('.disclosure').textContent, /代码、交易所与可用的 IBKR 行情快照发送给 OpenAI/)
    await click(document.querySelector('.analyze-watch'))
    const call = app.calls.find(call => call.method === 'post')
    assert.deepEqual(call.body, { scope: 'WATCHLIST', model: 'account-model', watchId: 'w1' })
  } finally { app.cleanup() }
})

test('login only opens verified HTTPS provider URLs and does not optimistically mark an account connected', async () => {
  const app = await mountStocks({ state: seed(false) })
  try {
    assert.equal(app.vm.safeAuthUrl('https://api.ibkr.com/oauth2/authorize', 'ibkr'), 'https://api.ibkr.com/oauth2/authorize')
    for (const url of ['javascript:alert(1)', 'https://auth.openai.com.evil.test/authorize', 'http://auth.openai.com/a', 'https://auth.openai.com@evil.test/', 'https://auth.openai.com:444/a']) assert.equal(app.vm.safeAuthUrl(url, 'gpt'), null)
    await app.vm.connect('gpt'); await tick()
    assert.deepEqual(app.opened, [['https://auth.openai.com/authorize?state=abc', '_blank', 'noopener,noreferrer']])
    assert.equal(app.vm.status.gpt.connected, false)
    assert.match(document.querySelector('.auth-link').textContent, /继续/)
    assert.equal(document.querySelector('.auth-link').rel, 'noopener noreferrer')
  } finally { app.cleanup() }
})

test('expired session reads renew and retry once while rejected mutations require another explicit action', async () => {
  let expiredRead = true, expiredPost = true
  const expiry = { response: { status: 403, data: { message: '股票页面连接已过期，请重新打开此页面' } } }
  const app = await mountStocks({
    get: async (url) => { if (url.endsWith('/status') && expiredRead) { expiredRead = false; throw expiry } },
    post: async (url) => { if (url.endsWith('/reports') && expiredPost) { expiredPost = false; throw expiry } }
  })
  try {
    assert.equal(app.vm.sessionToken, 'session-2')
    assert.equal(app.vm.loading, false)
    assert.equal(app.vm.error, '')
    await app.vm.analyze('PORTFOLIO'); await tick()
    assert.equal(app.calls.filter(call => call.method === 'post').length, 1)
    assert.equal(app.vm.sessionToken, 'session-3')
    assert.match(app.vm.error, /再次执行/)
    assert.equal(app.vm.reports.length, 0)
    await app.vm.analyze('PORTFOLIO'); await tick()
    assert.equal(app.vm.reports.length, 1)
  } finally { app.cleanup() }
})

test('failed initial requests stop loading and provide a retry action without invented holdings', async () => {
  const app = await mountStocks({ get: async () => { throw { code: 'ECONNABORTED' } } })
  try {
    assert.equal(app.vm.loading, false)
    assert.equal(app.vm.busy, '')
    assert.match(document.querySelector('[role=alert]').textContent, /请求超时/)
    assert.equal(document.querySelector('.stocks-intro button').disabled, false)
    assert.equal(app.vm.portfolio.positions.length, 0)
    assert.equal(document.querySelector('.initial'), null)
  } finally { app.cleanup() }
})

test('ChatGPT sign-in keeps official branding in both interface languages', async () => {
  const app = await mountStocks({ state: seed(false) })
  try {
    const card = document.querySelectorAll('.connection-card')[1]
    assert.equal(card.querySelector('.connection-actions button').textContent, '使用 ChatGPT 账号登录 / Continue with ChatGPT')
    i18n.setLocale('en-US'); await tick()
    assert.equal(card.querySelector('.connection-actions button').textContent, 'Continue with ChatGPT')
  } finally { app.cleanup() }
})

test('ChatGPT shows the active account and plan usage disclosure, with a fixed official usage link', async () => {
  const state = seed()
  state.status.gpt = { ...state.status.gpt, account: 'My account', email: 'person@example.test', usageUrl: 'https://malicious.example/usage' }
  const app = await mountStocks({ state })
  try {
    assert.match(document.querySelector('.gpt-account').textContent, /My account/)
    assert.match(document.querySelector('.gpt-account').textContent, /person@example.test/)
    assert.match(document.querySelector('.plan-badge').textContent, /使用 ChatGPT 套餐/)
    assert.match(document.querySelector('.plan-consumption').textContent, /手动生成报告会使用当前 ChatGPT 套餐额度/)
    const usage = document.querySelector('.plan-usage-link')
    assert.equal(usage.href, 'https://chatgpt.com/settings/usage')
    assert.equal(usage.rel, 'noopener noreferrer')
    await click(document.querySelector('.gpt-switch-account'))
    assert.deepEqual(app.calls.find(call => call.url === '/api/stocks/auth/gpt/start').body, { registrationId: 'new' })
  } finally { app.cleanup() }
})

test('a signed-in ChatGPT account without plan consent can reauthorize but cannot generate a report', async () => {
  const state = seed(); state.status.gpt.planEnabled = false
  const app = await mountStocks({ state })
  try {
    assert.equal(app.vm.canAnalyze, false)
    assert.equal(document.querySelector('.research-settings select').disabled, true)
    assert.equal(document.querySelector('.analysis-bar button').disabled, true)
    await click(document.querySelector('.gpt-reauthorize'))
    assert.deepEqual(app.calls.find(call => call.url === '/api/stocks/auth/gpt/start').body, {})
    assert.match(document.querySelector('.plan-badge').textContent, /套餐尚未授权/)
  } finally { app.cleanup() }
})

test('disconnect waits for token revocation and preserves the backend revocation warning', async () => {
  const warning = '本地连接已移除，但远端撤销未确认，请在 ChatGPT 中检查已授权应用。'
  const app = await mountStocks({ post: async (url, _body, _config, state) => {
    if (url === '/api/stocks/auth/gpt/disconnect') {
      state.status.gpt = { connected: false, planEnabled: false }; state.status.models = []; state.status.canAnalyze = false
      return { data: { connected: false, remoteRevocationConfirmed: false, message: warning } }
    }
  } })
  try {
    await app.vm.disconnect('gpt'); await tick()
    const call = app.calls.find(call => call.url === '/api/stocks/auth/gpt/disconnect')
    assert.equal(call.config.timeout, 70000)
    assert.equal(app.vm.notice, warning)
    assert.equal(app.vm.status.gpt.connected, false)
    assert.equal(app.vm.canAnalyze, false)
    assert.match(document.querySelector('[role=status]').textContent, /远端撤销未确认/)
  } finally { app.cleanup() }
})

test('phone stock center keeps research available while hiding and blocking account management', async () => {
  const app = await mountStocks({ props: { mobile: true } })
  try {
    assert.equal(document.querySelector('.connection-actions'), null)
    assert.equal(document.querySelector('.plan-usage-link'), null)
    assert.equal(document.querySelector('.gpt-account'), null)
    assert.match(document.querySelector('.mobile-stock-help').textContent, /在电脑完成/)
    await app.vm.connect('gpt'); await app.vm.disconnect('ibkr')
    assert.equal(app.calls.some(call => call.method === 'post'), false)
    await click(document.querySelector('.analysis-bar button'))
    assert.equal(app.calls.find(call => call.method === 'post').url, '/api/stocks/reports')
  } finally { app.cleanup() }
})

test('cash and account value render for a cash-only account without double-counting the BASE row', async () => {
  const state = seed()
  state.portfolio.positions = []; state.portfolio.totals = []
  state.portfolio.balances.unshift({ currency: 'BASE', cashBalance: 9999 })
  const app = await mountStocks({ state })
  try {
    assert.equal(document.querySelectorAll('.account-card').length, 4)
    assert.match(document.querySelector('.account-card.primary').textContent, /CAD\s*950/)
    assert.equal(document.querySelectorAll('.account-card strong')[3].textContent, '—')
    assert.equal(document.querySelectorAll('.balance-card').length, 2)
    assert.match(document.querySelector('.balance-card').textContent, /USD\s*0\.00/)
    assert.doesNotMatch(document.querySelector('.cash-balances').textContent, /BASE|9,999/)
    assert.ok(document.querySelector('.stock-history svg'))
    assert.equal(document.querySelector('.analysis-bar button').disabled, true)
    i18n.setLocale('en-US'); await tick()
    assert.match(document.querySelector('.account-overview').textContent, /includes cash/)
    assert.match(document.querySelector('.cash-balances').textContent, /Settled cash/)
  } finally { app.cleanup() }
})

test('history offers all six ranges, requests the chosen held contract, and does not generate AI reports', async () => {
  const app = await mountStocks({ props: { mobile: true } })
  try {
    assert.equal(document.querySelectorAll('.history-ranges button').length, 6)
    assert.equal(document.querySelectorAll('.history-selection option').length, 4)
    assert.ok(app.calls.some(call => call.url === '/api/stocks/history?scope=PORTFOLIO&range=1M'))
    assert.match(document.querySelector('.history-explanation').textContent, /入金、出金/)
    const select = document.querySelector('.history-selection select')
    select.value = '101'; select.dispatchEvent(new dom.window.Event('change', { bubbles: true })); await tick()
    await click(document.querySelectorAll('.history-ranges button')[0])
    assert.ok(app.calls.some(call => call.url === '/api/stocks/history?scope=STOCK&range=2H&contractId=101'))
    assert.equal(app.calls.some(call => call.method !== 'get'), false)
    const slider = document.querySelector('.history-scrubber input')
    slider.value = '0'; slider.dispatchEvent(new dom.window.Event('input', { bubbles: true })); await tick()
    assert.match(document.querySelector('.history-value strong').textContent, /900/)
    assert.match(slider.getAttribute('aria-valuetext'), /900/)
    assert.match(document.querySelector('.history-value time').getAttribute('datetime'), /2026-09-28/)
  } finally { app.cleanup() }
})

test('late history responses cannot replace a newly selected range', async () => {
  let releaseOld
  const app = await mountStocks({ get: async url => {
    if (url.includes('range=2H')) return new Promise(resolve => { releaseOld = resolve })
  } })
  try {
    app.vm.historyRange = '2H'; await tick()
    assert.equal(app.vm.historyLoading, true)
    assert.equal(document.querySelector('.stock-history svg'), null)
    app.vm.historyRange = '1Y'; await tick()
    assert.equal(app.vm.historyLoading, false)
    assert.match(document.querySelector('.history-value strong').textContent, /950/)
    releaseOld({ data: { ...app.state.history, points: [{ time: '2026-09-30T12:00:00Z', value: 99999 }] } }); await tick()
    assert.match(document.querySelector('.history-value strong').textContent, /950/)
    assert.equal(app.vm.historyRange, '1Y')
  } finally { app.cleanup() }
})

test('history failure is isolated from assets and retry recovers without claiming unavailable data', async () => {
  let unavailable = true
  const app = await mountStocks({ get: async url => {
    if (url.includes('/history?') && unavailable) throw { code: 'ECONNABORTED' }
  } })
  try {
    assert.equal(app.vm.error, '')
    assert.match(document.querySelector('.history-error').textContent, /请求超时/)
    assert.ok(document.querySelector('.positions-table'))
    unavailable = false
    await click(document.querySelector('.history-error button'))
    assert.ok(document.querySelector('.stock-history svg'))
    assert.equal(app.calls.some(call => call.method !== 'get'), false)
    app.vm.history = { available: false, points: [], message: '暂无数据' }; await tick()
    assert.equal(document.querySelector('.stock-history svg'), null)
    assert.match(document.querySelector('.history-empty').textContent, /缺少的数据不会补画/)
  } finally { app.cleanup() }
})

test('single observations stay visible as a dot; overall two-hour refresh records assets before loading history', async () => {
  const state = seed()
  state.history = { ...state.history, resolution: 'snapshot', points: [{ time: '2026-09-30T12:00:00Z', value: 950 }] }
  const app = await mountStocks({ state })
  try {
    assert.ok(document.querySelector('.chart-dot'))
    assert.equal(document.querySelector('.chart-line'), null)
    assert.equal(document.querySelector('.history-scrubber'), null)
    assert.match(document.querySelector('.stock-history').textContent, /只有一个真实记录/)
    app.vm.historyRange = '2H'; await tick()
    const before = app.calls.length
    await click(document.querySelector('.history-refresh'))
    const newCalls = app.calls.slice(before)
    assert.equal(newCalls[0].url, '/api/stocks/portfolio/refresh')
    assert.equal(newCalls[1].url, '/api/stocks/history?scope=PORTFOLIO&range=2H&refresh=true')
    assert.equal(app.calls.some(call => call.url === '/api/stocks/reports' && call.method === 'post'), false)
  } finally { app.cleanup() }
})

test('chart uses observed times, breaks lines across missing prices, and safely plots zero, negative and constant values', () => {
  const points = chartHelpers.historyPoints([
    { time: '2026-09-30T12:30:00Z', value: -20 },
    { time: '2026-09-30T12:00:00Z', value: 0 },
    { time: '2026-09-30T12:10:00Z', value: null },
    { time: 'bad-date', value: 45 },
    { time: '2026-09-30T13:00:00Z', value: -10 }
  ])
  assert.equal(points.length, 4)
  assert.equal(points[0].value, 0)
  assert.equal(points[1].value, null)
  const geometry = chartHelpers.historyGeometry(points)
  assert.equal(geometry.dots.length, 3)
  assert.equal(geometry.dots[1].x, 500)
  assert.equal((geometry.path.match(/M/g) || []).length, 2)
  assert.doesNotMatch(geometry.path, /NaN|Infinity/)
  const flat = chartHelpers.historyGeometry(chartHelpers.historyPoints([{ time: '2026-09-30T12:00:00Z', value: 0 }, { time: '2026-09-30T13:00:00Z', value: 0 }]))
  assert.equal(flat.dots[0].y, flat.dots[1].y)
  assert.doesNotMatch(flat.path, /NaN|Infinity/)
})
