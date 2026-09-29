<template>
  <section class="focus-center" :aria-label="t('专注与每周复盘')">
    <header class="intro"><div><span class="eyebrow">FOCUS · DAILY TIME</span><h2>{{ t("把时间花在哪里，看得见。") }}</h2><p>{{ t("开始和结束都会保存到本机数据库。一次只运行一个计时器，刷新页面也会继续计时。") }}</p><p class="shortcut-hint">{{ t("在此页面按数字键") }} <kbd>1</kbd> {{ t("招工、") }}<kbd>2</kbd> {{ t("法语、") }}<kbd>3</kbd> {{ t("课程、") }}<kbd>4</kbd> {{ t("娱乐，可快速开始或结束。") }}</p></div><button type="button" @click="refresh" :disabled="busy">{{ t("刷新记录") }}</button></header>
    <p v-if="error" class="notice" role="alert">{{ t(error) }}</p>
    <div v-if="!overview" class="loading">{{ t("正在读取计时记录…") }}</div>
    <template v-else>
      <div class="today-heading"><div><span class="eyebrow">TODAY</span><h3>{{ t("今天的时间") }}</h3></div><span>{{ overview.today }} · {{ zone }}</span></div>
      <div class="category-grid">
        <article v-for="item in categories" :key="item.key" :class="['category-card', item.key.toLowerCase(), { running: overview.active?.category === item.key }]">
          <div class="card-top"><div class="glyph">{{ item.icon }}</div><span v-if="overview.active?.category === item.key" class="live-dot">{{ t("正在计时") }}</span><kbd class="shortcut-key" :aria-label="t('快捷键 {v0}', { v0: item.shortcut })">{{ item.shortcut }}</kbd></div>
          <h3>{{ t(item.label) }}</h3><p>{{ t(item.description) }}</p>
          <template v-if="item.key === 'ENTERTAINMENT'"><strong class="big-time">{{ countdown(remainingMs) }}</strong><small>{{ t("今天剩余 · 已使用") }} {{ duration(todayMs(item.key)) }} / {{ duration(limitMs) }}</small></template>
          <template v-else><strong class="big-time">{{ duration(todayMs(item.key)) }}</strong><small>{{ t("今天累计") }}</small></template>
          <button class="timer-button" type="button" :aria-keyshortcuts="item.shortcut" :disabled="!canToggle(item.key)" @click="toggle(item.key)">{{ overview.active?.category === item.key ? t("结束并保存") : item.key === 'ENTERTAINMENT' && remainingMs <= 0 ? t("今日额度已用完") : t("开始计时") }}</button>
        </article>
      </div>
      <div class="controls-row">
        <label class="task-picker">{{ t("关联现有任务（可选）") }}<select v-model="selectedTaskId" :disabled="!!overview.active"><option value="">{{ t("不关联任务") }}</option><option v-for="task in openTasks" :key="task.id" :value="task.id">{{ task.text }}</option></select></label>
        <form class="limit-form" @submit.prevent="saveLimit"><label>{{ t("每天娱乐额度（小时）") }}<input v-model.number="quotaHours" type="number" min="0.25" max="24" step="0.25" required /></label><button :disabled="busy" type="submit">{{ t("保存额度") }}</button></form>
      </div>
      <p v-if="remainingMs <= 0" class="quota-finished" role="status">{{ t('今天的娱乐额度已用完。明天会自动恢复为 {duration}。', { duration: duration(limitMs) }) }}</p>
      <p v-if="overview.active" class="active-hint">{{ t('当前正在记录「{category}」', { category: label(overview.active.category) }) }}<template v-if="runningTask"> · {{ runningTask.text }}</template>{{ t("。娱乐额度到零会自动停止；其他类别点击“结束并保存”即可。") }}</p>

      <section class="weekly"><div class="section-head"><div><span class="eyebrow">WEEKLY REVIEW</span><h3>{{ t("本周复盘") }}</h3></div><span>{{ t('自 {date} 起', { date: overview.weekStart }) }}</span></div>
        <div class="review-stats"><div><strong>{{ duration(workWeekMs) }}</strong><span>{{ t("本周专注") }}</span></div><div><strong>{{ completedThisWeek.length }}</strong><span>{{ t("本周完成任务") }}</span></div><div><strong>{{ overdueTasks.length }}</strong><span>{{ t("当前已逾期") }}</span></div><div><strong>{{ repeatedTasks.length }}</strong><span>{{ t("曾多次延后截止日") }}</span></div></div>
        <div class="weekly-days"><div v-for="(totals, day) in overview.weekDays" :key="day"><span>{{ weekday(day) }}</span><div class="day-bar"><i :style="{ height: barHeight(workMs(totals)) + '%' }"></i></div><strong>{{ duration(workMs(totals)) }}</strong><small>{{ day.slice(5) }}</small></div></div>
        <div class="week-breakdown"><table><thead><tr><th>{{ t("日期") }}</th><th v-for="item in categories" :key="item.key">{{ t(item.label) }}</th></tr></thead><tbody><tr v-for="(totals, day) in overview.weekDays" :key="day"><th>{{ day.slice(5) }} {{ weekday(day) }}</th><td v-for="item in categories" :key="item.key">{{ duration(totals[item.key]) }}</td></tr></tbody><tfoot><tr><th>{{ t("本周合计") }}</th><td v-for="item in categories" :key="item.key">{{ duration((overview.weekly[item.key] || 0) + (overview.active?.category === item.key ? extraMs : 0)) }}</td></tr></tfoot></table></div>
        <div class="review-lists"><section><h4>{{ t("本周完成") }}</h4><p v-if="!completedThisWeek.length" class="muted">{{ t("本周还没有完成的任务。") }}</p><ul v-else><li v-for="task in completedThisWeek.slice(0, 8)" :key="task.id"><span>{{ task.text }}</span><small>{{ taskTime(task.id) }} · {{ shortDate(task.completedAt) }}</small></li></ul></section><section><h4>{{ t("延期与待处理") }}</h4><p v-if="!reviewTasks.length" class="muted">{{ t("目前没有逾期或反复延后的任务。") }}</p><ul v-else><li v-for="task in reviewTasks.slice(0, 8)" :key="task.id"><span>{{ task.text }}</span><small><template v-if="task.dueAt < tick">{{ t("逾期") }} {{ overdueDays(task) }} {{ t("天 ·") }} </template><template v-if="task.rescheduleCount">{{ t("截止日推后") }} {{ task.rescheduleCount }} {{ t("次 ·") }} </template>{{ t("专注") }} {{ taskTime(task.id) }}</small></li></ul></section></div>
        <p class="review-note">{{ t("“截止日推后次数”从此功能启用后开始记录；已删除任务不会出现在复盘里。逾期只按当前未完成任务计算。") }}</p>
      </section>
    </template>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { useTaskStore } from '../stores/taskStore'

