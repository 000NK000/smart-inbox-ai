<template>
  <section class="stocks-center" :aria-label="t('股票中心')">
    <header class="stocks-intro">
      <div><span class="eyebrow">PORTFOLIO · WATCHLIST · RESEARCH</span><h2>{{ t('看清持仓，带着问题研究。') }}</h2><p>{{ t('连接 IBKR 查看持仓，管理自选股票，用 GPT 生成中文研究报告。') }}</p></div>
      <button type="button" class="quiet" :disabled="busy || loading" @click="load">{{ loading ? t('正在刷新…') : t('刷新连接与记录') }}</button>
    </header>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <div v-if="loading && !ready" class="empty initial" role="status">{{ t('正在读取股票中心…') }}</div>
    <template v-else>
      <div class="connection-grid">
        <article v-for="provider in providers" :key="provider.id" class="connection-card">
          <div class="connection-heading"><span :class="['provider-icon', provider.id]">{{ provider.glyph }}</span><div><h3>{{ provider.label }}</h3><span :class="['connection-status', { connected: status[provider.id]?.connected }]">{{ status[provider.id]?.connected ? t('已连接') : t('未连接') }}</span></div></div>
          <p>{{ providerMessage(provider) }}</p>
          <div v-if="!mobile && provider.id === 'gpt' && status.gpt?.connected" class="gpt-account">
            <div><span>{{ t('当前 ChatGPT 账号') }}</span><strong>{{ status.gpt.account || status.gpt.email || t('已登录的 ChatGPT 账号') }}</strong><small v-if="status.gpt.account && status.gpt.email && status.gpt.account !== status.gpt.email">{{ status.gpt.email }}</small></div>
            <span :class="['plan-badge', { enabled: status.gpt.planEnabled }]">{{ status.gpt.planEnabled ? t('使用 ChatGPT 套餐') : t('套餐尚未授权') }}</span>
          </div>
          <div v-if="!mobile" class="connection-actions"><button v-if="!status[provider.id]?.connected" type="button" :disabled="busy || loading || !sessionToken || status[provider.id]?.loginSupported === false" @click="connect(provider.id)">{{ t(provider.action) }}</button><template v-else><button v-if="provider.id === 'gpt' && !status.gpt.planEnabled" type="button" class="gpt-reauthorize" :disabled="busy || loading" @click="connect('gpt')">{{ t('授权使用 ChatGPT 套餐') }}</button><button type="button" class="quiet" :disabled="busy || loading" @click="disconnect(provider.id)">{{ t('断开连接') }}</button><button v-if="provider.id === 'gpt'" type="button" class="quiet gpt-switch-account" :disabled="busy || loading" @click="connect('gpt', 'new')">{{ t('使用其他 ChatGPT 账号') }}</button></template><span>{{ t(provider.caption) }}</span></div>
          <p v-if="!mobile && provider.id === 'gpt'" class="plan-consumption">{{ t('手动生成报告会使用当前 ChatGPT 套餐额度。') }} <a href="https://chatgpt.com/settings/usage" target="_blank" rel="noopener noreferrer" class="plan-usage-link">{{ t('查看套餐用量 ↗') }}</a></p>
          <a v-if="authLink?.provider === provider.id" :href="authLink.url" target="_blank" rel="noopener noreferrer" class="auth-link">{{ t('继续在授权页面登录 ↗') }}</a>
        </article>
      </div>
      <div class="research-settings"><label>{{ t('报告模型') }}<select v-model="selectedModel" :disabled="busy || !status.gpt?.connected || !status.gpt?.planEnabled || !models.length"><option value="">{{ t('连接后选择可用模型') }}</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name || model.id }}</option></select></label><p>{{ t('报告保持中文；界面语言不会改写你的备注或报告。') }}</p></div>
      <p v-if="status.modelMessage" class="notice">{{ t(status.modelMessage) }}</p>
      <p v-if="mobile" class="notice mobile-stock-help">{{ t('账号首次授权、重新授权和断开连接请在电脑完成。手机可查看持仓、管理自选并主动生成报告。') }}</p>
      <nav class="stock-tabs" :aria-label="t('股票中心视图')"><button v-for="item in tabs" :key="item.id" type="button" :aria-pressed="tab === item.id" @click="tab = item.id">{{ t(item.label) }}<span>{{ item.id === 'portfolio' ? positions.length : item.id === 'watchlist' ? watchlist.length : reports.length }}</span></button></nav>

      <section v-if="tab === 'portfolio'" class="stock-panel">
        <div class="section-heading"><div><h3>{{ t('IBKR 账户与持仓') }}</h3><p>{{ t('账户概览使用 IBKR 报告币种；现金明细按原币种展示。') }}</p></div><button type="button" class="quiet" :disabled="busy || loading || !status.ibkr?.connected" @click="refreshPortfolio">{{ busy === 'portfolio' ? t('正在刷新…') : t('刷新资产') }}</button></div>
        <div class="snapshot-meta"><span>{{ t('来源') }}: {{ portfolio.source || 'IBKR' }}</span><span>{{ t('快照时间') }}: {{ formatTime(portfolio.asOf) }}</span><span :class="['snapshot-badge', { stale: portfolio.stale || !portfolio.asOf }]">{{ !portfolio.asOf ? t('尚无快照') : portfolio.stale ? t('缓存快照 · 请刷新') : t('最近读取的快照') }}</span></div>
        <p v-if="portfolio.message" class="notice">{{ t(portfolio.message) }}</p>
        <div v-if="portfolio.accountSummary" class="account-overview" :aria-label="t('账户资产概览')">
          <article class="account-card primary"><span>{{ t('账户净值（含现金）') }}</span><strong>{{ money(portfolio.accountSummary.netLiquidation, portfolio.accountSummary.currency) }}</strong><small>{{ t('IBKR 报告的账户总净值') }}</small></article>
          <article class="account-card"><span>{{ t('现金总额') }}</span><strong>{{ money(portfolio.accountSummary.totalCash, portfolio.accountSummary.currency) }}</strong><small>{{ t('含已结算与未结算现金') }}</small></article>
          <article class="account-card"><span>{{ t('可用资金') }}</span><strong>{{ money(portfolio.accountSummary.availableFunds, portfolio.accountSummary.currency) }}</strong><small>{{ t('用于新交易的可用资金') }}</small></article>
          <article class="account-card"><span>{{ t('购买力') }}</span><strong>{{ money(portfolio.accountSummary.buyingPower, portfolio.accountSummary.currency) }}</strong><small>{{ t('可能包含保证金额度，不等于现金') }}</small></article>
        </div>
        <p v-if="portfolio.balanceMessage" class="notice balance-notice">{{ t(portfolio.balanceMessage) }}</p>
        <section v-if="balances.length" class="cash-balances" :aria-label="t('各币种现金')"><div class="balance-heading"><h4>{{ t('各币种现金') }}</h4><span>{{ t('按原币种分别展示，不重复加总账户概览。') }}</span></div><div class="balance-grid"><article v-for="balance in balances" :key="balance.currency" class="balance-card"><span class="balance-currency">{{ balance.currency || t('币种未知') }}</span><strong>{{ money(balance.cashBalance, balance.currency) }}</strong><dl><div><dt>{{ t('已结算现金') }}</dt><dd>{{ money(balance.settledCash, balance.currency) }}</dd></div><div><dt>{{ t('股票市值') }}</dt><dd>{{ money(balance.stockMarketValue, balance.currency) }}</dd></div></dl></article></div></section>
        <div v-if="!positions.length" class="empty"><span class="empty-glyph">▥</span><h4>{{ status.ibkr?.connected ? t('暂无持仓数据') : t('连接 IBKR 后查看持仓') }}</h4><p>{{ t('这里仅展示实际读取的数据，首次连接后请刷新持仓。') }}</p></div>
        <template v-else>
          <div class="currency-grid"><article v-for="total in totals" :key="total.currency" class="currency-card"><span>{{ total.currency || t('币种未知') }} · {{ t('持仓市值') }}</span><strong>{{ money(total.marketValue, total.currency) }}</strong><small>{{ t('未实现盈亏') }} <b :class="pnlClass(total.unrealizedPnl)">{{ signedMoney(total.unrealizedPnl, total.currency) }}</b></small></article></div>
          <div class="table-scroll"><table class="positions-table"><thead><tr><th>{{ t('股票 / 名称') }}</th><th>{{ t('数量') }}</th><th>{{ t('平均成本') }}</th><th>{{ t('价格') }}</th><th>{{ t('市值') }}</th><th>{{ t('未实现盈亏') }}</th><th>{{ t('同币种占比') }}</th><th>{{ t('数据时间 / 来源') }}</th></tr></thead><tbody><tr v-for="(position, index) in positions" :key="position.id || `${position.symbol}-${index}`"><td><strong>{{ position.symbol || position.name || t('未提供名称') }}</strong><small>{{ position.symbol ? (position.name || '—') : '—' }} · {{ position.currency || t('币种未知') }}</small></td><td>{{ number(position.quantity) }}</td><td>{{ money(position.averageCost, position.currency) }}</td><td>{{ money(position.price, position.currency) }}</td><td>{{ money(position.marketValue, position.currency) }}</td><td :class="pnlClass(position.unrealizedPnl)">{{ signedMoney(position.unrealizedPnl, position.currency) }}</td><td><div class="concentration"><span>{{ concentration(position) == null ? '—' : `${concentration(position).toFixed(1)}%` }}</span><i v-if="concentration(position) != null"><b :style="{ width: `${Math.min(100, concentration(position))}%` }"></b></i></div></td><td class="position-source"><span>{{ formatTime(position.asOf || portfolio.asOf) }}</span><small>{{ position.source || portfolio.source || 'IBKR' }} · {{ position.stale || portfolio.stale ? t('缓存快照') : t('快照') }}</small></td></tr></tbody></table></div>
          <p class="fineprint">{{ t('占比按同币种持仓的绝对市值计算；不同币种不合并。缺失价格显示为 —。') }}</p>
        </template>
        <StockHistoryChart v-model:target="historyTarget" v-model:range="historyRange" :history="history" :loading="historyLoading || busy === 'portfolio'" :error="historyError" :connected="!!status.ibkr?.connected" :positions="positions" @retry="refreshHistory" />
        <div class="analysis-bar"><div><h4>{{ t('持仓研究报告') }}</h4><p>{{ t('点击生成会将当前持仓快照发送给 OpenAI，报告会注明快照时间。') }}</p><small>{{ t('将使用的快照') }}: {{ formatTime(portfolio.asOf) }}</small></div><button type="button" :disabled="busy || loading || !canAnalyze || !status.ibkr?.connected || !positions.length" @click="analyze('PORTFOLIO')">{{ busy === 'report' ? t('正在生成报告…') : t('生成中文持仓报告') }}</button></div>
      </section>

      <section v-else-if="tab === 'watchlist'" class="stock-panel">
        <div class="section-heading"><div><h3>{{ t('我的自选股票') }}</h3><p>{{ t('记录代码、交易所和研究问题。自选列表独立保存在本机。') }}</p></div><button type="button" :disabled="busy || loading || !sessionToken" @click="openEditor()">{{ t('添加自选') }}</button></div>
        <form v-if="editor" class="watch-editor" @submit.prevent="saveWatch"><div class="editor-title"><h4>{{ editingId ? t('编辑自选股票') : t('添加自选股票') }}</h4><button type="button" class="quiet" :disabled="!!busy" @click="editor = false">{{ t('取消') }}</button></div><div class="editor-fields"><label>{{ t('股票代码') }}<input v-model.trim="draft.symbol" name="symbol" maxlength="32" placeholder="AAPL" required :disabled="!!busy" /></label><label>{{ t('交易所') }}<input v-model.trim="draft.exchange" name="exchange" maxlength="40" placeholder="NASDAQ" :disabled="!!busy" /></label><label>{{ t('名称（可选）') }}<input v-model.trim="draft.name" name="name" maxlength="160" :disabled="!!busy" /></label></div><label>{{ t('研究问题与备注') }}<textarea v-model.trim="draft.notes" name="notes" maxlength="4000" rows="3" :placeholder="t('例如：收入增长来源、估值假设和下一次财报需要观察什么？')" :disabled="!!busy"></textarea></label><button type="submit" :disabled="busy || !draft.symbol.trim()">{{ busy === 'watch' ? t('正在保存…') : t('保存自选') }}</button></form>
        <div v-if="!watchlist.length && !editor" class="empty"><span class="empty-glyph">＋</span><h4>{{ t('从你关心的一家公司开始') }}</h4><p>{{ t('添加股票和问题，再主动生成研究报告。') }}</p></div>
        <div class="watch-grid"><article v-for="item in watchlist" :key="item.id" class="watch-card"><header><div><strong>{{ item.symbol }}</strong><span>{{ item.exchange || t('未指定交易所') }}</span></div><div class="watch-actions"><button type="button" class="quiet" :disabled="!!busy" :aria-label="t('编辑 {symbol}', { symbol: item.symbol })" @click="openEditor(item)">{{ t('编辑') }}</button><button type="button" class="quiet danger" :disabled="!!busy" :aria-label="t('删除 {symbol}', { symbol: item.symbol })" @click="deletingId = item.id">{{ t('删除') }}</button></div></header><h4>{{ item.name || item.symbol }}</h4><p class="watch-notes">{{ item.notes || t('还没有研究备注。') }}</p><div v-if="deletingId === item.id" class="delete-confirm"><p>{{ t('从自选列表移除 {symbol}？', { symbol: item.symbol }) }}</p><button type="button" class="danger" :disabled="!!busy" @click="deleteWatch(item)">{{ t('确认移除') }}</button><button type="button" class="quiet" :disabled="!!busy" @click="deletingId = null">{{ t('取消') }}</button></div><button v-else type="button" class="analyze-watch" :disabled="busy || loading || !canAnalyze" @click="analyze('WATCHLIST', item.id)">{{ busy === 'report' ? t('正在生成报告…') : t('生成中文研究报告') }}</button></article></div>
        <p class="disclosure">{{ t('点击生成会将所选股票的代码、交易所与可用的 IBKR 行情快照发送给 OpenAI。报告标注生成时间；没有行情来源时不会声称使用实时价格。') }}</p>
      </section>

      <section v-else class="stock-panel">
        <div class="section-heading"><div><h3>{{ t('研究报告') }}</h3><p>{{ t('每次主动生成的报告都保留模型与时间，方便回看判断依据。') }}</p></div></div>
        <div v-if="!reports.length" class="empty"><span class="empty-glyph">≡</span><h4>{{ t('还没有研究报告') }}</h4><p>{{ t('在持仓或自选页选择模型并生成第一份中文报告。') }}</p></div>
        <div v-else class="reports-layout"><div class="report-list"><button v-for="report in sortedReports" :key="report.id" type="button" :class="{ selected: selectedReport?.id === report.id }" @click="selectedReportId = report.id"><span>{{ report.scope === 'PORTFOLIO' ? t('持仓报告') : t('自选研究') }}</span><strong>{{ report.title }}</strong><small>{{ formatTime(report.createdAt) }}</small></button></div><article v-if="selectedReport" class="report-detail"><span class="eyebrow">RESEARCH NOTE · 中文</span><h3>{{ selectedReport.title }}</h3><div class="report-meta"><span>{{ formatTime(selectedReport.createdAt) }}</span><span>{{ selectedReport.model }}</span><button type="button" class="quiet danger" :disabled="!!busy" @click="deletingReportId = selectedReport.id">{{ t('删除报告') }}</button></div><div v-if="deletingReportId === selectedReport.id" class="delete-confirm"><p>{{ t('永久删除这份本地报告？') }}</p><button type="button" class="danger" :disabled="!!busy" @click="deleteReport(selectedReport)">{{ t('确认删除') }}</button><button type="button" class="quiet" :disabled="!!busy" @click="deletingReportId = null">{{ t('取消') }}</button></div><div class="report-body">{{ selectedReport.body }}</div></article></div>
      </section>
      <p class="stocks-footer">{{ t('仅用于查看与研究。股票中心不提供下单功能。') }}</p>
    </template>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import axios from 'axios'
