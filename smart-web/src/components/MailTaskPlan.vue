<template>
  <section class="task-plan" :aria-label="t('邮件任务规划')">
    <header class="plan-heading">
      <div><span class="eyebrow">MAIL · AI PLANNER</span><h2>{{ t('把邮件里的待办，整理在一起。') }}</h2><p>{{ t('最近 5 天 · 全部邮箱 · 在 Inbox 标为已读后，相关建议会移出，已加入「先记再做」的任务保留。AI 建议仅供核对，请以原邮件为准。') }}</p></div>
      <button class="analyze-button" :disabled="busy || running" @click="refresh">{{ running ? t('正在分析…') : plan.state === 'PARTIAL' ? t('重试未完成分析') : t('分析新增邮件') }}</button>
    </header>
    <div class="plan-stats"><div><strong>{{ plan.totalMails }}</strong><span>{{ t('封邮件纳入分析') }}</span></div><div><strong>{{ plan.analyzedMails }}</strong><span>{{ t('封已完成分析') }}</span></div><div><strong>{{ plan.tasks.length }}</strong><span>{{ t('条可能需要处理的任务') }}</span></div></div>
    <div v-if="running || plan.pendingMails" class="plan-progress" role="status" aria-live="polite">
      <div><span>{{ running ? t('本地 AI 正在逐封分析，结果会陆续显示') : t('还有邮件等待分析') }}</span><strong>{{ plan.analyzedMails }} / {{ plan.totalMails }}</strong></div>
      <progress :value="plan.analyzedMails" :max="plan.totalMails || 1"></progress>
    </div>
    <p v-if="error || plan.error" class="plan-notice" role="alert">{{ t(error || plan.error) }}</p>
    <details v-if="plan.failedMails.length" class="plan-notice"><summary>{{ plan.failedMails.length }} {{ t('封邮件暂未完成分析，可以点击上方按钮重试') }}</summary><div v-for="mail in plan.failedMails" :key="mail.mailId" class="failed-mail"><span>{{ mail.subject }}</span><button @click="$emit('open-mail', { id: mail.mailId })">{{ t('查看原邮件') }}</button></div></details>
    <div class="plan-filters" role="group" :aria-label="t('任务类型')"><button v-for="option in filters" :key="option.key" :class="{ active: filter === option.key }" :aria-pressed="filter === option.key" @click="filter = option.key">{{ t(option.label) }}</button><small v-if="plan.updatedAt">{{ t('已自动保存在本机 ·') }} {{ formatTime(plan.updatedAt) }}</small></div>
    <div v-if="visibleTasks.length" class="plan-list">
      <article v-for="(task, index) in visibleTasks" :key="task.mailId + ':' + task.suggestion.id" class="plan-card">
        <div class="plan-card-title"><span class="task-number">{{ String(index + 1).padStart(2, '0') }}</span><div><div class="task-badges"><span :class="['priority', task.suggestion.priority]">{{ t(priorities[task.suggestion.priority] || '普通优先级') }}</span><span>{{ task.suggestion.obligation === 'REQUIRED' ? t('邮件明确要求') : t('可选事项') }}</span></div><h3>{{ task.suggestion.title }}</h3></div></div>
        <p class="task-description">{{ task.suggestion.details }}</p>
        <div class="task-deadline"><span>{{ t('截止时间') }}</span><strong>{{ task.suggestion.deadlineText || t('邮件未明确说明') }}</strong><small v-if="task.suggestion.deadlineText">{{ t('沿用原文表述，日期与时区以原邮件为准') }}</small></div>
        <details class="task-evidence"><summary>{{ t('查看邮件依据') }}</summary><blockquote>{{ task.suggestion.evidence }}</blockquote></details>
        <footer><div><span>{{ sourceName(task.source) }} · {{ formatTime(task.receivedAt) }}</span><p>{{ task.subject }}</p></div><div class="suggestion-actions"><button @click="$emit('open-mail', { id: task.mailId })">{{ t('查看来源邮件 ›') }}</button><button class="add-task" :disabled="!!converted(task)" @click="prepareTask(task)">{{ converted(task)?.status === 'COMPLETED' ? t('✓ 任务已完成') : converted(task) ? t('✓ 已加入任务') : t('+ 加入任务') }}</button></div></footer>
      </article>
    </div>
    <div v-else class="plan-empty"><span>✓</span><h3>{{ t(emptyTitle) }}</h3><p>{{ running ? t('可以先浏览其他页面，分析会在后台继续。') : t('任务建议来自邮件正文，不会自动执行，也不会更改原邮箱状态。') }}</p></div>
    <el-dialog v-model="confirmVisible" :title="t('确认加入任务')" width="min(560px, 92vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!savingTask" :show-close="!savingTask" :before-close="closeConfirmation">
      <form class="confirm-task-form" @submit.prevent="confirmTask">
        <p class="confirm-hint">{{ t('核对 AI 建议后再保存。截止时间请按你的当地时间填写。') }}</p>
        <label>{{ t('任务内容') }}<input v-model="draft.text" required maxlength="300"></label>
        <label>{{ t('优先级') }}<select v-model="draft.priority"><option v-for="(label,key) in priorities" :key="key" :value="key">{{ t(label) }}</option></select></label>
        <label>{{ t('截止时间') }}<input v-model="draft.due" type="datetime-local" min="1970-01-01T00:00" max="2099-12-31T23:59"><small>{{ t('原邮件表述：') }}{{ selectedTask?.suggestion.deadlineText || t('未明确说明，可暂不设置') }}</small></label>
        <label>{{ t('备注') }}<textarea v-model="draft.notes" maxlength="2000" rows="4"></textarea></label>
        <p v-if="conversionError" class="conversion-error" role="alert">{{ t(conversionError) }}</p>
        <div class="confirm-actions"><button type="button" :disabled="savingTask" @click="confirmVisible = false">{{ t('取消') }}</button><button type="submit" class="analyze-button" :disabled="savingTask">{{ savingTask ? t('保存中…') : t('确认加入任务') }}</button></div>
      </form>
    </el-dialog>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { ElDialog } from 'element-plus'
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useTaskStore, convertMailTask, parseDue, taskError } from '../stores/taskStore'
defineEmits(['open-mail'])
const plan = ref({ state: 'PENDING', totalMails: 0, analyzedMails: 0, pendingMails: 0, failedMails: [], tasks: [] })
const busy = ref(false), error = ref(''), filter = ref('ALL')
const { tasks: savedTasks } = useTaskStore()
const convertedTasks = computed(() => new Map(savedTasks.value.filter(task => task.sourceMailId != null).map(task => [task.sourceMailId + ':' + task.sourceSuggestionId, task])))
const confirmVisible = ref(false), selectedTask = ref(null), savingTask = ref(false), conversionError = ref('')
const draft = reactive({ text: '', priority: 'NORMAL', due: '', notes: '' })
const filters = [{ key: 'ALL', label: '全部建议' }, { key: 'REQUIRED', label: '明确要求' }, { key: 'OPTIONAL', label: '可选事项' }]
const priorities = { HIGH: '优先处理', NORMAL: '普通优先级', LOW: '低优先级' }
const running = computed(() => plan.value.state === 'RUNNING')
const visibleTasks = computed(() => plan.value.tasks.filter(task => filter.value === 'ALL' || task.suggestion.obligation === filter.value))
const emptyTitle = computed(() => running.value ? '正在寻找可能需要你处理的事项…' : plan.value.tasks.length ? '这个分类暂时没有任务' : plan.value.state === 'PENDING' ? '准备分析邮件' : plan.value.state === 'PARTIAL' ? '分析尚未完成，请重试' : plan.value.totalMails ? '已分析的邮件中没有发现待办事项' : '最近 5 天没有待规划的邮件')
let timer, disposed = false, fetching = false, lastRefresh = 0, detailVersion = null
function sourceName(source) { return t(({ GMAIL: 'Gmail', QQMAIL: 'QQ 邮箱', OUTLOOK: 'Outlook' })[source] || source) }
function formatTime(value) { return value ? new Date(value).toLocaleString(dateLocale.value, { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '' }
async function refresh() {
  if (busy.value || running.value) return
  busy.value = true; error.value = ''; lastRefresh = Date.now()
  try {
    if (plan.value.state === 'PARTIAL') {
      const { data } = await axios.post('/api/mails/task-plan/retry', {}, { timeout: 20000 })
      if (!disposed) applyProgress(data)
      await loadDetails()
    } else {
      const { data } = await axios.post('/api/mails/task-plan/refresh', {}, { timeout: 20000 })
      if (!disposed) { plan.value = { failedMails: [], tasks: [], ...data }; detailVersion = data.version }
    }
  }
  catch { if (!disposed) error.value = '无法启动分析，请确认后端和本地 AI 正在运行后重试。' }
  finally { busy.value = false; schedulePoll() }
}
function applyProgress(data) {
  // Progress is a cheap snapshot. Keep the task array until the version changes.
  for (const key of ['state', 'totalMails', 'analyzedMails', 'pendingMails', 'error', 'lastFinished']) plan.value[key] = data[key]
}
async function loadDetails() {
  const response = await axios.get('/api/mails/task-plan', { params: detailVersion == null ? {} : { sinceVersion: detailVersion }, timeout: 15000 })
  if (!disposed && response.status !== 204) {
    plan.value = { failedMails: [], tasks: [], ...response.data }; detailVersion = response.data.version
  }
}
function schedulePoll() {
  clearTimeout(timer)
  if (!disposed && !document.hidden) timer = setTimeout(poll, running.value || plan.value.state === 'PENDING' ? 5000 : 30000)
}
async function poll() {
  if (disposed || document.hidden || fetching || busy.value) return
  fetching = true
  try {
    const response = await axios.get('/api/mails/task-plan/progress', { timeout: 15000 })
    if (disposed) return
    applyProgress(response.data); error.value = ''
    if (detailVersion !== response.data.version) await loadDetails()
    if (plan.value.state === 'PENDING' && Date.now() - lastRefresh > 60000) await refresh()
  } catch { if (!disposed) error.value = '暂时无法更新分析进度，连接恢复后会自动重试。' }
  finally { fetching = false; schedulePoll() }
}
function onVisibility() { clearTimeout(timer); if (!document.hidden) poll() }
function converted(task) { return convertedTasks.value.get(task.mailId + ':' + task.suggestion.id) }
function prepareTask(task) {
  selectedTask.value = task; conversionError.value = ''
  Object.assign(draft, { text: task.suggestion.title.slice(0, 300), priority: task.suggestion.priority || 'NORMAL', due: '', notes: (task.suggestion.details || '').slice(0, 2000) })
  confirmVisible.value = true
}
function closeConfirmation(done) { if (!savingTask.value) done() }
async function confirmTask() {
  if (savingTask.value || !selectedTask.value) return
  savingTask.value = true; conversionError.value = ''
  try {
    await convertMailTask({ sourceMailId: selectedTask.value.mailId, sourceSuggestionId: selectedTask.value.suggestion.id,
      text: draft.text.trim(), priority: draft.priority, dueAt: parseDue(draft.due), notes: draft.notes.trim() })
    confirmVisible.value = false; ElMessage.success(t('已加入先记再做'))
  } catch (failure) { conversionError.value = taskError(failure) }
  finally { savingTask.value = false }
}
onMounted(() => { poll(); document.addEventListener('visibilitychange', onVisibility) })
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisibility) })
</script>

