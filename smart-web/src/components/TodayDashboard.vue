<template>
  <section class="today-dashboard" :aria-label="t('今日概览')">
    <header><div><span class="eyebrow">TODAY · YOUR NEXT STEP</span><h2>{{ t("把今天，安排得清楚一点。") }}</h2><p>{{ t("按你的当地时间整理截止日期，完成的事项会保留在任务历史中。") }}</p></div><div class="today-actions"><button @click="$emit('navigate', 'calendar')">{{ t("打开日历 ▦") }}</button><button class="primary" @click="$emit('navigate', 'tasks')">{{ t("管理全部任务 ↗") }}</button></div></header>
    <div class="today-stats"><div><strong>{{ groups.today.length }}</strong><span>{{ t("今天剩余待办") }}</span></div><div><strong>{{ groups.overdue.length }}</strong><span>{{ t("已经逾期") }}</span></div><div><strong>{{ summary.completed }}</strong><span>{{ t("累计完成") }}</span></div></div>
    <p v-if="error" class="notice" role="alert">{{ t(error) }} <button @click="refresh({ force: true })">{{ t("重新连接") }}</button></p>
    <div class="today-sections">
      <section v-for="section in sections" :key="section.key" :class="['today-section', section.key]">
        <div class="section-heading"><div><span>{{ section.eyebrow }}</span><h3>{{ t(section.label) }}</h3></div><strong>{{ groups[section.key].length }}</strong></div>
        <ul v-if="groups[section.key].length">
          <li v-for="task in groups[section.key].slice(0, expanded[section.key] ? undefined : 6)" :key="task.id">
            <button class="complete" :disabled="pending.has(task.id)" :aria-label="t('完成任务：') + task.text" @click="finish(task)">○</button>
            <div><div class="task-meta"><span :class="task.priority">{{ t(priorities[task.priority] || priorities.NORMAL) }}</span><time v-if="task.dueAt != null">{{ localTaskDate(task.dueAt) }}</time></div><h4>{{ task.text }}</h4><p v-if="task.notes">{{ task.notes }}</p><button v-if="task.sourceMailId" class="mail-source" @click="$emit('open-mail', { id: task.sourceMailId })">{{ t("查看来源邮件 ›") }}</button></div>
          </li>
        </ul>
        <p v-else class="empty">{{ t(section.empty) }}</p>
        <button v-if="groups[section.key].length > 6" class="expand" @click="expanded[section.key] = !expanded[section.key]">{{ expanded[section.key] ? t("收起") : t("展开全部 ") + groups[section.key].length + t(" 条") }}</button>
      </section>
    </div>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'

import { computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useTaskStore, priorities, completeTask, taskError } from '../stores/taskStore'
import { groupOpenTasks } from '../stores/taskQueries'
const { t, dateLocale } = useI18n()
function localTaskDate(value) { return value == null ? t('未设置截止时间') : new Date(value).toLocaleString(dateLocale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
defineEmits(['open-mail', 'navigate'])
const { tasks, summary, now, error, refresh } = useTaskStore()
const pending = reactive(new Set()), expanded = reactive({})
const groups = computed(() => groupOpenTasks(tasks.value, now.value))
const sections = [
  { key: 'overdue', label: '已逾期', eyebrow: 'NEEDS ATTENTION', empty: '没有逾期的任务。' },
  { key: 'today', label: '今天接下来', eyebrow: 'THE REST OF TODAY', empty: '今天接下来没有已安排的截止事项。' },
  { key: 'upcoming', label: '即将到来', eyebrow: 'COMING UP', empty: '还没有未来的截止事项。' },
  { key: 'unscheduled', label: '待安排', eyebrow: 'AT YOUR OWN PACE', empty: '所有待办都已安排时间。' }
]
async function finish(task) {
  if (pending.has(task.id)) return
  pending.add(task.id)
  try { await completeTask(task, true); ElMessage.success(t("已完成，保留在任务历史中")) }
  catch (failure) { ElMessage.error(t(taskError(failure))); await refresh({ force: true }) }
  finally { pending.delete(task.id) }
}
</script>
<style scoped>
.today-actions{display:flex;gap:10px;flex-wrap:wrap}
.today-dashboard{max-width:1200px;margin:auto;color:#20324e}header{display:flex;align-items:center;justify-content:space-between;gap:24px;margin:8px 0 26px}.eyebrow{font-size:11px;letter-spacing:2px;font-weight:800;color:#7990af}h2{font-size:32px;letter-spacing:-.8px;margin:12px 0}header p{font-size:13px;line-height:1.8;color:#7b8ca3}button{font:inherit;cursor:pointer;border:0;border-radius:12px;padding:10px 15px;color:#316bc0;background:#edf4ff;font-size:12px;font-weight:700}button:disabled{opacity:.45;cursor:wait}button:focus-visible{outline:3px solid #81b3fc;outline-offset:3px}.primary{background:#3274e4;color:white;white-space:nowrap}.today-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:24px}.today-stats>div{padding:24px;border-radius:24px;background:#ffffffb5;border:1px solid white}.today-stats strong{display:block;font-size:32px;margin-bottom:5px}.today-stats span{color:#7b8ea7;font-size:12px}.today-sections{display:grid;grid-template-columns:1fr 1fr;gap:22px;align-items:start}.today-section{border-radius:27px;padding:25px;background:#ffffffdb;border:1px solid white;box-shadow:0 16px 40px #29456909}.section-heading{display:flex;justify-content:space-between;align-items:center}.section-heading span{font-size:10px;letter-spacing:1.5px;color:#8a9bb1;font-weight:750}.section-heading h3{font-size:22px;margin:7px 0 20px}.section-heading>strong{background:#eef4ff;padding:11px 15px;border-radius:15px;color:#4f7dbd}.overdue .section-heading>strong{background:#fff0eb;color:#b5634d}ul{list-style:none;margin:0;padding:0}li{display:grid;grid-template-columns:28px 1fr;gap:13px;padding:18px 0;border-top:1px solid #eaf0f6}li>div{min-width:0}.complete{padding:0;border:1px solid #c4d4e7;background:#fff;width:28px;height:28px;border-radius:50%;font-size:20px}.task-meta{display:flex;flex-wrap:wrap;gap:8px;align-items:center;font-size:10px;color:#8497af}.task-meta span{background:#edf3fb;padding:4px 7px;border-radius:6px}.task-meta .HIGH{color:#b15a4b;background:#ffede8}.task-meta .LOW{color:#5a8a71;background:#edf8f2}h4{font-size:15px;line-height:1.65;margin:7px 0;overflow-wrap:anywhere}li p{font-size:12px;color:#7b8da5;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere;margin:7px 0}.mail-source{background:transparent;padding:5px 0}.empty{font-size:13px;color:#8b9aaf;padding:12px 0;line-height:1.7}.expand{width:100%;margin-top:10px;background:#f2f6fc}.notice{padding:15px;border-radius:12px;background:#fff4dc;color:#a17637;font-size:13px}@media(max-width:750px){header{align-items:flex-start;flex-direction:column}h2{font-size:26px}.today-sections{grid-template-columns:1fr}.today-stats{gap:9px}.today-stats>div{padding:17px}.today-stats strong{font-size:28px}.today-section{padding:20px}}
</style>