const { t, dateLocale } = useI18n()
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Toronto'
const categories = [
  { key: 'JOB', label: '招工', icon: '↗', shortcut: '1', description: '求职、投递、面试准备' },
  { key: 'FRENCH', label: '法语', icon: 'Fr', shortcut: '2', description: '学习、听说读写练习' },
  { key: 'COURSE', label: '课程', icon: '▦', shortcut: '3', description: '上课、作业、复习' },
  { key: 'ENTERTAINMENT', label: '娱乐', icon: '▶', shortcut: '4', description: '按今天的额度倒计时' }
]
const { tasks, openTasks, refresh: refreshTasks } = useTaskStore()
const overview = ref(null), error = ref(''), busy = ref(false), selectedTaskId = ref(''), quotaHours = ref(5)
const tick = ref(Date.now()), snapshotAt = ref(Date.now())
let timer, polling = false, autoRefresh = false
const limitMs = computed(() => (overview.value?.entertainmentLimitMinutes || 300) * 60000)
const runningTask = computed(() => tasks.value.find(t => t.id === overview.value?.active?.taskId))
const extraMs = computed(() => {
  if (!overview.value?.active) return 0
  const dayEnd = new Date(); dayEnd.setHours(24, 0, 0, 0)
  return Math.max(0, Math.min(tick.value, dayEnd.getTime()) - snapshotAt.value)
})
function todayMs(key) {
  const stored = overview.value?.daily?.[key] || 0
  const extra = overview.value?.active?.category === key ? extraMs.value : 0
  return key === 'ENTERTAINMENT' ? Math.min(limitMs.value, stored + extra) : stored + extra
}
const remainingMs = computed(() => Math.max(0, limitMs.value - todayMs('ENTERTAINMENT')))
function workMs(totals) { return (totals?.JOB || 0) + (totals?.FRENCH || 0) + (totals?.COURSE || 0) }
const workWeekMs = computed(() => workMs(overview.value?.weekly) + (overview.value?.active?.category !== 'ENTERTAINMENT' ? extraMs.value : 0))
const weekStartMs = computed(() => overview.value ? new Date(`${overview.value.weekStart}T00:00:00`).getTime() : 0)
const weekEndMs = computed(() => { const end = new Date(weekStartMs.value); end.setDate(end.getDate() + 7); return end.getTime() })
const completedThisWeek = computed(() => tasks.value.filter(t => t.status === 'COMPLETED' && t.completedAt >= weekStartMs.value && t.completedAt < weekEndMs.value).sort((a, b) => b.completedAt - a.completedAt))
const overdueTasks = computed(() => tasks.value.filter(t => t.status !== 'COMPLETED' && t.dueAt != null && t.dueAt < tick.value).sort((a, b) => (b.rescheduleCount || 0) - (a.rescheduleCount || 0) || a.dueAt - b.dueAt))
const repeatedTasks = computed(() => openTasks.value.filter(t => (t.rescheduleCount || 0) >= 2))
const reviewTasks = computed(() => openTasks.value.filter(t => t.dueAt != null && t.dueAt < tick.value || (t.rescheduleCount || 0) >= 2)
  .sort((a, b) => (b.rescheduleCount || 0) - (a.rescheduleCount || 0) || (a.dueAt || Number.MAX_SAFE_INTEGER) - (b.dueAt || Number.MAX_SAFE_INTEGER)))
