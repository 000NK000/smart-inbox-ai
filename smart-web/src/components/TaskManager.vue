<template>
  <section class="task-manager" :aria-label="t('任务管理')">
    <header class="task-intro"><div><span class="eyebrow">FOCUS LIST</span><h2>{{ t("先记再做") }}</h2><p>{{ t("给重要的事留一个位置。完成后会保存在历史中。") }}</p></div><strong class="task-count">{{ summary.open }}<small>{{ t("件未完成") }}</small></strong></header>
    <form class="task-form" @submit.prevent="save">
      <h3>{{ editing ? t("修改任务") : t("添加任务") }}</h3>
      <label class="wide">{{ t("任务内容") }}<input ref="titleInput" v-model="draft.text" required maxlength="300" :placeholder="t('接下来要完成什么？')"></label>
      <label>{{ t("优先级") }}<select v-model="draft.priority"><option v-for="(label,key) in priorities" :key="key" :value="key">{{ t(label) }}</option></select></label>
      <label>{{ t("截止时间") }} <small>{{ t("当地时间") }}</small><input v-model="draft.due" type="datetime-local" min="1970-01-01T00:00" max="2099-12-31T23:59"></label>
      <label class="wide">{{ t("备注") }}<textarea v-model="draft.notes" maxlength="2000" rows="2" :placeholder="t('可选：补充说明或行动步骤')"></textarea></label>
      <div class="form-actions wide"><button class="primary" :disabled="saving">{{ saving ? t("保存中…") : editing ? t("保存修改") : t("添加任务") }}</button><button v-if="editing" type="button" @click="reset">{{ t("取消") }}</button></div>
    </form>
    <p v-if="error" class="notice" role="alert">{{ t(error) }} <button @click="refresh({ force: true })">{{ t("重试") }}</button></p>
    <div class="task-tools"><nav :aria-label="t('任务状态')"><button :class="{ active: view === 'OPEN' }" @click="view = 'OPEN'">{{ t("待办") }} {{ summary.open }}</button><button :class="{ active: view === 'COMPLETED' }" @click="view = 'COMPLETED'">{{ t("完成历史") }} {{ summary.completed }}</button></nav><div><button @click="exportBackup">{{ t("导出任务备份") }}</button><button @click="importInput?.click()">{{ t("恢复备份") }}</button><input ref="importInput" type="file" accept="application/json,.json" hidden @change="restoreBackup"></div></div>
    <ul v-if="visible.length" class="task-list">
      <li v-for="task in visible" :key="task.id" class="task-row">
        <button class="completion" :class="{ checked: task.status === 'COMPLETED' }" :disabled="pending.has(task.id)" :aria-label="task.status === 'COMPLETED' ? t('恢复待办：') + task.text : t('完成任务：') + task.text" @click="toggle(task)">{{ task.status === 'COMPLETED' ? '✓' : '○' }}</button>
        <div class="task-copy"><div class="badges"><span :class="task.priority">{{ t(priorities[task.priority] || priorities.NORMAL) }}</span><span v-if="task.status !== 'COMPLETED' && task.dueAt != null && task.dueAt < now" class="late">{{ t("已逾期") }}</span></div><h3>{{ task.text }}</h3><p v-if="task.notes">{{ task.notes }}</p><small>{{ task.status === 'COMPLETED' ? t("完成于 ") + localTaskDate(task.completedAt) : localTaskDate(task.dueAt) }}</small><button v-if="task.sourceMailId" class="source" @click="$emit('open-mail', { id: task.sourceMailId })">{{ t("查看来源邮件 ↗") }}</button></div>
        <div class="row-actions"><button :disabled="pending.has(task.id)" @click="startEdit(task)">{{ t("修改") }}</button><button class="danger" :disabled="pending.has(task.id)" @click="remove(task)">{{ t("删除") }}</button></div>
      </li>
    </ul>
    <div v-else class="empty"><span>✓</span><h3>{{ view === 'OPEN' ? t("现在没有未完成事项") : t("还没有完成记录") }}</h3><p>{{ view === 'OPEN' ? t("写下下一件要做的事，或从邮件任务规划加入。") : t("完成一个任务后，会在这里保留记录。") }}</p></div>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskStore, priorities, createTask, editTask, completeTask, deleteTask, importTasks, fetchTaskBackup, dateInput, parseDue, taskError } from '../stores/taskStore'
