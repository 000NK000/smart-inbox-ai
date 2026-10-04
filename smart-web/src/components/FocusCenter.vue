<template>
  <section ref="focusRoot" class="focus-center" :data-focus-state="running ? overview.active.category : undefined" :aria-label="t('专注与每周复盘')">
    <header class="intro">
      <div><span class="eyebrow">FOCUS · DAILY TIME</span><h2>{{ t('把时间留给重要的事。') }}</h2><p>{{ t('打开软件，自动记录无效时间。准备好专注时，按 Enter 切换为有效时间。') }}</p></div>
      <button type="button" class="subtle-button refresh-button" :disabled="busy || refreshing" @click="refresh()">{{ t('刷新记录') }}</button>
    </header>
    <p v-if="error" class="notice" role="alert">{{ t(error) }}</p>
    <p v-if="!overview" class="loading" role="status">{{ t('正在读取计时记录…') }}</p>
    <template v-else>
      <div class="today-heading"><div><span class="eyebrow">TODAY</span><h3>{{ t('今天的时间') }}</h3></div><span>{{ overview.today }} · {{ zone }}</span></div>
      <div class="category-grid">
        <article v-for="item in categories" :key="item.key" :class="['category-card', item.key.toLowerCase(), { running: running && overview.active?.category === item.key }]">
          <div class="card-top"><div class="glyph" aria-hidden="true">{{ item.icon }}</div><span class="card-status"><i v-if="running && overview.active?.category === item.key" class="live-dot"></i>{{ running && overview.active?.category === item.key ? t('正在计时') : t('今天累计') }}</span></div>
          <h3>{{ t(item.label) }}</h3><p>{{ t(item.description) }}</p>
          <strong class="big-time" :aria-label="`${t(item.label)} ${clockTime(todayMs(item.key))}`">{{ clockTime(todayMs(item.key)) }}</strong>
          <div class="category-footer"><span>{{ t('占今日记录') }}</span><b>{{ percentage(todayMs(item.key), todayTotal) }}</b></div>
        </article>
      </div>
      <div class="switch-panel">
        <div><p class="active-hint" role="status">{{ running ? t('当前正在记录「{category}」', { category: activeLabel }) : t('等待软件恢复计时…') }}</p><span class="shortcut-hint">{{ t('在软件任意页面按') }} <kbd>Enter</kbd> {{ t('切换，输入文字或操作控件时除外') }}</span></div>
        <button type="button" class="switch-button" aria-keyshortcuts="Enter" :disabled="busy || !running" @click="switchCategory">{{ busy ? t('正在保存…') : t('切换为{category}', { category: nextLabel }) }} <span aria-hidden="true">⇄</span></button>
      </div>
      <p class="timing-note">{{ t('切换后自动保存；离开此页面继续计时。待机或完全退出软件后停止计时。') }}</p>

      <section class="history-section">
        <div class="section-head"><div><span class="eyebrow">YOUR TIME, OVER TIME</span><h3>{{ t('每日时间记录') }}</h3></div><div class="range-picker" :aria-label="t('记录范围')"><button v-for="item in ranges" :key="item.key" type="button" :class="{ selected: range === item.key }" :aria-pressed="range === item.key" @click="chooseRange(item.key)">{{ t(item.label) }}</button></div></div>
        <p class="retention-note">{{ t('记录保存在本机数据库，长期保留；这里可查看最近三个月。') }}</p>
        <div class="history-summary"><div><span>{{ t('有效时间') }}</span><strong>{{ duration(historyTotals.EFFECTIVE) }}</strong></div><div><span>{{ t('无效时间') }}</span><strong>{{ duration(historyTotals.INEFFECTIVE) }}</strong></div><div><span>{{ t('有效时间占比') }}</span><strong>{{ percentage(historyTotals.EFFECTIVE, historyTotals.EFFECTIVE + historyTotals.INEFFECTIVE) }}</strong></div></div>
        <div class="chart-header"><span>{{ range === 'week' ? t('每天的时间分布') : t('每七天汇总，明细按天列出') }}</span><div class="chart-legend"><span><i class="effective-swatch"></i>{{ t('有效时间') }}</span><span><i class="ineffective-swatch"></i>{{ t('无效时间') }}</span></div></div>
        <div class="history-chart" :style="{ '--bar-count': chartDays.length }" :aria-label="t('时间分布图')">
          <div v-for="bar in chartDays" :key="bar.start" class="chart-column" :title="`${dateText(bar.start)}${bar.end !== bar.start ? ' – ' + dateText(bar.end) : ''} · ${t('有效时间')} ${duration(bar.totals.EFFECTIVE)} · ${t('无效时间')} ${duration(bar.totals.INEFFECTIVE)}`">
            <div class="chart-track"><div class="bar-stack" :style="{ height: barHeight(bar) + '%' }"><i class="effective-fill" :style="{ flex: bar.totals.EFFECTIVE }"></i><i class="ineffective-fill" :style="{ flex: bar.totals.INEFFECTIVE }"></i></div></div>
            <span>{{ dateText(bar.start) }}</span>
          </div>
        </div>
        <p v-if="!historyTotals.EFFECTIVE && !historyTotals.INEFFECTIVE" class="empty-history">{{ t('这个时间段还没有记录。开始使用软件后，时间会显示在这里。') }}</p>
        <div class="daily-records"><table><caption class="sr-only">{{ t('每日有效与无效时间') }}</caption><thead><tr><th scope="col">{{ t('日期') }}</th><th scope="col">{{ t('有效时间') }}</th><th scope="col">{{ t('无效时间') }}</th><th scope="col">{{ t('有效占比') }}</th></tr></thead><tbody><tr v-for="day in pageDays" :key="day.date"><th scope="row">{{ dateText(day.date) }} <small>{{ day.date === overview.today ? t('今天') : weekday(day.date) }}</small></th><td>{{ duration(day.totals.EFFECTIVE) }}</td><td>{{ duration(day.totals.INEFFECTIVE) }}</td><td>{{ percentage(day.totals.EFFECTIVE, day.totals.EFFECTIVE + day.totals.INEFFECTIVE) }}</td></tr></tbody></table></div>
        <div class="pagination"><span>{{ t('共 {count} 天 · 第 {page} / {pages} 页', { count: historyDays.length, page: page + 1, pages: pageCount }) }}</span><div><button type="button" class="subtle-button" :disabled="page === 0" @click="page--">{{ t('较新记录') }}</button><button type="button" class="subtle-button" :disabled="page + 1 >= pageCount" @click="page++">{{ t('较早记录') }}</button></div></div>
        <p class="legacy-note">{{ t('以往记录保留：招工、法语、课程合并为有效时间，娱乐合并为无效时间。') }}</p>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { useI18n } from '../i18n/index.js'