import { useI18n } from '../i18n/index.js'
import StockHistoryChart from './StockHistoryChart.vue'

const { t, dateLocale } = useI18n()
const props = defineProps({ mobile: { type: Boolean, default: false } })
const providers = [
  { id: 'ibkr', label: 'Interactive Brokers', glyph: 'IB', description: '通过官方连接读取持仓与行情快照。', action: '连接 IBKR', caption: '只读持仓与行情' },
  { id: 'gpt', label: 'ChatGPT · OpenAI', glyph: '◎', description: '登录后选择账号支持的模型，手动生成中文报告。', action: '使用 ChatGPT 账号登录 / Continue with ChatGPT', caption: '仅在点击生成时调用' }
]
const tabs = [{ id: 'portfolio', label: '我的持仓' }, { id: 'watchlist', label: '自选股票' }, { id: 'reports', label: '研究报告' }]
const tab = ref('portfolio'), loading = ref(false), ready = ref(false), busy = ref(''), error = ref(''), notice = ref('')
const sessionToken = ref(''), status = ref({ ibkr: { connected: false }, gpt: { connected: false }, models: [] })
const portfolio = ref({ positions: [], totals: [], stale: true }), watchlist = ref([]), reports = ref([])
const history = ref(null), historyTarget = ref('PORTFOLIO'), historyRange = ref('1M'), historyLoading = ref(false), historyError = ref('')
let historyRequest = 0
const selectedModel = ref(''), selectedReportId = ref(null), authLink = ref(null)
const editor = ref(false), editingId = ref(null), deletingId = ref(null), deletingReportId = ref(null)
const draft = reactive({ symbol: '', exchange: '', name: '', notes: '', version: null })
const controller = new AbortController()
let sessionPromise, sessionIssuedAt = 0
const models = computed(() => Array.isArray(status.value.models) ? status.value.models : [])
const canAnalyze = computed(() => !!status.value.canAnalyze && !!status.value.gpt?.connected && status.value.gpt?.planEnabled === true && models.value.some(model => model.id === selectedModel.value))
const positions = computed(() => Array.isArray(portfolio.value.positions) ? portfolio.value.positions : [])
const totals = computed(() => Array.isArray(portfolio.value.totals) ? portfolio.value.totals : [])
const balances = computed(() => Array.isArray(portfolio.value.balances) ? portfolio.value.balances.filter(item => String(item.currency || '').toUpperCase() !== 'BASE') : [])
const sortedReports = computed(() => [...reports.value].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))))
const selectedReport = computed(() => reports.value.find(item => item.id === selectedReportId.value) || sortedReports.value[0])
const currencyExposure = computed(() => {
  const values = {}
  for (const item of positions.value) {
    const group = values[item.currency] ||= { total: 0, complete: true }
    if (!finite(item.marketValue)) group.complete = false
    else group.total += Math.abs(Number(item.marketValue))
  }
  return values
})
function finite(value) { return value != null && value !== '' && Number.isFinite(Number(value)) }
function number(value) { return finite(value) ? new Intl.NumberFormat(dateLocale.value, { maximumFractionDigits: 4 }).format(Number(value)) : '—' }
function money(value, currency) {
  if (!finite(value)) return '—'
  if (!currency) return `${t('币种未知')} ${number(value)}`
  try { return new Intl.NumberFormat(dateLocale.value, { style: 'currency', currency: currency || 'XXX', currencyDisplay: 'code', maximumFractionDigits: 2 }).format(Number(value)) }
  catch { return `${currency || ''} ${number(value)}`.trim() }
}
function signedMoney(value, currency) { return `${finite(value) && Number(value) > 0 ? '+' : ''}${money(value, currency)}` }
function pnlClass(value) { return !finite(value) || Number(value) === 0 ? '' : Number(value) > 0 ? 'positive' : 'negative' }
function concentration(item) {
  if (!item.currency) return null
  const exposure = currencyExposure.value[item.currency]
  return exposure?.complete && exposure.total > 0 && finite(item.marketValue) ? Math.abs(Number(item.marketValue)) / exposure.total * 100 : null
}
function formatTime(value) {
  if (!value) return t('尚无记录')
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? t('时间不可用') : new Intl.DateTimeFormat(dateLocale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
function config(timeout = 20000) { return { timeout, signal: controller.signal, headers: { 'X-Stock-Session': sessionToken.value } } }
function sessionExpired(reason) { return reason?.response?.status === 403 && reason?.response?.data?.message === '股票页面连接已过期，请重新打开此页面' }
async function renewSession() {
  if (!sessionPromise) sessionPromise = axios.get('/api/stocks/session', { timeout: 15000, signal: controller.signal }).then(({ data }) => {
    if (!data?.token) throw new Error('Missing stock session')
    sessionToken.value = data.token; sessionIssuedAt = Date.now()
  }).finally(() => { sessionPromise = null })
  return sessionPromise
}
async function stockGet(url, timeout = 20000) {
  const token = sessionToken.value
  try { return await axios.get(url, config(timeout)) }
  catch (reason) {
    if (!sessionExpired(reason)) throw reason
    if (sessionToken.value === token) await renewSession()
    return axios.get(url, config(timeout))
  }
}
async function loadHistory(refresh = false) {
  const request = ++historyRequest
  historyError.value = ''; history.value = null
  if (!ready.value || tab.value !== 'portfolio' || !status.value.ibkr?.connected || !sessionToken.value) { historyLoading.value = false; return }
  historyLoading.value = true
  const query = new URLSearchParams({ scope: historyTarget.value === 'PORTFOLIO' ? 'PORTFOLIO' : 'STOCK', range: historyRange.value })
  if (historyTarget.value !== 'PORTFOLIO') query.set('contractId', historyTarget.value)
  if (refresh) query.set('refresh', 'true')
  try {
    const { data } = await stockGet(`/api/stocks/history?${query}`, 60000)
    if (request === historyRequest && !controller.signal.aborted) history.value = data || null
  } catch (reason) {
    if (request === historyRequest && !controller.signal.aborted) historyError.value = errorMessage(reason)
  } finally { if (request === historyRequest) historyLoading.value = false }
}
watch([tab, historyTarget, historyRange, ready, () => status.value.ibkr?.connected], () => { loadHistory() })
watch(positions, value => { if (historyTarget.value !== 'PORTFOLIO' && !value.some(item => String(item.contractId) === historyTarget.value)) historyTarget.value = 'PORTFOLIO' })
function errorMessage(reason) {
  const body = reason?.response?.data
  if (typeof body?.message === 'string') return t(body.message)
  if (typeof body?.detail === 'string') return t(body.detail)
  if (reason?.code === 'ECONNABORTED') return t('请求超时，请稍后重试。')
  return t('暂时无法读取股票中心，请确认服务启动后重试。')
}
function providerMessage(provider) {
  const message = status.value[provider.id]?.message || provider.description
  const branded = { 'Using ChatGPT plan': '使用 ChatGPT 套餐', 'Continue with ChatGPT': '使用 ChatGPT 账号登录' }
  return t(provider.id === 'gpt' ? (branded[message] || message) : message)
}
async function fetchStatus() {
  const response = await stockGet('/api/stocks/status')
  status.value = response.data || {}
  if (!models.value.some(model => model.id === selectedModel.value)) selectedModel.value = models.value[0]?.id || ''
}
async function load() {
  if (loading.value || busy.value) return
  loading.value = true; error.value = ''
  try {
    if (!sessionToken.value || Date.now() - sessionIssuedAt > 25 * 60000) await renewSession()
    const results = await Promise.allSettled([
      fetchStatus(),
      stockGet('/api/stocks/watchlist').then(({ data }) => { watchlist.value = Array.isArray(data) ? data : [] }),
      stockGet('/api/stocks/reports').then(({ data }) => { reports.value = Array.isArray(data) ? data : [] })
    ])
    const failed = results.find(result => result.status === 'rejected')
    if (failed) error.value = errorMessage(failed.reason)
    if (results[0].status === 'fulfilled' && status.value.ibkr?.connected) {
      portfolio.value = (await stockGet('/api/stocks/portfolio', 60000)).data || { positions: [], totals: [], stale: true }
    } else if (results[0].status === 'fulfilled') portfolio.value = { positions: [], totals: [], stale: true }
    const wasReady = ready.value
    ready.value = true
    if (wasReady) loadHistory(true)
  } catch (reason) { portfolio.value = { ...portfolio.value, stale: true }; error.value = errorMessage(reason) }
  finally { loading.value = false }
}
async function action(key, work) {
  if (busy.value || loading.value || !sessionToken.value) return
  busy.value = key; error.value = ''; notice.value = ''
  try {
    if (Date.now() - sessionIssuedAt > 25 * 60000) await renewSession()
    await work()
  } catch (reason) {
    if (key === 'portfolio') portfolio.value = { ...portfolio.value, stale: true }
    if (sessionExpired(reason)) {
      try { await renewSession(); error.value = t('连接会话已更新，请再次执行刚才的操作。') }
      catch (renewalError) { sessionToken.value = ''; error.value = errorMessage(renewalError) }
    } else error.value = errorMessage(reason)
  }
  finally { busy.value = '' }
}
function safeAuthUrl(raw, provider) {
  try {
    const url = new URL(raw)
    const allowed = provider === 'gpt' ? ['auth.openai.com', 'auth0.openai.com', 'chatgpt.com'] : ['api.ibkr.com', 'interactivebrokers.com', 'interactivebrokers.co.uk', 'interactivebrokers.ca', 'interactivebrokers.com.hk', 'interactivebrokers.com.sg', 'interactivebrokers.com.au', 'interactivebrokers.ie']
    return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443') && allowed.some(host => url.hostname === host || (provider === 'ibkr' && url.hostname.endsWith(`.${host}`))) ? url.href : null
  } catch { return null }
}
async function connect(provider, registrationId) {
  if (props.mobile) return
  await action(`auth-${provider}`, async () => {
    authLink.value = null
    const body = provider === 'gpt' && registrationId ? { registrationId } : {}
    const { data } = await axios.post(`/api/stocks/auth/${provider}/start`, body, config(30000))
    if (data?.url) {
      const url = safeAuthUrl(data.url, provider)
      if (!url) { error.value = t('授权地址未通过验证，请检查服务端连接配置。'); return }
      authLink.value = { provider, url }
      window.open(url, '_blank', 'noopener,noreferrer')
      notice.value = t('请在授权页面完成登录，然后点击“刷新连接与记录”。')
    } else notice.value = data?.message || t('登录已启动，请按连接说明完成，然后刷新连接状态。')
    await fetchStatus()
  })
}
async function disconnect(provider) {
  if (props.mobile) return
  await action(`auth-${provider}`, async () => {
    const { data } = await axios.post(`/api/stocks/auth/${provider}/disconnect`, {}, config(70000))
    if (data?.message) notice.value = t(data.message)
    authLink.value = null
    status.value = { ...status.value, [provider]: { connected: false }, ...(provider === 'gpt' ? { models: [], canAnalyze: false } : {}) }
    if (provider === 'ibkr') portfolio.value = { positions: [], totals: [], stale: true }
    await fetchStatus()
  })
}
async function refreshPortfolio() {
  await action('portfolio', async () => { portfolio.value = (await axios.post('/api/stocks/portfolio/refresh', {}, config(60000))).data || { positions: [], totals: [], stale: true }; await loadHistory(true) })
}
function refreshHistory() { return historyTarget.value === 'PORTFOLIO' && historyRange.value === '2H' ? refreshPortfolio() : loadHistory(true) }
function openEditor(item = null) {
  editingId.value = item?.id || null
  Object.assign(draft, { symbol: item?.symbol || '', exchange: item?.exchange || '', name: item?.name || '', notes: item?.notes || '', version: item?.version ?? null })
  deletingId.value = null; editor.value = true
}
async function saveWatch() {
  if (!draft.symbol.trim()) return
  await action('watch', async () => {
    const body = { symbol: draft.symbol.trim().toUpperCase(), exchange: draft.exchange.trim().toUpperCase(), name: draft.name.trim(), notes: draft.notes.trim() }
    const { data } = editingId.value ? await axios.put(`/api/stocks/watchlist/${encodeURIComponent(editingId.value)}`, { ...body, version: draft.version }, config()) : await axios.post('/api/stocks/watchlist', body, config())
    const existing = watchlist.value.findIndex(item => item.id === data.id)
    if (existing >= 0) watchlist.value.splice(existing, 1, data)
    else watchlist.value.push(data)
    editor.value = false; notice.value = t('自选股票已保存。')
  })
}
async function deleteWatch(item) {
  await action('watch', async () => {
    await axios.delete(`/api/stocks/watchlist/${encodeURIComponent(item.id)}`, { ...config(), params: { version: item.version } })
    watchlist.value = watchlist.value.filter(entry => entry.id !== item.id)
    if (editingId.value === item.id) editor.value = false
    deletingId.value = null
  })
}
async function analyze(scope, watchId) {
  if (!canAnalyze.value || (scope === 'PORTFOLIO' && (!status.value.ibkr?.connected || !positions.value.length))) return
  await action('report', async () => {
    const body = { scope, model: selectedModel.value, ...(watchId ? { watchId } : {}) }
    const { data } = await axios.post('/api/stocks/reports', body, config(180000))
    reports.value = [data, ...reports.value.filter(item => item.id !== data.id)]
    selectedReportId.value = data.id; tab.value = 'reports'
  })
}
async function deleteReport(report) {
  await action('report-delete', async () => {
    await axios.delete(`/api/stocks/reports/${encodeURIComponent(report.id)}`, config())
    reports.value = reports.value.filter(item => item.id !== report.id)
    deletingReportId.value = null
  })
}
function refreshMobile() { if (props.mobile && !document.hidden) load() }
onMounted(() => { load(); window.addEventListener('smart-inbox:mobile-resume', refreshMobile) })
onBeforeUnmount(() => { ++historyRequest; controller.abort(); sessionToken.value = ''; authLink.value = null; window.removeEventListener('smart-inbox:mobile-resume', refreshMobile) })
</script>

<style scoped>
.account-overview{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:24px 0}.account-card{padding:19px;border:1px solid #dce8e2;border-radius:16px;background:#f7faf8;min-width:0}.account-card.primary{background:linear-gradient(140deg,#285d50,#347867);border-color:#2c6757;color:#fff}.account-card>span{display:block;font-size:11px;font-weight:700;color:#719081}.account-card>strong{display:block;font-size:clamp(18px,1.75vw,26px);letter-spacing:-.04em;margin:13px 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.account-card>small{font-size:10px;line-height:1.6;color:#8a9f92}.account-card.primary>span{color:#c9e3d5}.account-card.primary>small{color:#b0d0c0}.balance-heading{display:flex;justify-content:space-between;gap:12px;align-items:baseline;margin:25px 0 13px}.balance-heading h4{font-size:15px;margin:0}.balance-heading>span{font-size:11px;color:#869b90}.balance-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:13px}.balance-card{padding:18px 20px;background:#fff;border:1px solid #e0e9e4;border-radius:15px}.balance-currency{font-size:11px;font-weight:800;letter-spacing:.05em;color:#719383}.balance-card>strong{display:block;margin:10px 0 16px;font-size:23px;color:#335f4d;font-variant-numeric:tabular-nums}.balance-card dl{margin:0;border-top:1px solid #edf1ee;padding-top:11px}.balance-card dl>div{display:flex;justify-content:space-between;gap:10px;margin:7px 0;font-size:11px}.balance-card dt{color:#8b9e93}.balance-card dd{margin:0;color:#5b7968;font-variant-numeric:tabular-nums}@media(max-width:1050px){.account-overview{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.account-overview{gap:9px}.account-card{padding:15px 13px}.account-card>strong{font-size:19px}.balance-heading{align-items:flex-start;flex-direction:column;gap:7px}.balance-grid{grid-template-columns:1fr}}@media(max-width:360px){.account-overview{grid-template-columns:1fr}}
.gpt-account{display:flex;justify-content:space-between;align-items:flex-start;gap:14px;flex-wrap:wrap;margin:0 0 18px;padding:15px;background:#f0f6f3;border:1px solid #dfebe4;border-radius:13px}.gpt-account>div{display:grid;gap:5px;min-width:0}.gpt-account>div>span,.gpt-account small{font-size:11px;color:#7f9488;overflow-wrap:anywhere}.gpt-account strong{font-size:14px;color:#385e4e;overflow-wrap:anywhere}.plan-badge{padding:5px 8px;border-radius:7px;font-size:10px;background:#fff0d9;color:#967440;white-space:nowrap}.plan-badge.enabled{background:#dceddf;color:#407154}.connection-card>.plan-consumption{flex:0;font-size:11px;color:#829589;margin:17px 0 0;line-height:1.7}.plan-usage-link{color:#286e68;white-space:nowrap}.gpt-switch-account{font-size:12px!important}
.stocks-center{max-width:1440px;margin:auto;color:#20364a}.stocks-center button,.stocks-center input,.stocks-center textarea,.stocks-center select{font:inherit}.stocks-center button{cursor:pointer;border:1px solid #286e68;border-radius:12px;padding:11px 16px;font-weight:700;color:#fff;background:#286e68;transition:background .18s}.stocks-center button:hover:not(:disabled){background:#19564f}.stocks-center button:disabled{opacity:.48;cursor:not-allowed}.stocks-center button:focus-visible,.stocks-center a:focus-visible{outline:3px solid #75b4de;outline-offset:3px}.stocks-center button.quiet{border-color:#dce5ea;background:#fff;color:#435f70}.stocks-center button.quiet:hover:not(:disabled){background:#edf5f4}.stocks-center .danger{color:#a54642}.stocks-intro,.section-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:22px}.stocks-intro h2{font-size:clamp(26px,3vw,36px);line-height:1.25;letter-spacing:-.04em;margin:9px 0 12px}.stocks-intro p,.section-heading p{margin:0;color:#687f90;line-height:1.6;font-size:14px}.eyebrow{font-size:10px;font-weight:800;letter-spacing:.16em;color:#728b99}.stocks-intro>button,.section-heading>button{flex-shrink:0}.notice{padding:14px 18px;background:#edf5f4;border:1px solid #d1e3df;border-radius:14px;font-size:14px;line-height:1.65;overflow-wrap:anywhere}.notice.error{background:#fff4ed;border-color:#f1dacb;color:#935a32}.connection-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.connection-card{border:1px solid #e0e8ee;border-radius:23px;background:linear-gradient(120deg,#fff,#f8fbfa);padding:24px;display:flex;flex-direction:column}.connection-heading{display:flex;gap:13px;align-items:center}.provider-icon{width:46px;height:46px;border-radius:14px;display:grid;place-items:center;font-size:21px;font-weight:800}.provider-icon.ibkr{color:#a8433c;background:#fbece9}.provider-icon.gpt{color:#286e68;background:#e0efea;font-size:32px}.connection-heading h3{font-size:17px;margin:0 0 5px}.connection-status{font-size:12px;color:#82909e}.connection-status:before{content:'●';font-size:9px;margin-right:6px}.connection-status.connected{color:#28755a}.connection-card>p{font-size:13px;line-height:1.7;color:#687f90;flex:1;overflow-wrap:anywhere}.connection-actions{display:flex;align-items:center;gap:13px;flex-wrap:wrap}.connection-actions>span{font-size:11px;color:#82909e}.auth-link{margin-top:16px;color:#216b65;font-size:13px}.research-settings{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:20px 2px}.research-settings label{display:flex;align-items:center;gap:13px;font-weight:700;font-size:13px}.research-settings select{width:260px;max-width:100%}.research-settings p{font-size:12px;color:#82909e;line-height:1.5;text-align:right}.stocks-center input,.stocks-center textarea,.stocks-center select{box-sizing:border-box;background:#fff;color:#294355;border:1px solid #d6e1e8;border-radius:10px;padding:11px 12px;min-width:0}.stocks-center input:focus,.stocks-center textarea:focus,.stocks-center select:focus{outline:2px solid #95c1b8;outline-offset:1px}.stock-tabs{display:flex;gap:5px;border-bottom:1px solid #dce5ea;margin-bottom:24px;padding-bottom:8px;overflow:auto}.stock-tabs button{display:flex;gap:9px;align-items:center;border:0;background:transparent;color:#6b8293;font-size:14px;padding:12px 19px;white-space:nowrap}.stock-tabs button[aria-pressed=true]{background:#e0efeb;color:#225f57}.stock-tabs button:hover:not(:disabled){background:#eaf3f1}.stock-tabs button span{font-size:11px;font-variant-numeric:tabular-nums;background:#ffffff91;padding:2px 7px;border-radius:6px}.stock-panel{background:#ffffffc9;border:1px solid #e0e8ed;border-radius:24px;padding:26px}.section-heading{margin-bottom:17px}.section-heading h3{font-size:21px;margin:0 0 7px}.snapshot-meta{display:flex;gap:9px 16px;align-items:center;flex-wrap:wrap;font-size:12px;color:#768b9a}.snapshot-badge{background:#ebf4f0;color:#46745f;padding:5px 9px;border-radius:7px}.snapshot-badge.stale{background:#fff2df;color:#9b733e}.empty{padding:55px 18px;text-align:center;color:#7c909e}.empty.initial{padding:50px}.empty-glyph{font-size:38px;color:#85a89c;font-weight:400}.empty h4{font-size:18px;color:#486576;margin:15px 0 9px}.empty p{font-size:13px;line-height:1.7;margin:0}.currency-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:15px;margin:23px 0}.currency-card{border:1px solid #e1eae7;border-radius:17px;background:linear-gradient(125deg,#f1f7f5,#fafcfb);padding:21px}.currency-card>span{display:block;color:#708a80;font-size:12px;font-weight:700}.currency-card>strong{display:block;font-size:clamp(23px,2.3vw,31px);margin:12px 0;font-variant-numeric:tabular-nums;letter-spacing:-.03em}.currency-card small{font-size:12px;color:#758f82}.currency-card small b{margin-left:9px;font-weight:700}.table-scroll{overflow-x:auto;border:1px solid #e6ecef;border-radius:13px;margin-top:20px}.positions-table{width:100%;border-collapse:collapse;font-size:13px;white-space:nowrap}.positions-table th{text-align:right;background:#f6f9fa;color:#81919d;font-size:11px;font-weight:600;padding:14px 16px}.positions-table td{text-align:right;padding:18px 16px;border-top:1px solid #edf1f3;font-variant-numeric:tabular-nums}.positions-table th:first-child,.positions-table td:first-child{text-align:left}.positions-table td:first-child strong{font-size:14px}.positions-table small{display:block;color:#899aa5;font-size:11px;margin-top:6px}.positive{color:#247a64!important}.negative{color:#b85650!important}.concentration{min-width:65px}.concentration>i{display:block;height:4px;background:#e8efed;border-radius:3px;margin-top:8px}.concentration b{display:block;height:4px;background:#8bb4a7;border-radius:3px}.position-source{font-size:11px}.fineprint{font-size:11px;color:#8c9da9;line-height:1.6}.analysis-bar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px;margin-top:25px;border:1px solid #dbe9e3;border-radius:17px;background:#f2f8f5}.analysis-bar h4{margin:0 0 8px;font-size:16px}.analysis-bar p{font-size:13px;margin:0 0 6px;color:#6b8479;line-height:1.7}.analysis-bar small{font-size:11px;color:#819589}.analysis-bar button{flex-shrink:0}.watch-editor{margin:20px 0;padding:22px;background:#f4f8f9;border:1px solid #dfe8ec;border-radius:18px}.editor-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:15px}.editor-title h4{margin:0}.editor-fields{display:grid;grid-template-columns:1fr 1fr 1.3fr;gap:15px;margin-bottom:15px}.watch-editor label{display:grid;gap:8px;font-size:12px;color:#69808f;font-weight:700}.watch-editor textarea{resize:vertical}.watch-editor>button{margin-top:16px}.watch-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:17px;margin-top:23px}.watch-card{display:flex;flex-direction:column;padding:22px;border:1px solid #e0e8ed;border-radius:18px;background:#fff}.watch-card header{display:flex;align-items:center;justify-content:space-between;gap:10px}.watch-card header strong{font-size:19px;display:block;letter-spacing:.02em}.watch-card header span{font-size:10px;color:#7c9490;display:block;margin-top:6px}.watch-actions{display:flex;gap:5px}.watch-actions button{padding:6px 8px;font-size:11px}.watch-card h4{font-size:13px;margin:17px 0 7px;color:#6e8292}.watch-notes{font-size:13px;line-height:1.8;margin:0 0 20px;white-space:pre-wrap;overflow-wrap:anywhere;flex:1}.analyze-watch{width:100%;font-size:13px!important;background:#edf5f2!important;color:#286e68!important;border-color:#d8e8e1!important}.delete-confirm{font-size:13px;color:#a54642}.delete-confirm button{margin:0 8px 5px 0;padding:8px 10px;background:#fff;border-color:#e8d7d5}.disclosure{font-size:12px;color:#7d909d;line-height:1.8;margin:24px 0 0}.reports-layout{display:grid;grid-template-columns:minmax(200px,280px) minmax(0,1fr);gap:25px;margin-top:25px}.report-list{display:flex;flex-direction:column;gap:9px}.report-list button{display:grid;gap:9px;text-align:left;padding:17px;background:#f8fafb;border-color:#e4ebee;color:#456271}.report-list button:hover:not(:disabled),.report-list button.selected{background:#eaf4ef;border-color:#aacabc}.report-list button>span{font-size:10px;color:#759182}.report-list button strong{font-size:13px;line-height:1.6}.report-list button small{font-size:10px;color:#8a9ca4}.report-detail{padding:26px;border:1px solid #e3ebe7;border-radius:18px;background:#fff;min-width:0}.report-detail h3{font-size:23px;line-height:1.45;margin:14px 0}.report-meta{display:flex;gap:10px;flex-wrap:wrap;color:#81938e;font-size:11px;padding-bottom:20px;border-bottom:1px solid #e6eeea}.report-body{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.95;margin-top:23px;color:#3b555f}.stocks-footer{font-size:11px;text-align:center;color:#93a1ac;margin:21px 0 0}@media(max-width:850px){.stocks-intro{align-items:flex-start}.connection-card{padding:20px}.research-settings{align-items:flex-start;flex-direction:column;gap:3px}.research-settings p{text-align:left}.stock-panel{padding:20px}.analysis-bar{align-items:flex-start;flex-direction:column}.reports-layout{grid-template-columns:1fr}.report-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));max-height:280px;overflow:auto}}@media(max-width:620px){.stocks-intro{flex-direction:column}.stocks-intro h2{font-size:27px}.connection-grid{grid-template-columns:1fr;gap:13px}.research-settings label{align-items:flex-start;flex-direction:column;gap:8px;width:100%}.research-settings select{width:100%}.stock-panel{padding:17px;border-radius:19px}.section-heading{align-items:flex-start}.section-heading h3{font-size:18px}.section-heading p{font-size:12px}.section-heading>button{font-size:12px;padding:9px 10px}.stock-tabs{gap:0}.stock-tabs button{padding:11px 12px;font-size:13px}.stock-tabs button span{padding:2px 5px}.editor-fields{grid-template-columns:1fr}.watch-editor{padding:16px}.watch-grid{grid-template-columns:1fr}.currency-grid{grid-template-columns:1fr}.snapshot-meta{font-size:11px}.analysis-bar{padding:18px}.analysis-bar button{width:100%}.report-list{grid-template-columns:1fr}.report-detail{padding:18px}.report-detail h3{font-size:20px}}
</style>
