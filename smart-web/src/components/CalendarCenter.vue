<template>
  <section class="calendar-center" :aria-label="t('日历与课程中心')">
    <header class="calendar-intro">
      <div><span class="eyebrow">MAKE ROOM FOR WHAT MATTERS</span><h2>{{ t("日历与课程") }}</h2><p>{{ t("课程、考试、面试，还有每一个重要的截止时刻。") }}</p></div>
      <button class="primary add-event" @click="newEvent(selectedDay)"><span>＋</span> {{ t("新建日程") }}</button>
    </header>

    <div class="calendar-toolbar">
      <div class="period-controls"><button :aria-label="t('上一周期')" @click="move(-1)">‹</button><button :aria-label="t('下一周期')" @click="move(1)">›</button><h3>{{ periodLabel }}</h3><button class="today-button" @click="goToday">{{ t("今天") }}</button></div>
      <div class="view-switch" role="group" :aria-label="t('日历视图')"><button :aria-pressed="view === 'week'" :class="{ active: view === 'week' }" @click="view = 'week'">{{ t("周视图") }}</button><button :aria-pressed="view === 'month'" :class="{ active: view === 'month' }" @click="view = 'month'">{{ t("月视图") }}</button></div>
    </div>
    <div class="calendar-filters"><div role="group" :aria-label="t('显示类型')"><button v-for="(meta, kind) in kinds" :key="kind" :class="['filter', kind, { muted: !filters[kind] }]" :aria-pressed="filters[kind]" @click="filters[kind] = !filters[kind]"><i></i>{{ t(meta.label) }}</button></div><span class="display-zone">{{ t("显示时区 ·") }} {{ zone }}</span></div>
    <p v-if="error" class="notice" role="alert">{{ t(error) }} <button @click="loadCalendar()">{{ t("重试") }}</button></p>
    <p v-if="taskLoadError" class="notice" role="alert">{{ t("任务截止时间：") }}{{ t(taskLoadError) }} <button @click="refreshTasks({ force: true })">{{ t("重试任务同步") }}</button></p>
    <p class="sync-status" role="status" aria-live="polite">{{ loading ? t("正在同步日程…") : initialLoaded ? t('当前周期 {events} 个日程 · {deadlines} 个未完成截止事项', { events: occurrences.length, deadlines: deadlines.length }) : t("准备日历…") }}<span v-if="!loading && lastSync">{{ t("上次同步") }} {{ time(lastSync) }}</span></p>

    <div class="calendar-layout">
      <div class="calendar-main panel" :aria-busy="loading">
        <div v-if="view === 'week'" class="week-scroll" tabindex="0" :aria-label="t('本周日程，可横向滚动')">
          <div class="week-grid">
            <section v-for="day in range.days" :key="day" :class="['week-day', { selected: selectedDay === day, 'is-today': today === day }]">
              <button class="week-heading" :aria-label="t('查看 ') + day" :aria-pressed="selectedDay === day" @click="selectedDay = day"><small>{{ weekday(day) }}</small><strong>{{ localDay(day).getDate() }}</strong><span>{{ today === day ? t("今天") : shortMonth(day) }}</span></button>
              <div class="day-items">
                <button v-for="item in byDay[day]" :key="item.key" :class="['event-card', item.kind, { conflict: hasConflict(item) }]" @click="openItem(item)">
                  <span class="card-time">{{ itemTime(item, day) }}</span><strong>{{ item.kind === 'TASK' ? '◆ ' : '' }}{{ item.title }}</strong><span class="card-kind">{{ t(kinds[item.kind]?.label) }}{{ item.recurring ? t(" · 每周") : '' }}</span><span v-if="item.location" class="card-location">{{ item.location }}</span><span v-if="hasConflict(item)" class="conflict-tag">{{ item.kind === 'TASK' ? t("忙碌时截止") : t("时间重叠") }}</span>
                </button>
                <button v-if="!byDay[day].length" class="empty-day" :aria-label="day + t(' 暂无安排，添加日程')" @click="newEvent(day)"><span>＋</span>{{ t("留一点空白") }}<br><small>{{ t("点击安排") }}</small></button>
                <button v-else class="day-add" :aria-label="t('在 ') + day + t(' 添加日程')" @click="newEvent(day)">{{ t("＋ 添加") }}</button>
              </div>
            </section>
          </div>
        </div>
        <div v-else class="month-view">
          <div class="month-weekdays"><span v-for="day in weekdays" :key="day">{{ t(day) }}</span></div>
          <div class="month-grid">
            <section v-for="day in range.days" :key="day" :class="['month-day', { outside: day.slice(0, 7) !== anchor.slice(0, 7), selected: selectedDay === day, 'is-today': today === day }]">
              <div class="month-day-head"><button class="day-number" :aria-label="t('查看 {day}，{count} 项安排', { day, count: byDay[day].length })" :aria-pressed="selectedDay === day" @click="selectedDay = day">{{ localDay(day).getDate() }}</button><button class="month-add" :aria-label="t('在 ') + day + t(' 添加日程')" @click="newEvent(day)">＋</button></div>
              <button v-for="item in byDay[day].slice(0, 3)" :key="item.key" :class="['month-item', item.kind, { conflict: hasConflict(item) }]" :title="item.title + ' · ' + itemTime(item, day)" @click="selectedDay = day; openItem(item)"><span>{{ item.kind === 'TASK' ? '◆' : hasConflict(item) ? '!' : '●' }}</span>{{ item.title }}</button>
              <button v-if="byDay[day].length > 3" class="more-items" @click="selectedDay = day">{{ t("另有") }} {{ byDay[day].length - 3 }} {{ t("项") }}</button>
            </section>
          </div>
        </div>
        <p v-if="initialLoaded && !loading && !occurrences.length && !deadlines.length" class="period-empty">{{ t("这一页还没有安排。选择一天，开始记录下一件重要的事。") }}</p>
        <p class="calendar-footnote">{{ t("◆ 表示任务的截止时刻。跨日安排会显示在经过的每一天。") }}</p>
      </div>

      <aside class="calendar-sidebar">
        <section class="daily-agenda panel" :aria-label="t('所选日期安排')"><div class="aside-heading"><div><span class="eyebrow">YOUR DAY</span><h3>{{ selectedLabel }}</h3><small>{{ weekday(selectedDay) }} · {{ selectedItems.length }} {{ t("项安排") }}</small></div><button :aria-label="t('在 ') + selectedDay + t(' 添加日程')" @click="newEvent(selectedDay)">＋</button></div>
          <div v-if="selectedItems.length" class="agenda-items"><button v-for="item in selectedItems" :key="item.key" :class="['agenda-item', item.kind]" @click="openItem(item)"><i></i><div><time>{{ itemTime(item, selectedDay) }}</time><strong>{{ item.title }}</strong><small>{{ t(kinds[item.kind]?.label) }}{{ item.location ? ' · ' + item.location : '' }}</small><span v-if="hasConflict(item)" class="conflict-tag">{{ item.kind === 'TASK' ? t("截止时仍有日程") : t("有重叠日程") }}</span></div><span class="agenda-arrow">›</span></button></div>
          <div v-else class="agenda-empty"><span>☀</span><h4>{{ t("这一天，留有余地") }}</h4><p>{{ anyFilter ? t("当前显示类型下没有安排。") : t("选择上方类型，查看这一天的安排。") }}</p><button @click="newEvent(selectedDay)">{{ t("安排一件事") }}</button></div>
          <button class="all-tasks" @click="$emit('navigate', 'tasks')">{{ t("管理全部任务，包括未设截止时间的任务 ↗") }}</button>
        </section>

        <section class="conflict-panel panel" :aria-label="t('当前周期时间检查')"><div class="aside-heading"><div><span class="eyebrow">A LITTLE HEADS-UP</span><h3>{{ t("时间检查") }}</h3></div><span :class="['check-count', { clear: !conflictCount }]">{{ conflictCount || '✓' }}</span></div><p class="check-description">{{ t("按当前周期的全部类型检查；首尾相接的日程可正常衔接。") }}</p>
          <template v-if="conflictCount"><div v-for="pair in shownPairs" :key="pair.first.key + ':' + pair.second.key" class="conflict-row"><span class="conflict-caption">{{ fullDate(pair.startAt) }} {{ t("· 日程重叠") }}</span><button @click="openItem(pair.first)">{{ pair.first.title }}</button><span>{{ t("与") }}</span><button @click="openItem(pair.second)">{{ pair.second.title }}</button></div><div v-for="hint in shownDeadlines" :key="hint.deadline.key" class="conflict-row deadline-hint"><span class="conflict-caption">{{ fullDate(hint.deadline.startAt) }} {{ t("· 截止提醒") }}</span><button @click="openItem(hint.deadline)">{{ hint.deadline.title }}</button><p>{{ t('截止时有「{events}」。截止时间是一个时刻，请提前预留完成时间。', { events: hint.events.map(event => event.title).join(', ') }) }}</p></div><button v-if="conflictCount > 6" class="expand-conflicts" @click="expandConflicts = !expandConflicts">{{ expandConflicts ? t("收起提醒") : t("查看全部 ") + conflictCount + t(" 条提醒") }}</button></template>
          <p v-else class="clear-conflicts">{{ initialLoaded ? t("当前周期没有日程重叠，也没有忙碌时的任务截止。") : t("同步后显示时间检查结果。") }}</p>
        </section>
      </aside>
    </div>

    <Teleport to="body">
      <div v-if="modal" class="calendar-modal-backdrop" @mousedown.self="closeModal">
        <section ref="modalElement" class="calendar-modal" role="dialog" aria-modal="true" aria-labelledby="calendar-dialog-title" tabindex="-1" @keydown="modalKeydown">
          <header class="modal-heading"><div><span class="eyebrow">{{ modal === 'event' ? 'MAKE A PLAN' : 'ONE IMPORTANT MOMENT' }}</span><h3 id="calendar-dialog-title">{{ modal === 'task' ? t("任务截止时间") : editing ? t("编辑日程") : t("新建日程") }}</h3></div><button type="button" class="close-modal" :aria-label="t('关闭弹窗')" :disabled="saving" @click="closeModal">×</button></header>
          <form v-if="modal === 'event'" class="event-form" @submit.prevent="saveEvent">
            <p v-if="editing?.recurrence === 'WEEKLY'" class="series-notice wide">{{ t("正在编辑整个每周系列。修改将应用于此系列的全部日期。") }}</p>
            <label class="wide">{{ t("日程名称") }}<input ref="titleInput" v-model="draft.title" required maxlength="300" :placeholder="t('例如：概率论 / 期末考试 / 产品面试')"></label>
            <label>{{ t("类型") }}<select v-model="draft.kind"><option v-for="kind in eventKinds" :key="kind" :value="kind">{{ t(kinds[kind].label) }}</option></select></label>
            <label>{{ t("重复") }}<select v-model="draft.recurrence"><option value="NONE">{{ t("不重复") }}</option><option value="WEEKLY">{{ t("每周重复") }}</option></select></label>
            <div class="time-zone-note wide">{{ t("以下开始和结束时间使用") }} <strong>{{ draft.zone }}</strong> {{ t("时区。") }}{{ draft.zone !== zone ? t("日历将换算为你的当地时区显示。") : '' }}</div>
            <label>{{ t("开始时间") }}<input v-model="draft.startLocal" type="datetime-local" required min="1970-01-01T00:00" max="2099-12-31T23:59"></label>
            <label>{{ t("结束时间") }}<input v-model="draft.endLocal" type="datetime-local" required min="1970-01-01T00:00" max="2099-12-31T23:59"></label>
            <template v-if="draft.recurrence === 'WEEKLY'"><fieldset class="repeat-days wide"><legend>{{ t("每周重复的日期") }}</legend><label v-for="(day, index) in weekdays" :key="day" :class="{ checked: draft.daysOfWeek.includes(index + 1) }"><input v-model="draft.daysOfWeek" type="checkbox" :value="index + 1">{{ t(day) }}</label></fieldset><label class="wide">{{ t("重复至（含当天）") }}<input v-model="draft.repeatUntil" type="date" required :min="draft.startLocal.slice(0, 10)" max="2099-12-31"></label></template>
            <label class="wide">{{ t("地点") }}<input v-model="draft.location" maxlength="300" :placeholder="t('教室、地址或会议链接（可选）')"></label>
            <label class="wide">{{ t("备注") }}<textarea v-model="draft.notes" maxlength="4000" rows="3" :placeholder="t('准备材料、提醒或其他说明（可选）')"></textarea></label>
            <p v-if="modalError" class="notice wide" role="alert">{{ modalErrorMessage }}</p>
            <div class="modal-actions wide"><button v-if="editing" type="button" class="danger" :disabled="saving" @click="removeEvent">{{ editing.recurrence === 'WEEKLY' ? t("删除整个系列") : t("删除日程") }}</button><span></span><button type="button" :disabled="saving" @click="closeModal">{{ t("取消") }}</button><button class="primary" :disabled="saving">{{ saving ? t("保存中…") : editing?.recurrence === 'WEEKLY' ? t("保存整个系列") : t("保存日程") }}</button></div>
          </form>
          <form v-else class="task-deadline-form" @submit.prevent="saveDeadline"><h4>{{ taskEditing?.text }}</h4><p v-if="taskEditing?.notes" class="task-notes">{{ taskEditing.notes }}</p><p class="time-zone-note">{{ t("截止时间是一个时刻，不占用日历时段。时区：") }}{{ zone }}</p><label>{{ t("截止时间") }}<input ref="deadlineInput" v-model="taskDue" type="datetime-local" min="1970-01-01T00:00" max="2099-12-31T23:59"></label><small>{{ t("清空后，任务仍保留在任务中心。") }}</small><p v-if="modalError" class="notice" role="alert">{{ modalErrorMessage }}</p><div class="task-links"><button v-if="taskEditing?.sourceMailId" type="button" @click="openSourceMail">{{ t("查看来源邮件 ↗") }}</button><button type="button" @click="openTaskCenter">{{ t("打开任务中心 ↗") }}</button></div><div class="modal-actions"><button type="button" :disabled="saving" @click="finishTask">{{ t("标记完成") }}</button><span></span><button type="button" :disabled="saving" @click="closeModal">{{ t("取消") }}</button><button class="primary" :disabled="saving">{{ saving ? t("保存中…") : t("保存截止时间") }}</button></div></form>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'