import { elapsedSinceSnapshot, nextDayStart, localDay, rangeDays, chartBuckets, clockTime } from '../utils/focusTime.js'
import { createFocusController, focusControllerKey } from '../stores/focusController.js'

const { t, dateLocale } = useI18n()
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Toronto'
const categories = [
  { key: 'EFFECTIVE', label: '有效时间', icon: '◷', description: '专心投入学习、工作和重要事项' },
  { key: 'INEFFECTIVE', label: '无效时间', icon: '☾', description: '休息、娱乐，以及尚未开始专注的时间' }
]
const ranges = [{ key: 'week', label: '最近 7 天' }, { key: 'month', label: '最近 1 个月' }, { key: 'quarter', label: '最近 3 个月' }]
const workspaceController = inject(focusControllerKey, null)
const controller = workspaceController || createFocusController(axios, zone)
const { overview, error, busy, refreshing, snapshotAt, refresh, switchCategory } = controller
const focusRoot = ref(null)
const tick = ref(Date.now()), dayEnd = ref(0), range = ref('week'), page = ref(0)
const pageSize = 7
let ticker, poller
const extraMs = computed(() => elapsedSinceSnapshot(overview.value, snapshotAt.value, tick.value, dayEnd.value))
const serverEstimate = computed(() => (overview.value?.serverNow || 0) + Math.max(0, tick.value - snapshotAt.value))
const running = computed(() => !!overview.value?.active && serverEstimate.value < overview.value.active.leaseUntil)
const activeLabel = computed(() => t(overview.value?.active?.category === 'EFFECTIVE' ? '有效时间' : '无效时间'))
const nextLabel = computed(() => t(overview.value?.active?.category === 'EFFECTIVE' ? '无效时间' : '有效时间'))
function todayMs(key) { return (overview.value?.daily?.[key] || 0) + (overview.value?.active?.category === key ? extraMs.value : 0) }
const todayTotal = computed(() => todayMs('EFFECTIVE') + todayMs('INEFFECTIVE'))
const historyDays = computed(() => rangeDays(overview.value?.historyDays || [], overview.value?.today, range.value).map(day => day.date === overview.value?.today ? { ...day, totals: { EFFECTIVE: todayMs('EFFECTIVE'), INEFFECTIVE: todayMs('INEFFECTIVE') } } : day))
const historyTotals = computed(() => historyDays.value.reduce((sum, day) => ({ EFFECTIVE: sum.EFFECTIVE + (day.totals.EFFECTIVE || 0), INEFFECTIVE: sum.INEFFECTIVE + (day.totals.INEFFECTIVE || 0) }), { EFFECTIVE: 0, INEFFECTIVE: 0 }))
const chartDays = computed(() => chartBuckets(historyDays.value, range.value === 'week' ? 1 : 7))
const chartMax = computed(() => Math.max(60000, ...chartDays.value.map(day => day.totals.EFFECTIVE + day.totals.INEFFECTIVE)))
const pageCount = computed(() => Math.max(1, Math.ceil(historyDays.value.length / pageSize)))
const pageDays = computed(() => [...historyDays.value].reverse().slice(page.value * pageSize, (page.value + 1) * pageSize))
function chooseRange(value) { range.value = value; page.value = 0 }
function percentage(value, total) { return total > 0 ? `${Math.round(value / total * 100)}%` : '—' }
function duration(ms = 0) { const minutes = Math.floor(Math.max(0, ms) / 60000); return t('{v0}小时 {v1}分', { v0: Math.floor(minutes / 60), v1: String(minutes % 60).padStart(2, '0') }) }
function weekday(day) { return new Intl.DateTimeFormat(dateLocale.value, { weekday: 'short' }).format(new Date(`${day}T12:00:00`)) }
function dateText(day) { return new Intl.DateTimeFormat(dateLocale.value, { month: 'numeric', day: 'numeric' }).format(new Date(`${day}T12:00:00`)) }
function barHeight(bar) { return (bar.totals.EFFECTIVE + bar.totals.INEFFECTIVE) / chartMax.value * 100 }
watch(overview, data => {
  if (!data) return
  tick.value = Date.now(); dayEnd.value = nextDayStart(data.serverNow, zone)
  page.value = Math.min(page.value, pageCount.value - 1)
}, { immediate: true })
function onVisible() { if (!document.hidden) void refresh() }
onMounted(() => {
  void refresh()
  ticker = setInterval(() => { tick.value = Date.now(); if (!document.hidden && overview.value && localDay(serverEstimate.value, zone) !== overview.value.today) void refresh() }, 1000)
  poller = setInterval(onVisible, 15000)
  document.addEventListener('visibilitychange', onVisible); window.addEventListener('focus', onVisible)
})
onBeforeUnmount(() => { if (!workspaceController) controller.dispose(); clearInterval(ticker); clearInterval(poller); document.removeEventListener('visibilitychange', onVisible); window.removeEventListener('focus', onVisible) })
</script>