const maxDayMs = computed(() => Math.max(3600000, ...Object.values(overview.value?.weekDays || {}).map(workMs)))
function barHeight(value) { return Math.max(5, Math.round(value / maxDayMs.value * 100)) }
function duration(ms) { const minutes = Math.floor(Math.max(0, ms) / 60000); return t("{v0}小时 {v1}分", { v0: Math.floor(minutes / 60), v1: String(minutes % 60).padStart(2, '0') }) }
function countdown(ms) { const s = Math.ceil(Math.max(0, ms) / 1000); return `${String(Math.floor(s / 3600)).padStart(2, '0')}:${String(Math.floor(s % 3600 / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}` }
function weekday(day) { return new Intl.DateTimeFormat(dateLocale.value, { weekday: 'short' }).format(new Date(`${day}T12:00:00`)) }
function shortDate(stamp) { return new Date(stamp).toLocaleDateString(dateLocale.value, { month: 'numeric', day: 'numeric' }) }
function overdueDays(task) { return Math.max(1, Math.ceil((tick.value - task.dueAt) / 86400000)) }
function taskTime(id) { return duration(overview.value?.taskTimes?.find(t => t.taskId === id)?.millis || 0) }
function label(key) { return t(categories.find(c => c.key === key)?.label || key) }
function apply(data) { overview.value = data; snapshotAt.value = Date.now(); tick.value = snapshotAt.value; quotaHours.value = data.entertainmentLimitMinutes / 60; error.value = '' }
async function refresh() { if (polling) return; polling = true; try { apply((await axios.get('/api/focus', { params: { zone }, timeout: 12000 })).data); await refreshTasks({ force: true }) } catch (failure) { error.value = failure.response?.data?.message || "计时记录暂时无法读取，请重试" } finally { polling = false } }
async function send(url, body, method = 'post') { busy.value = true; error.value = ''; try { apply((await axios[method](url, { ...body, zone }, { timeout: 12000 })).data) } catch (failure) { error.value = failure.response?.data?.message || "保存失败，请重试"; await refresh() } finally { busy.value = false } }
function start(category) { return send('/api/focus/start', { category, taskId: category === 'ENTERTAINMENT' ? null : selectedTaskId.value || null }) }
function stop() { if (overview.value?.active) return send('/api/focus/stop', { id: overview.value.active.id }) }
function canToggle(category) {
  return !!overview.value && !busy.value &&
    (!overview.value.active || overview.value.active.category === category) &&
    !(category === 'ENTERTAINMENT' && remainingMs.value <= 0)
}
function toggle(category) {
  if (!canToggle(category)) return
  return overview.value.active?.category === category ? stop() : start(category)
}
function onShortcut(event) {
  if (event.defaultPrevented || event.repeat || event.isComposing || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || document.hidden) return
  const target = event.target
  if (target instanceof Element && (target.closest('input, textarea, select, [contenteditable]') || target.isContentEditable)) return
  const category = categories.find(item => item.shortcut === event.key)?.key
  if (!category || !canToggle(category)) return
  event.preventDefault()
  toggle(category)
}
function saveLimit() { if (!Number.isFinite(quotaHours.value) || quotaHours.value < .25 || quotaHours.value > 24) { error.value = "请输入 0.25 到 24 小时"; return } return send('/api/focus/limit', { minutes: Math.round(quotaHours.value * 60) }, 'put') }
function onVisible() { if (!document.hidden) refresh() }
onMounted(() => { refresh(); timer = setInterval(() => { tick.value = Date.now(); if (!overview.value || document.hidden) return; const today = new Intl.DateTimeFormat('sv-SE', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()); if (today !== overview.value.today || overview.value.active?.category === 'ENTERTAINMENT' && remainingMs.value <= 0) { if (!autoRefresh) { autoRefresh = true; refresh().finally(() => { autoRefresh = false }) } } }, 1000); document.addEventListener('visibilitychange', onVisible); window.addEventListener('focus', onVisible); window.addEventListener('keydown', onShortcut) })
onBeforeUnmount(() => { clearInterval(timer); document.removeEventListener('visibilitychange', onVisible); window.removeEventListener('focus', onVisible); window.removeEventListener('keydown', onShortcut) })
</script>