const { t, dateLocale } = useI18n()
function localTaskDate(value) { return value == null ? t('未设置截止时间') : new Date(value).toLocaleString(dateLocale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
const emit = defineEmits(['count-change', 'open-mail'])
const { tasks, summary, error, now, refresh } = useTaskStore()
const view = ref('OPEN'), saving = ref(false), editing = ref(null), importInput = ref(null), titleInput = ref(null)
const pending = reactive(new Set())
const draft = reactive({ text: '', priority: 'NORMAL', due: '', notes: '' })
const visible = computed(() => tasks.value.filter(t => (t.status || 'OPEN') === view.value).sort((a,b) => view.value === 'COMPLETED' ? (b.completedAt || 0) - (a.completedAt || 0) : ({ HIGH:0,NORMAL:1,LOW:2 }[a.priority || 'NORMAL'] - { HIGH:0,NORMAL:1,LOW:2 }[b.priority || 'NORMAL']) || (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity) || b.createdAt - a.createdAt))
watch(() => summary.value.open, count => emit('count-change', count), { immediate: true })
function reset() { editing.value = null; Object.assign(draft, { text: '', priority: 'NORMAL', due: '', notes: '' }) }
function startEdit(task) { editing.value = { ...task }; Object.assign(draft, { text: task.text, priority: task.priority || 'NORMAL', due: dateInput(task.dueAt), notes: task.notes || '' }); titleInput.value?.focus(); titleInput.value?.scrollIntoView({ behavior: 'smooth', block: 'center' }) }
async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const input = { text: draft.text.trim(), priority: draft.priority, dueAt: parseDue(draft.due), notes: draft.notes.trim() }
    if (editing.value) await editTask(editing.value, input); else await createTask(input)
    reset(); ElMessage.success(t("任务已保存"))
  } catch (failure) { ElMessage.error(t(taskError(failure))); await refresh({ force: true }) }
  finally { saving.value = false }
}
async function toggle(task) {
  if (pending.has(task.id)) return
  pending.add(task.id)
  try { await completeTask(task, task.status !== 'COMPLETED') }
  catch (failure) { ElMessage.error(t(taskError(failure))); await refresh({ force: true }) }
  finally { pending.delete(task.id) }
}
async function remove(task) {
  try {
    await ElMessageBox.confirm(t("删除后不会保留在完成历史中。确定删除这条任务？"), t("删除任务"), { confirmButtonText: t("删除"), cancelButtonText: t("取消"), type: 'warning' })
    pending.add(task.id); await deleteTask(task)
    if (editing.value?.id === task.id) reset()
  } catch (failure) { if (failure !== 'cancel' && failure !== 'close') ElMessage.error(t(taskError(failure))) }
  finally { pending.delete(task.id) }
}
async function exportBackup() {
  try {
    const rows = await fetchTaskBackup()
    const url = URL.createObjectURL(new Blob([JSON.stringify({ version: 2, tasks: rows }, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a'); link.href = url; link.download = t("先记再做备份-") + new Date().toISOString().slice(0,10) + '.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) { ElMessage.error(t(taskError(failure))) }
}
async function restoreBackup(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  try {
    if (file.size > 25_000_000) throw new Error(t("备份文件不能超过 25MB"))
    const data = JSON.parse(await file.text())
    if (!Array.isArray(data.tasks) || data.tasks.length > 10000 || (data.version != null && ![1,2].includes(data.version))) throw new Error(t("任务备份格式无效"))
    await ElMessageBox.confirm(t("将用备份中的 ") + data.tasks.length + t(" 条任务替换当前全部待办和完成历史。"), t("恢复任务备份"), { confirmButtonText: t("确认恢复"), cancelButtonText: t("取消"), type: 'warning' })
    await importTasks(data.tasks, true); reset(); ElMessage.success(t("任务备份已恢复"))
  } catch (failure) { if (failure !== 'cancel' && failure !== 'close') ElMessage.error(t(taskError(failure))) }
}
onMounted(async () => {
  const migration = 'remember-before-doing.server-migration.v1', oldKey = 'remember-before-doing.tasks.v2'
  try {
    if (localStorage.getItem(migration)) return
    const legacy = JSON.parse(localStorage.getItem(oldKey) || '[]')
    if (Array.isArray(legacy) && legacy.length) {
      await importTasks(legacy, false); localStorage.setItem(migration, '1'); localStorage.removeItem(oldKey)
      ElMessage.success(t("浏览器中的旧任务已迁移"))
    }
  } catch { ElMessage.warning(t("旧任务暂未迁移，原数据仍保留在浏览器中")) }
})
</script>
<style scoped>
.task-manager{max-width:1060px;margin:auto;color:#20324e}.task-intro{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:26px}.eyebrow{font-size:11px;font-weight:850;letter-spacing:2px;color:#7387a5}h2{font-size:36px;letter-spacing:-1px;margin:10px 0}.task-intro p{color:#76879d;font-size:14px}.task-count{font-size:40px}.task-count small{display:block;font-size:12px;color:#8896a8}.task-form{display:grid;grid-template-columns:1fr 1fr;gap:16px;background:#ffffffbd;border:1px solid white;padding:25px;border-radius:24px;box-shadow:0 15px 40px #3556750b}.task-form h3,.wide{grid-column:1/-1}.task-form h3{margin:0;font-size:17px}.task-form label{display:grid;gap:8px;font-size:12px;font-weight:700;color:#71839c}.task-form small{font-weight:400}input,textarea,select{width:100%;box-sizing:border-box;border:1px solid #dce5ef;border-radius:12px;padding:12px;font:inherit;color:#223653;background:#fff}textarea{resize:vertical}input:focus,textarea:focus,select:focus{outline:2px solid #87b4f5;outline-offset:2px}button{font:inherit;cursor:pointer;border:0;border-radius:10px;background:#eaf0f7;color:#56708f;padding:9px 13px;font-size:12px;font-weight:700}button:disabled{opacity:.5;cursor:wait}button:focus-visible{outline:3px solid #81b3fc;outline-offset:3px}.primary{background:#2f74e8;color:white}.form-actions{display:flex;gap:10px}.task-tools{display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;margin:25px 0 16px}.task-tools>div,nav{display:flex;gap:6px;flex-wrap:wrap}nav .active{background:#253e61;color:white}.task-list{list-style:none;padding:0;display:grid;gap:12px}.task-row{display:grid;grid-template-columns:36px 1fr auto;gap:15px;align-items:start;border:1px solid white;border-radius:22px;padding:21px;background:#ffffffe8}.completion{border-radius:50%;width:34px;height:34px;padding:0;font-size:23px;border:1px solid #bdcddd;background:white}.checked{background:#26ad73;color:white;border-color:transparent}.task-copy{min-width:0}.task-copy h3{font-size:17px;line-height:1.6;margin:8px 0;overflow-wrap:anywhere}.task-copy p{font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere;color:#71829a}.task-copy small{font-size:11px;color:#8a9aae}.badges{display:flex;gap:7px;flex-wrap:wrap}.badges span{padding:4px 8px;border-radius:7px;background:#edf4ff;font-size:10px;color:#5582b6}.badges .HIGH,.badges .late{color:#ba5454;background:#fff0ec}.badges .LOW{color:#65917c;background:#eef7f1}.source{display:block;background:transparent;color:#347ae3;padding:8px 0 0}.row-actions{display:flex;gap:6px}.danger{background:#fff0ef;color:#b65a58}.empty{text-align:center;color:#8093ac;padding:55px 20px}.empty>span{font-size:40px;color:#42b488}.empty p{font-size:13px}.notice{padding:15px;border-radius:12px;background:#fff4dc;color:#a17637;font-size:13px}@media(max-width:650px){.task-form{grid-template-columns:1fr}.task-row{grid-template-columns:34px 1fr}.row-actions{grid-column:2}.task-count{font-size:32px}.task-intro{align-items:flex-start}.task-intro p{line-height:1.8}}
</style>