import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskStore, editTask, completeTask, dateInput, parseDue, taskError } from '../stores/taskStore'
import { addDays, calendarRange, dayKey, deadlineItems, findConflicts, itemsOnDay, localDay, shiftPeriod } from '../utils/calendar'

const { t, dateLocale } = useI18n()
const emit = defineEmits(['navigate', 'open-mail'])
const { tasks, error: taskLoadError, refresh: refreshTasks } = useTaskStore()
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const kinds = { COURSE: { label: '课程' }, EXAM: { label: '考试' }, INTERVIEW: { label: '面试' }, PERSONAL: { label: '个人安排' }, TASK: { label: '任务截止' } }
const eventKinds = ['COURSE', 'EXAM', 'INTERVIEW', 'PERSONAL']
const filters = reactive({ COURSE: true, EXAM: true, INTERVIEW: true, PERSONAL: true, TASK: true })
const view = ref('week'), today = ref(dayKey()), anchor = ref(dayKey()), selectedDay = ref(dayKey())
const occurrences = ref([]), definitions = ref([]), loading = ref(false), initialLoaded = ref(false), error = ref(''), lastSync = ref(null)
const expandConflicts = ref(false), modal = ref(null), editing = ref(null), taskEditing = ref(null), taskDue = ref(''), saving = ref(false), modalError = ref('')
const modalConflict = ref(false)
const modalErrorMessage = computed(() => t(modalError.value) + (modalConflict.value ? t('。请关闭并重新打开最新日程后修改。') : ''))
const modalElement = ref(null), titleInput = ref(null), deadlineInput = ref(null)
const draft = reactive({ title: '', kind: 'COURSE', startLocal: '', endLocal: '', zone, recurrence: 'NONE', daysOfWeek: [], repeatUntil: '', location: '', notes: '' })
let mounted = false, timer, controller, loadSequence = 0, returnFocus
const range = computed(() => calendarRange(anchor.value, view.value))
const deadlines = computed(() => deadlineItems(tasks.value, range.value))
const filteredEvents = computed(() => occurrences.value.filter(event => filters[event.kind]))
const filteredDeadlines = computed(() => filters.TASK ? deadlines.value : [])
const byDay = computed(() => Object.fromEntries(range.value.days.map(day => [day, itemsOnDay(filteredEvents.value, filteredDeadlines.value, day)])))
const selectedItems = computed(() => byDay.value[selectedDay.value] || [])
const conflicts = computed(() => findConflicts(occurrences.value, deadlines.value, range.value))
const conflictCount = computed(() => conflicts.value.pairs.length + conflicts.value.busyDeadlines.length)
const shownPairs = computed(() => expandConflicts.value ? conflicts.value.pairs : conflicts.value.pairs.slice(0, 6))
const shownDeadlines = computed(() => expandConflicts.value ? conflicts.value.busyDeadlines : conflicts.value.busyDeadlines.slice(0, Math.max(0, 6 - shownPairs.value.length)))
const anyFilter = computed(() => Object.values(filters).some(Boolean))
const selectedLabel = computed(() => localDay(selectedDay.value).toLocaleDateString(dateLocale.value, { month: 'long', day: 'numeric' }))
const periodLabel = computed(() => view.value === 'month' ? localDay(anchor.value).toLocaleDateString(dateLocale.value, { year: 'numeric', month: 'long' }) : `${shortDate(range.value.from)} — ${shortDate(addDays(range.value.to, -1))}`)
function shortDate(day) { return localDay(day).toLocaleDateString(dateLocale.value, { month: 'numeric', day: 'numeric' }) }
function shortMonth(day) { return localDay(day).toLocaleDateString(dateLocale.value, { month: 'short' }) }
function weekday(day) { return t(weekdays[(localDay(day).getDay() + 6) % 7]) }
function time(stamp) { return new Date(stamp).toLocaleTimeString(dateLocale.value, { hour: '2-digit', minute: '2-digit', hour12: false }) }
function fullDate(stamp) { return new Date(stamp).toLocaleString(dateLocale.value, { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) }
function itemTime(item, day) {
  if (item.kind === 'TASK') return time(item.startAt) + t(" 截止")
  const start = dayKey(item.startAt), end = dayKey(item.endAt)
  const endDate = new Date(item.endAt)
  const endsAtMidnight = endDate.getHours() === 0 && endDate.getMinutes() === 0
  return (start < day ? t("承接前日") : time(item.startAt)) + ' – ' + (end > day ? (endsAtMidnight && end === addDays(day, 1) ? '24:00' : t("次日续")) : time(item.endAt))
}
function hasConflict(item) { return item.kind === 'TASK' ? conflicts.value.taskKeys.has(item.key) : conflicts.value.eventKeys.has(item.key) }
function move(amount) { anchor.value = shiftPeriod(anchor.value, view.value, amount); selectedDay.value = anchor.value }
function goToday() { today.value = dayKey(); anchor.value = today.value; selectedDay.value = today.value }

async function loadCalendar({ background = false } = {}) {
  if (!mounted || (background && (document.hidden || loading.value || saving.value))) return
  controller?.abort()
  const request = new AbortController(), sequence = ++loadSequence, requestedRange = range.value
  controller = request; loading.value = true
  try {
    const { data } = await axios.get('/api/calendar', { params: { from: requestedRange.from, to: requestedRange.to, zone }, signal: request.signal, timeout: 15000 })
    if (!mounted || sequence !== loadSequence) return
    definitions.value = Array.isArray(data.events) ? data.events : []
    occurrences.value = Array.isArray(data.occurrences) ? data.occurrences : []
    initialLoaded.value = true; error.value = ''; lastSync.value = Date.now(); today.value = dayKey()
  } catch (failure) {
    if (mounted && sequence === loadSequence && !request.signal.aborted) error.value = taskError(failure) || "日历同步失败，请重试"
  } finally { if (mounted && sequence === loadSequence) loading.value = false }
}
function refreshVisible() { if (!document.hidden) loadCalendar({ background: true }) }
watch(() => range.value.from + ':' + range.value.to, () => {
  if (selectedDay.value < range.value.from || selectedDay.value >= range.value.to) selectedDay.value = anchor.value
  occurrences.value = []; definitions.value = []; initialLoaded.value = false; lastSync.value = null; expandConflicts.value = false
  loadCalendar()
})
onMounted(() => {
  mounted = true; loadCalendar(); timer = setInterval(refreshVisible, 30000)
  document.addEventListener('visibilitychange', refreshVisible); window.addEventListener('focus', refreshVisible)
})
onBeforeUnmount(() => {
  mounted = false; loadSequence++; controller?.abort(); clearInterval(timer)
  document.removeEventListener('visibilitychange', refreshVisible); window.removeEventListener('focus', refreshVisible)
})

async function showModal(type) {
  returnFocus = document.activeElement; modalError.value = ''; modalConflict.value = false; modal.value = type
  await nextTick(); (type === 'event' ? titleInput.value : deadlineInput.value)?.focus()
}
function newEvent(day) {
  editing.value = null; selectedDay.value = day
  Object.assign(draft, { title: '', kind: 'COURSE', startLocal: day + 'T09:00', endLocal: day + 'T10:00', zone, recurrence: 'NONE', daysOfWeek: [(localDay(day).getDay() + 6) % 7 + 1], repeatUntil: addDays(day, 84), location: '', notes: '' })
  showModal('event')
}
function openItem(item) {
  if (item.kind === 'TASK') {
    const task = tasks.value.find(row => row.id === item.task.id)
    if (!task || task.status === 'COMPLETED') { ElMessage.info(t("这条任务已完成或已移除")); return }
    taskEditing.value = { ...task }; taskDue.value = dateInput(task.dueAt); showModal('task'); return
  }
  const event = definitions.value.find(row => row.id === item.eventId)
  if (!event) { ElMessage.warning(t("日程已更新，请同步后重试")); loadCalendar(); return }
  editing.value = { ...event }
  Object.assign(draft, { title: event.title, kind: event.kind, startLocal: event.startLocal.slice(0, 16), endLocal: event.endLocal.slice(0, 16), zone: event.zone, recurrence: event.recurrence, daysOfWeek: [...(event.daysOfWeek || [])], repeatUntil: event.repeatUntil || '', location: event.location || '', notes: event.notes || '' })
  showModal('event')
}
function closeModal() { if (saving.value) return; modal.value = null; nextTick(() => returnFocus?.isConnected && returnFocus.focus()) }
function modalKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); closeModal() }
  if (event.key !== 'Tab') return
  const elements = [...modalElement.value.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')]
  const first = elements[0], last = elements.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function eventLocalValue(field) {
  const original = editing.value?.[field]
  // The controls edit minutes. Retain imported seconds/fractions when a field
  // has not changed, so a title-only edit does not move the original event.
  return original && draft[field] === original.slice(0, 16) ? original : draft[field]
}
function localTimeOrder(value) {
  return value.slice(0, 16) + ':' + (value.slice(17, 19) || '00') + '.' + (value.split('.')[1] || '').padEnd(9, '0')
}
async function saveEvent() {
  if (saving.value) return
  modalError.value = ''; modalConflict.value = false
  const startLocal = eventLocalValue('startLocal'), endLocal = eventLocalValue('endLocal')
  if (!draft.title.trim()) { modalError.value = "请填写日程名称"; return }
  if (!startLocal || !endLocal || localTimeOrder(endLocal) <= localTimeOrder(startLocal)) { modalError.value = "结束时间必须晚于开始时间；跨日安排请选择下一天的日期"; return }
  if (draft.recurrence === 'WEEKLY' && (!draft.daysOfWeek.length || !draft.repeatUntil || draft.repeatUntil < draft.startLocal.slice(0, 10))) { modalError.value = "请选择至少一个重复日期，并设置不早于开始日期的结束日期"; return }
  saving.value = true
  const input = { ...draft, startLocal, endLocal, title: draft.title.trim(), location: draft.location.trim(), notes: draft.notes.trim(), daysOfWeek: draft.recurrence === 'WEEKLY' ? [...draft.daysOfWeek].sort((a, b) => a - b) : [], repeatUntil: draft.recurrence === 'WEEKLY' ? draft.repeatUntil : null }
  try {
    if (editing.value) await axios.put('/api/calendar/events/' + encodeURIComponent(editing.value.id), { ...input, version: editing.value.version }, { timeout: 15000 })
    else await axios.post('/api/calendar/events', input, { timeout: 15000 })
    if (!mounted) return
    saving.value = false; closeModal(); ElMessage.success(t("日程已保存")); await loadCalendar()
  } catch (failure) { if (mounted) { modalError.value = taskError(failure); modalConflict.value = failure.response?.status === 409; await loadCalendar() } }
  finally { saving.value = false }
}
async function removeEvent() {
  if (!editing.value || saving.value) return
  const event = { ...editing.value }
  try { await ElMessageBox.confirm(event.recurrence === 'WEEKLY' ? t('这会删除「{title}」整个重复系列，包含所有日期。确定删除？', { title: event.title }) : t('确定删除「{title}」？', { title: event.title }), event.recurrence === 'WEEKLY' ? t("删除整个系列") : t("删除日程"), { confirmButtonText: t("确认删除"), cancelButtonText: t("保留"), type: 'warning' }) }
  catch { return }
  if (!mounted) return
  saving.value = true; modalError.value = ''; modalConflict.value = false
  try {
    await axios.delete('/api/calendar/events/' + encodeURIComponent(event.id), { params: { version: event.version }, timeout: 15000 })
    if (!mounted) return
    saving.value = false; closeModal(); ElMessage.success(t("日程已删除")); await loadCalendar()
  } catch (failure) { if (mounted) { modalError.value = taskError(failure); await loadCalendar() } }
  finally { saving.value = false }
}
async function saveDeadline() {
  if (saving.value) return
  saving.value = true; modalError.value = ''; modalConflict.value = false
  try { await editTask(taskEditing.value, { text: taskEditing.value.text, notes: taskEditing.value.notes || '', priority: taskEditing.value.priority || 'NORMAL', dueAt: parseDue(taskDue.value) }); if (mounted) { saving.value = false; closeModal(); ElMessage.success(t("截止时间已更新")) } }
  catch (failure) { if (mounted) { modalError.value = taskError(failure); await refreshTasks({ force: true }) } }
  finally { saving.value = false }
}
async function finishTask() {
  if (saving.value) return
  saving.value = true; modalError.value = ''; modalConflict.value = false
  try { await completeTask(taskEditing.value, true); if (mounted) { saving.value = false; closeModal(); ElMessage.success(t("已完成，记录保留在任务中心")) } }
  catch (failure) { if (mounted) { modalError.value = taskError(failure); await refreshTasks({ force: true }) } }
  finally { saving.value = false }
}
function openSourceMail() { const id = taskEditing.value?.sourceMailId; closeModal(); if (id) emit('open-mail', { id }) }
function openTaskCenter() { closeModal(); emit('navigate', 'tasks') }
</script>

<style scoped>
.calendar-center{max-width:1440px;margin:auto;color:#253c5b}.calendar-intro{display:flex;align-items:center;justify-content:space-between;gap:24px;margin:8px 0 28px}.eyebrow{font-size:10px;letter-spacing:1.8px;color:#8093ac;font-weight:800}.calendar-intro h2{font-size:34px;letter-spacing:-.9px;margin:11px 0}.calendar-intro p{font-size:13px;line-height:1.8;color:#7b8fa7;margin:0}button{border:0;font:inherit;cursor:pointer;color:#557292;background:#edf3fa;border-radius:11px;padding:10px 13px;font-size:12px;font-weight:650;transition:background .15s,box-shadow .15s}button:hover{background:#e1ecfa}button:focus-visible{outline:3px solid #88b4f5;outline-offset:2px}button:disabled{opacity:.5;cursor:wait}.primary{background:#387ced;color:#fff;box-shadow:0 6px 16px #4388e525}.primary:hover{background:#256bdc}.add-event{padding:13px 20px;white-space:nowrap}.add-event>span{font-size:18px;margin-right:5px}.calendar-toolbar{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:17px}.period-controls{display:flex;align-items:center;gap:7px}.period-controls>button:not(.today-button){font-size:24px;background:#ffffffa8;width:34px;height:34px;padding:0}.period-controls h3{font-size:20px;letter-spacing:-.4px;margin:0 13px 0 9px}.today-button{background:#fff}.view-switch{display:flex;background:#dce6f24f;border:1px solid #d8e3ef;padding:4px;border-radius:12px;gap:3px}.view-switch button{background:transparent;padding:7px 17px}.view-switch .active{background:#fff;color:#326fcd;box-shadow:0 3px 8px #30496b0c}.calendar-filters{display:flex;justify-content:space-between;gap:14px;align-items:center;flex-wrap:wrap}.calendar-filters>div{display:flex;gap:8px;flex-wrap:wrap}.filter{display:flex;gap:7px;align-items:center;background:#ffffffa8;font-size:11px;padding:8px 11px}.filter i,.agenda-item i{width:7px;height:7px;display:block;border-radius:50%;background:var(--event-accent)}.filter.muted{opacity:.4;background:transparent;text-decoration:line-through}.display-zone{font-size:10px;color:#7a8ca3}.sync-status{display:flex;gap:10px;justify-content:space-between;font-size:10px;color:#8c9bb0;min-height:15px;margin:13px 2px}.calendar-layout{display:grid;grid-template-columns:minmax(0,1fr) 280px;gap:20px;align-items:start}.panel{background:#ffffffd4;border:1px solid #fff;border-radius:25px;box-shadow:0 12px 35px #3b587509}.calendar-main{overflow:hidden}.calendar-sidebar{display:grid;gap:18px}.COURSE{--event-bg:#eaf2ff;--event-accent:#588bd9;--event-ink:#315c9d}.EXAM{--event-bg:#fff0e8;--event-accent:#df9c64;--event-ink:#a96a3c}.INTERVIEW{--event-bg:#f1eefe;--event-accent:#9b86d5;--event-ink:#7862b0}.PERSONAL{--event-bg:#eaf6f1;--event-accent:#72a58f;--event-ink:#4d826d}.TASK{--event-bg:#fff8e4;--event-accent:#c4a251;--event-ink:#978042}.week-scroll{overflow:auto;scrollbar-width:thin;scrollbar-color:#c5d3e3 transparent}.week-grid{display:grid;grid-template-columns:repeat(7,minmax(112px,1fr));min-height:485px;min-width:784px}.week-day{min-width:0;border-right:1px solid #ecf1f7}.week-day:last-child{border-right:0}.week-heading{width:100%;display:grid;justify-items:center;gap:6px;border-radius:0;background:transparent;padding:20px 5px 15px;border-bottom:1px solid #edf2f8}.week-heading small{font-size:10px;color:#8494a8}.week-heading strong{display:grid;place-items:center;font-size:22px;height:38px;width:38px;line-height:1;border-radius:14px}.week-heading>span{font-size:9px;color:#a0adc0}.is-today .week-heading strong,.is-today .day-number{background:#3b7de7;color:white}.is-today .week-heading>span{color:#4b84d6}.week-day.selected .week-heading{background:#f3f7fd}.day-items{padding:9px 7px;display:grid;gap:9px}.event-card{text-align:left;padding:10px 9px;display:grid;gap:7px;width:100%;border-left:3px solid var(--event-accent);background:var(--event-bg);color:var(--event-ink);border-radius:11px}.event-card:hover,.month-item:hover{filter:brightness(.97);background:var(--event-bg)}.event-card .card-time{font-size:9px;letter-spacing:-.2px;font-weight:600}.event-card strong{font-size:12px;line-height:1.65;overflow-wrap:anywhere}.card-kind{font-size:9px;opacity:.76}.card-location{font-size:10px;font-weight:400;line-height:1.5;overflow-wrap:anywhere}.conflict-tag{font-size:9px;background:#fff0e8;color:#b36b50;border-radius:5px;padding:3px 5px;width:fit-content;font-weight:600}.event-card.conflict{box-shadow:inset 0 0 0 1px #dcb29480}.event-card.TASK{border-left-style:dotted}.empty-day{background:transparent;color:#a7b3c3;font-size:10px;font-weight:400;line-height:1.9;width:100%;padding:40px 0}.empty-day>span{display:block;font-size:20px;margin-bottom:3px;color:#c1cdde}.empty-day small{font-size:9px;color:#aab7c8}.day-add{font-size:10px;background:transparent;color:#92a5be;padding:8px}.calendar-footnote{font-size:10px;color:#97a6b8;padding:15px 20px;border-top:1px solid #edf2f8;margin:0;line-height:1.8}.period-empty{font-size:12px;line-height:1.8;text-align:center;color:#8394ab;padding:10px 20px}.month-weekdays{display:grid;grid-template-columns:repeat(7,1fr);padding:17px 0;color:#8b9bb0;font-size:10px;text-align:center;border-bottom:1px solid #eaf0f7}.month-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr))}.month-day{min-height:117px;min-width:0;border-bottom:1px solid #edf2f8;border-right:1px solid #edf2f8;padding:6px}.month-day:nth-child(7n){border-right:0}.month-day:nth-last-child(-n+7){border-bottom:0}.month-day.outside{background:#f8fafc}.month-day.outside .day-number{color:#b2bfd0}.month-day.selected{background:#f0f6ff;box-shadow:inset 0 0 0 1px #bdd4f3}.month-day-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:4px}.day-number{width:27px;height:27px;padding:0;background:transparent;border-radius:9px;font-size:11px}.month-add{background:transparent;color:#a6b5c9;padding:0;width:22px;height:24px;font-size:14px}.month-item{display:block;width:100%;background:var(--event-bg);color:var(--event-ink);border-radius:5px;padding:4px;text-align:left;font-size:9px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-bottom:3px;font-weight:550}.month-item>span{font-size:7px;margin-right:4px;color:var(--event-accent)}.month-item.conflict>span{color:#b86c49}.more-items{padding:2px 4px;background:transparent;font-size:9px;color:#8799b0}.daily-agenda,.conflict-panel{padding:22px 20px}.aside-heading{display:flex;justify-content:space-between;align-items:center;gap:8px}.aside-heading h3{font-size:20px;margin:8px 0 6px;letter-spacing:-.4px}.aside-heading small{font-size:10px;color:#8c9bb0}.aside-heading>button{background:#edf4ff;color:#5b8fda;font-size:20px;padding:5px 10px;border-radius:12px}.agenda-items{margin-top:19px;display:grid}.agenda-item{display:flex;gap:10px;align-items:flex-start;padding:14px 0;background:transparent;border-radius:0;text-align:left;border-top:1px solid #edf2f8;width:100%}.agenda-item i{margin-top:5px;flex:none}.agenda-item>div{display:grid;gap:7px;min-width:0;flex:1}.agenda-item time{font-size:10px;color:#8a9bb0;font-weight:500}.agenda-item strong{font-size:13px;line-height:1.6;overflow-wrap:anywhere;color:#39516e}.agenda-item small{font-size:10px;color:#91a0b3;line-height:1.7;overflow-wrap:anywhere}.agenda-arrow{color:#b8c5d6;font-size:20px;margin-top:15px}.agenda-empty{padding:29px 3px;text-align:center}.agenda-empty>span{font-size:28px;color:#b8cdea}.agenda-empty h4{font-size:13px;font-weight:650;color:#687e9c;margin:13px 0 7px}.agenda-empty p{font-size:11px;color:#94a4ba;line-height:1.8}.agenda-empty button{font-size:10px;margin-top:5px}.all-tasks{font-size:10px;text-align:left;line-height:1.8;background:#f4f7fc;color:#6e8aaa;padding:11px 13px;width:100%;margin-top:16px}.conflict-panel .aside-heading h3{font-size:18px}.check-count{font-size:12px;background:#fff0e6;color:#b58159;border-radius:10px;padding:8px 11px}.check-count.clear{background:#ecf6f0;color:#78a48d}.check-description,.clear-conflicts{font-size:11px;line-height:1.9;color:#90a0b6;margin:15px 0 0}.clear-conflicts{color:#7e9b8e}.conflict-row{font-size:11px;margin-top:15px;border-top:1px solid #edf2f8;padding-top:14px;line-height:1.8}.conflict-caption{display:block;font-size:9px;color:#b68765;margin-bottom:5px}.conflict-row button{display:inline;text-align:left;padding:2px 0;font-size:11px;line-height:1.8;background:transparent;color:#6683a8;overflow-wrap:anywhere}.conflict-row>span:not(.conflict-caption){color:#a3adbb;margin:0 6px;font-size:10px}.conflict-row p{font-size:10px;color:#96a3b6;line-height:1.9;margin:5px 0 0;overflow-wrap:anywhere}.expand-conflicts{width:100%;margin-top:16px;font-size:10px}.notice{padding:12px 15px;background:#fff4e2;color:#a67b41;border-radius:12px;font-size:12px;line-height:1.7}.notice button{background:#fff7e9;color:#9f773f;margin-left:8px;padding:3px 7px;font-size:11px}.calendar-modal-backdrop{position:fixed;inset:0;z-index:1000;background:#20345359;backdrop-filter:blur(6px);display:flex;align-items:center;justify-content:center;padding:26px;font-family:inherit}.calendar-modal{background:#f9fbff;color:#2d4463;border:1px solid #fff;border-radius:26px;box-shadow:0 30px 100px #243b6940;padding:28px;width:620px;max-width:100%;max-height:calc(100dvh - 52px);overflow:auto;outline:none}.modal-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:22px}.modal-heading h3{font-size:24px;margin:8px 0 0}.close-modal{background:#edf2f8;font-size:23px;padding:3px 12px}.event-form{display:grid;grid-template-columns:1fr 1fr;gap:16px}.wide{grid-column:1/-1}.event-form label,.task-deadline-form label{display:grid;gap:8px;font-size:11px;color:#7d8ea5;font-weight:700}.calendar-modal input,.calendar-modal select,.calendar-modal textarea{width:100%;box-sizing:border-box;border:1px solid #dce5f1;background:#fff;color:#38516f;border-radius:11px;padding:11px;font:inherit;min-width:0;font-size:13px}.calendar-modal textarea{resize:vertical;line-height:1.7}.calendar-modal input:focus,.calendar-modal select:focus,.calendar-modal textarea:focus{outline:2px solid #9cc4fd;outline-offset:2px}.time-zone-note{font-size:11px;line-height:1.8;background:#edf3fb;color:#7e93ac;border-radius:9px;padding:10px 12px}.series-notice{font-size:12px;color:#8c78b3;background:#f0edfa;border-radius:10px;padding:12px 14px;line-height:1.8;margin:0}.repeat-days{border:0;padding:0;margin:0;display:flex;gap:7px;flex-wrap:wrap}.repeat-days legend{font-size:11px;font-weight:700;color:#7d8ea5;margin-bottom:9px}.repeat-days label{display:flex;gap:5px;align-items:center;padding:9px;border:1px solid #e2eaf5;border-radius:9px;background:white;font-size:11px;cursor:pointer}.repeat-days label.checked{background:#eaf2ff;border-color:#b8d0f5;color:#5483c4}.repeat-days input{width:12px;height:12px;accent-color:#4a86e7}.modal-actions{display:flex;gap:8px;align-items:center;margin-top:6px}.modal-actions>span{flex:1}.modal-actions button{white-space:nowrap}.danger{color:#b76e67;background:#fbefed}.task-deadline-form h4{font-size:18px;line-height:1.7;overflow-wrap:anywhere;margin:0 0 15px}.task-notes{font-size:12px;line-height:1.9;color:#8a9bb1;white-space:pre-wrap;overflow-wrap:anywhere}.task-deadline-form>small{display:block;font-size:11px;color:#98a6b9;margin:9px 0 0}.task-links{display:flex;gap:10px;margin:15px 0 24px}.task-links button{font-size:11px;background:transparent;padding:6px 0;color:#5588cd}
@media(min-width:1500px){.calendar-layout{grid-template-columns:minmax(0,1fr) 310px}.week-grid{grid-template-columns:repeat(7,minmax(125px,1fr))}.event-card{padding:12px 10px}.month-day{min-height:133px}.month-item{font-size:10px;padding:5px}}
@media(max-width:1100px){.calendar-layout{grid-template-columns:minmax(0,1fr)}.calendar-sidebar{grid-template-columns:1fr 1fr;align-items:start}.week-grid{min-width:784px}.calendar-center{max-width:100%}.month-day{min-height:125px}}
@media(max-width:650px){.calendar-intro{align-items:flex-start;margin-bottom:24px;gap:12px}.calendar-intro h2{font-size:27px}.calendar-intro p{font-size:11px;max-width:240px}.calendar-intro .eyebrow{font-size:8px;letter-spacing:1px}.add-event{font-size:11px;padding:10px 12px;margin-top:25px}.add-event>span{font-size:14px;margin-right:2px}.calendar-toolbar{flex-wrap:wrap;gap:13px}.period-controls{gap:4px}.period-controls h3{font-size:17px;margin:0 6px}.today-button{font-size:10px;padding:9px}.view-switch{margin-left:auto}.view-switch button{font-size:11px;padding:6px 14px}.calendar-filters{gap:11px}.calendar-filters>div{gap:5px}.filter{font-size:10px;padding:7px 8px;gap:4px}.display-zone{font-size:9px}.sync-status{font-size:9px;flex-wrap:wrap}.calendar-sidebar{grid-template-columns:1fr}.panel{border-radius:20px}.month-day{min-height:106px;padding:3px}.month-weekdays{font-size:9px;padding:13px 0}.month-item{font-size:8px;padding:3px 2px;border-radius:4px}.month-item>span{margin-right:2px;font-size:6px}.day-number{font-size:10px;width:23px;height:23px}.month-add{font-size:12px;width:17px}.more-items{font-size:8px;padding:2px}.calendar-footnote{font-size:9px;padding:12px}.calendar-modal-backdrop{padding:14px}.calendar-modal{padding:22px 18px;max-height:calc(100dvh - 28px);border-radius:22px}.event-form{gap:14px;grid-template-columns:1fr}.event-form>label{grid-column:1}.repeat-days{gap:5px}.repeat-days label{font-size:10px;padding:8px 6px}.modal-actions{flex-wrap:wrap}.modal-actions button{font-size:11px;padding:9px 11px}.modal-actions .danger{margin-right:auto}.modal-actions>span{display:none}.task-deadline-form .modal-actions>span{display:block}.modal-heading h3{font-size:22px}}
</style>