<style scoped>
.focus-center[data-focus-state] :is(.intro h2, .intro .eyebrow, .intro p, .today-heading h3, .today-heading .eyebrow, .today-heading > span, .timing-note) { color: var(--focus-state-ink); }
.focus-center[data-focus-state] :is(.switch-panel, .history-section, .refresh-button) { background: #fff; }
.focus-center{max-width:1160px;margin:auto;color:#20344f}.intro,.today-heading,.section-head{display:flex;justify-content:space-between;align-items:center;gap:20px}.intro{margin-bottom:34px}.eyebrow{font-size:11px;letter-spacing:.17em;color:#7b95b2;font-weight:800}.intro h2{font-size:clamp(28px,3.4vw,42px);letter-spacing:-.04em;margin:12px 0}.intro p{font-size:14px;color:#758ba5;line-height:1.8;max-width:700px;margin:0}.subtle-button{border:1px solid #dfe8f1;background:#ffffffbf;color:#406286;border-radius:12px;padding:10px 15px;font-weight:700;cursor:pointer}.refresh-button{white-space:nowrap}.today-heading{margin-bottom:16px}.today-heading h3,.section-head h3{margin:8px 0 0;font-size:25px}.today-heading>span{color:#8193ac;font-size:12px}.category-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.category-card{position:relative;padding:27px 30px;border:1px solid #ffffff;border-radius:27px;background:#ffffffd9;box-shadow:0 15px 40px #244f810c;overflow:hidden}.category-card.effective{background:linear-gradient(135deg,#fcfffe,#edf8f4)}.category-card.ineffective{background:linear-gradient(135deg,#fffefd,#f5f0e8)}.category-card.running{border-color:#3d8c79;box-shadow:0 0 0 2px #3d8c7917,0 15px 40px #244f810c}.category-card.ineffective.running{border-color:#a28a68;box-shadow:0 0 0 2px #a28a6817,0 15px 40px #244f810c}.card-top{display:flex;align-items:center;justify-content:space-between;gap:12px}.glyph{display:grid;place-items:center;width:46px;height:46px;border-radius:15px;background:#dcefe7;color:#2a826c;font-size:28px}.ineffective .glyph{background:#eae1d4;color:#8e7554}.card-status{display:flex;align-items:center;gap:7px;color:#7a8996;font-size:12px;font-weight:700}.live-dot{width:7px;height:7px;border-radius:50%;background:#358f76;box-shadow:0 0 0 4px #358f7612}.ineffective .live-dot{background:#a58a66;box-shadow:0 0 0 4px #a58a6612}.category-card h3{font-size:23px;margin:18px 0 5px}.category-card p{font-size:13px;line-height:1.6;margin:0;color:#8293a4}.big-time{display:block;margin:24px 0;font-size:clamp(37px,4.7vw,58px);font-variant-numeric:tabular-nums;letter-spacing:-.035em;line-height:1.15}.category-footer{display:flex;justify-content:space-between;border-top:1px solid #d8e6e177;padding-top:15px;color:#7d92a0;font-size:12px}.category-footer b{font-size:15px;color:#42635a}.ineffective .category-footer b{color:#887357}.switch-panel{display:flex;align-items:center;justify-content:space-between;gap:24px;margin-top:20px;padding:21px 25px;background:#ffffffb8;border:1px solid #fff;border-radius:20px}.active-hint{font-size:15px;font-weight:700;margin:0 0 7px}.shortcut-hint{font-size:12px;color:#8394aa}.shortcut-hint kbd{display:inline-block;padding:2px 7px;margin:0 3px;border:1px solid #cddbea;border-bottom-width:2px;border-radius:5px;color:#456783;font:700 11px inherit}.switch-button{display:flex;align-items:center;justify-content:center;gap:25px;background:#2b7568;border:0;color:#fff;border-radius:13px;padding:14px 22px;font-size:15px;font-weight:750;cursor:pointer;min-width:195px}.switch-button span{font-size:23px;line-height:1}.timing-note{font-size:12px;line-height:1.8;color:#8294aa;margin:12px 5px 32px}.history-section{background:#ffffffd9;border:1px solid #fff;border-radius:28px;padding:28px}.range-picker{display:flex;background:#edf2f8;border-radius:12px;padding:4px;gap:2px}.range-picker button{border:0;background:transparent;border-radius:9px;padding:9px 12px;color:#7a8da4;font-weight:750;font-size:12px;cursor:pointer;white-space:nowrap}.range-picker button.selected{color:#316958;background:#fff;box-shadow:0 3px 8px #19355b08}.retention-note,.legacy-note{font-size:12px;color:#8797ab;line-height:1.8}.retention-note{margin:15px 0 20px}.history-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.history-summary>div{border-radius:15px;background:#f3f7fa;padding:17px 19px}.history-summary>div:first-child{background:#edf6f2}.history-summary>div:nth-child(2){background:#f8f3ed}.history-summary span{font-size:12px;color:#7e91a3}.history-summary strong{display:block;font-size:clamp(18px,2.6vw,25px);margin-top:7px;font-variant-numeric:tabular-nums}.chart-header{display:flex;justify-content:space-between;gap:15px;margin:28px 0 14px;color:#8293a8;font-size:11px}.chart-legend,.chart-legend>span{display:flex;align-items:center;gap:7px}.chart-legend{gap:15px;flex-shrink:0}.chart-legend i{width:8px;height:8px;border-radius:3px}.effective-swatch,.effective-fill{background:#56a58d}.ineffective-swatch,.ineffective-fill{background:#ccb596}.history-chart{display:grid;grid-template-columns:repeat(var(--bar-count),minmax(0,1fr));gap:12px;border-bottom:1px solid #e7edf3;padding:0 7px 18px}.chart-column{display:flex;flex-direction:column;align-items:center;gap:9px;color:#8293a8;font-size:10px;font-variant-numeric:tabular-nums}.chart-track{height:140px;width:min(100%,40px);display:flex;align-items:flex-end;background:linear-gradient(to top,#eef3f788,transparent);border-radius:7px}.bar-stack{display:flex;flex-direction:column-reverse;width:100%;border-radius:6px;overflow:hidden;min-height:0}.bar-stack i{display:block;width:100%;min-height:0}.daily-records{margin-top:19px;overflow-x:auto}.daily-records table{width:100%;border-collapse:collapse;font-size:12px;white-space:nowrap}.daily-records th,.daily-records td{padding:14px 8px;border-bottom:1px solid #edf1f5;text-align:right;font-variant-numeric:tabular-nums}.daily-records th:first-child{text-align:left}.daily-records thead{font-size:11px;color:#8798ab}.daily-records tbody th{font-weight:600}.daily-records tbody th small{font-weight:400;color:#8ea0b3;margin-left:7px}.daily-records td{color:#587088}.daily-records td:nth-child(2){color:#377864}.pagination{display:flex;justify-content:space-between;align-items:center;gap:15px;margin-top:19px;color:#8a9aac;font-size:11px}.pagination>div{display:flex;gap:8px}.pagination .subtle-button{font-size:11px;padding:8px 11px}.legacy-note{margin:20px 0 0}.empty-history{text-align:center;color:#8a9bb0;font-size:12px;padding:18px}.notice,.loading{padding:17px 20px;border:1px solid #eadfd0;background:#fff8ed;border-radius:15px;color:#8c7151;font-size:13px;line-height:1.7}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}button:disabled{opacity:.48;cursor:not-allowed}button:focus-visible{outline:3px solid #89b8e8;outline-offset:3px}
@media(max-width:720px){.intro{align-items:flex-start;gap:14px}.intro h2{font-size:29px}.intro p{font-size:12px}.refresh-button{padding:9px 11px;font-size:11px}.category-card{padding:22px}.big-time{font-size:36px}.section-head{align-items:flex-start;flex-direction:column}.history-section{padding:22px}.history-chart{gap:6px}.history-summary>div{padding:14px}.chart-header{flex-direction:column;gap:10px}.today-heading>span{max-width:150px;line-height:1.6;text-align:right}.switch-panel{padding:19px;gap:16px}.switch-button{min-width:175px;padding:13px 17px}.chart-column{font-size:9px}}
@media(max-width:480px){.intro{flex-wrap:wrap;margin-bottom:24px}.intro h2{font-size:28px}.category-grid{gap:12px}.category-card{padding:17px 14px;border-radius:20px}.glyph{width:36px;height:36px;font-size:23px;border-radius:11px}.card-status{font-size:10px;gap:5px}.category-card h3{font-size:20px;margin-top:17px}.category-card p{font-size:11px;min-height:36px}.big-time{font-size:clamp(24px,7.5vw,35px);margin:20px 0;letter-spacing:-.04em}.category-footer{font-size:10px}.switch-panel{align-items:stretch;flex-direction:column}.active-hint{font-size:14px}.switch-button{width:100%}.today-heading h3,.section-head h3{font-size:22px}.today-heading>span{font-size:10px}.history-section{padding:18px 14px;border-radius:22px}.history-summary{gap:7px}.history-summary>div{padding:12px 10px}.history-summary strong{font-size:16px}.history-summary span{font-size:10px}.range-picker{width:100%}.range-picker button{flex:1;padding:8px 9px;font-size:11px}.history-chart{gap:5px;padding-left:0;padding-right:0}.chart-column:nth-child(even)>span{visibility:hidden}.chart-track{height:115px}.daily-records th,.daily-records td{padding:12px 5px;font-size:11px}.daily-records tbody th small{display:block;margin:4px 0 0;font-size:10px}.pagination{flex-wrap:wrap}.pagination>div{margin-left:auto}.timing-note{font-size:11px}}
</style>