<style scoped>
.focus-center{max-width:1260px;margin:auto;color:#1e304d}.intro,.section-head,.today-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.intro{margin:0 0 30px}.eyebrow{font-size:11px;letter-spacing:.18em;color:#7d94b3;font-weight:800}.intro h2{font-size:clamp(27px,3vw,40px);margin:11px 0}.intro p,.today-heading>span,.section-head>span{font-size:13px;color:#7a8ca6}.intro button,.limit-form button{border:0;border-radius:12px;background:#eaf1fc;color:#3464a7;padding:11px 16px;font-weight:750;cursor:pointer}.loading,.notice{padding:20px;border-radius:16px;background:#fff4dd;color:#775e36}.today-heading{margin-bottom:16px}.today-heading h3,.section-head h3{margin:7px 0;font-size:24px}.category-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:15px}.category-card{display:flex;flex-direction:column;min-width:0;min-height:254px;padding:24px;border:1px solid #fff;border-radius:28px;background:#ffffffdd;box-shadow:0 14px 35px #244f810e}.category-card.entertainment{background:linear-gradient(145deg,#fffdf7,#fff1e7)}.category-card.running{outline:2px solid #639bf3}.card-top{display:flex;justify-content:space-between;align-items:center}.glyph{width:44px;height:44px;display:grid;place-items:center;border-radius:14px;background:#e9f1ff;color:#3473cf;font-size:22px;font-weight:900}.entertainment .glyph{background:#ffe7d9;color:#df714a}.live-dot{color:#178e5b;font-size:11px;font-weight:800}.category-card h3{margin:16px 0 4px;font-size:21px}.category-card p{margin:0;color:#7e91ab;font-size:12px}.big-time{font-variant-numeric:tabular-nums;font-size:clamp(23px,2.5vw,35px);margin:22px 0 4px;letter-spacing:-.03em;white-space:nowrap}.category-card small{color:#8999ad;line-height:1.5}.timer-button{margin-top:auto;padding:11px 14px;border:0;border-radius:12px;background:#2f72de;color:#fff;font-weight:800;cursor:pointer}.running .timer-button{background:#1e9b69}.entertainment .timer-button{background:#e87850}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #82aff0;outline-offset:2px}.controls-row{display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;margin:20px 0}.task-picker,.limit-form label{display:grid;gap:7px;font-size:12px;color:#6d809b;font-weight:700}.task-picker{flex:1;min-width:250px;max-width:570px}.task-picker select,.limit-form input{border:1px solid #dbe5f2;border-radius:12px;background:white;padding:11px;color:#29466f;font:inherit}.limit-form{display:flex;align-items:end;gap:10px}.limit-form input{width:115px}.active-hint{padding:13px 17px;border-radius:13px;background:#e8f4ed;color:#368360;font-size:13px}.weekly{margin-top:30px;border:1px solid #fff;border-radius:30px;background:#ffffffd9;padding:30px}.review-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0 27px}.review-stats>div{padding:16px;border-radius:16px;background:#f0f5fd}.review-stats strong{display:block;font-size:24px}.review-stats span{font-size:11px;color:#7890ad}.weekly-days{display:grid;grid-template-columns:repeat(7,1fr);gap:13px;align-items:end;min-height:170px;padding:18px;border-radius:20px;background:#f7faff}.weekly-days>div{display:grid;justify-items:center;gap:5px;color:#6f829d;font-size:11px}.weekly-days strong{font-size:11px;color:#385b8d}.day-bar{height:90px;width:min(100%,52px);display:flex;align-items:flex-end;border-radius:8px;background:#e3ecfa;overflow:hidden}.day-bar i{display:block;width:100%;background:linear-gradient(#84b5ff,#3779e4);border-radius:8px 8px 0 0}.week-breakdown{overflow:auto;margin-top:17px}.week-breakdown table{width:100%;border-collapse:collapse;min-width:640px;font-size:12px}.week-breakdown th,.week-breakdown td{padding:11px 8px;text-align:left;border-bottom:1px solid #e7edf6}.week-breakdown th{color:#6d83a2}.week-breakdown td{color:#3c587b}.week-breakdown tfoot{font-weight:800;background:#f3f8ff}.review-lists{display:grid;grid-template-columns:1fr 1fr;gap:26px;margin-top:25px}.review-lists h4{font-size:17px}.review-lists ul{padding:0;list-style:none}.review-lists li{display:flex;justify-content:space-between;gap:15px;border-top:1px solid #eaf0f7;padding:12px 0;font-size:13px}.review-lists li span{overflow-wrap:anywhere}.review-lists li small{color:#7d8fa9;white-space:nowrap}.muted,.review-note{color:#8c9bb0;font-size:12px;line-height:1.7}.review-note{margin-top:20px}@media(max-width:1000px){.category-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:640px){.category-grid,.review-lists{grid-template-columns:1fr}.review-stats{grid-template-columns:repeat(2,1fr)}.weekly{padding:20px}.weekly-days{gap:5px;padding:12px}.weekly-days strong{font-size:9px}.intro{align-items:flex-start;flex-direction:column}.category-card{min-height:225px}.big-time{font-size:30px}}
.quota-finished{padding:14px 18px;border-radius:13px;background:#fff0e8;color:#b55e40;font-size:13px;font-weight:700}
.shortcut-hint{margin-top:8px!important}.shortcut-hint kbd,.shortcut-key{display:inline-grid;place-items:center;min-width:23px;height:23px;padding:0 5px;border:1px solid #d4e0f1;border-radius:7px;background:#f6f9ff;color:#3464a7;font-family:inherit;font-size:12px;font-weight:800}.shortcut-key{margin-left:auto}.card-top .live-dot+.shortcut-key{margin-left:0}
</style>