<style scoped>
.task-plan { color:#20324e; }.plan-heading { display:flex; align-items:center; justify-content:space-between; gap:24px; margin:12px 0 26px; }.eyebrow { font-size:11px; letter-spacing:2px; font-weight:800; color:#6b88b0; }.plan-heading h2 { font-size:28px; margin:10px 0; letter-spacing:-.6px; }.plan-heading p { font-size:13px; line-height:1.8; color:#728298; margin:0; }button { cursor:pointer; font:inherit; }.analyze-button { flex-shrink:0; border:0; border-radius:14px; background:#286dea; color:white; padding:14px 20px; font-weight:750; }.analyze-button:disabled { opacity:.55; cursor:default; }.plan-stats { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; }.plan-stats>div { padding:24px; background:rgba(255,255,255,.8); border:1px solid white; border-radius:22px; }.plan-stats strong { display:block; font-size:30px; margin-bottom:6px; }.plan-stats span { font-size:13px; color:#77869b; }.plan-progress { margin:22px 0; font-size:13px; color:#557299; }.plan-progress>div { display:flex; justify-content:space-between; gap:12px; }.plan-progress progress { width:100%; height:7px; margin-top:10px; accent-color:#347bed; }.plan-notice { padding:16px 20px; background:#fff4df; color:#8c642b; border-radius:15px; font-size:13px; line-height:1.8; margin-top:18px; }.failed-mail { display:flex; justify-content:space-between; gap:20px; padding-top:10px; }.failed-mail button { border:0; background:transparent; color:#256ad2; white-space:nowrap; }.plan-filters { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:26px 0 18px; }.plan-filters button { border:0; border-radius:11px; color:#6a7c95; background:rgba(255,255,255,.5); padding:10px 17px; font-weight:700; }.plan-filters button.active { background:#243d62; color:#fff; }.plan-filters small { margin-left:auto; color:#8392a7; font-size:11px; }.plan-list { display:grid; gap:18px; }.plan-card { background:rgba(255,255,255,.92); border:1px solid white; border-radius:26px; padding:26px; box-shadow:0 14px 36px rgba(53,75,108,.06); }.plan-card-title { display:flex; gap:18px; align-items:center; }.task-number { font-size:22px; color:#90a5c1; font-weight:800; }.task-badges { display:flex; gap:7px; font-size:10px; color:#788ca7; }.task-badges span { padding:4px 9px; border-radius:8px; background:#f0f4fa; }.task-badges .HIGH { color:#c64346; background:#fff0ee; }.task-badges .NORMAL { color:#3069b3; background:#ecf3ff; }.plan-card h3 { font-size:19px; margin:9px 0 0; line-height:1.5; }.task-description { font-size:14px; line-height:1.9; color:#536782; }.task-deadline { display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; font-size:12px; padding:13px 16px; background:#f4f7fc; border-radius:12px; }.task-deadline>span,.task-deadline small { color:#8a98ab; }.task-deadline strong { overflow-wrap:anywhere; }.task-evidence { color:#788da8; font-size:12px; margin-top:16px; }.task-evidence summary { cursor:pointer; }.task-evidence blockquote { border-left:3px solid #cad9ed; padding:10px 15px; margin:10px 0; color:#5b6d84; line-height:1.8; white-space:pre-wrap; overflow-wrap:anywhere; }.plan-card footer { display:flex; align-items:center; gap:18px; justify-content:space-between; border-top:1px solid #edf1f6; padding-top:16px; margin-top:18px; }.plan-card footer>div { min-width:0; }.plan-card footer span { font-size:10px; color:#8e9db1; }.plan-card footer p { font-size:12px; color:#69819f; margin:5px 0 0; overflow-wrap:anywhere; }.plan-card footer button { border:0; padding:10px 13px; border-radius:11px; background:#edf4ff; color:#276ad5; font-size:12px; font-weight:700; flex-shrink:0; }.plan-empty { text-align:center; padding:60px 20px; color:#8093ad; }.plan-empty>span { display:block; font-size:35px; color:#8eb6eb; }.plan-empty h3 { font-size:18px; }.plan-empty p { font-size:13px; line-height:1.8; }@media(max-width:680px) { .plan-heading { align-items:flex-start; flex-direction:column; }.plan-heading h2 { font-size:23px; }.plan-stats { gap:8px; }.plan-stats>div { padding:16px 12px; }.plan-stats span { font-size:11px; }.plan-card { padding:20px; }.plan-filters small { width:100%; margin:8px 0 0; }.plan-card footer { flex-direction:column; align-items:flex-start; } }
.suggestion-actions{display:flex;gap:8px;flex-wrap:wrap}.plan-card footer .add-task{background:#2872de;color:white}.plan-card footer .add-task:disabled{background:#eaf5ee;color:#589775;cursor:default}.confirm-task-form{display:grid;gap:16px;color:#435a78}.confirm-hint{font-size:12px;line-height:1.8;margin:0;color:#7b8ca4}.confirm-task-form label{display:grid;gap:8px;font-size:12px;font-weight:700}.confirm-task-form input,.confirm-task-form textarea,.confirm-task-form select{width:100%;box-sizing:border-box;border:1px solid #dbe4ef;border-radius:10px;padding:11px;font:inherit;background:white;color:#213958}.confirm-task-form textarea{resize:vertical}.confirm-task-form small{font-weight:400;line-height:1.7;color:#8a98ac}.confirm-task-form input:focus,.confirm-task-form textarea:focus,.confirm-task-form select:focus{outline:2px solid #90b6f2;outline-offset:2px}.confirm-actions{display:flex;justify-content:flex-end;gap:10px}.confirm-actions button{border:0;padding:12px 20px;border-radius:12px}.conversion-error{font-size:12px;color:#b5574d;background:#fff1ec;padding:12px;border-radius:10px}.task-plan button:focus-visible{outline:3px solid #81b3fc;outline-offset:3px}</style>
