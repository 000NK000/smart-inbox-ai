<template>
  <section class="practice-center" :aria-label="t('刷题进度与熟练度')">
    <header class="hero">
      <div>
        <span class="eyebrow">JAVA · INTERVIEW PRACTICE</span>
        <h2>{{ t('我的刷题记录') }}</h2>
        <p>{{ t('按题型整理题目，记录自己的练习水平与同类题的解题心得。下方题单只是可选建议。') }}</p>
      </div>
      <button type="button" class="soft-button" :disabled="loading || saving" @click="refresh">{{ t('刷新记录 ↻') }}</button>
    </header>
    <p v-if="error" class="error" role="alert">{{ t(error) }}</p>
    <p v-if="loading && !loaded" class="empty">{{ t('正在读取本地刷题记录…') }}</p>
    <template v-else>
      <div class="stat-grid">
        <div class="stat"><span>{{ t('已记录题目') }}</span><strong>{{ summary.total }}</strong><small>{{ t('由你自己添加或从建议题单选入') }}</small></div>
        <div class="stat independent"><span>{{ t('独立完成') }}</span><strong>{{ summary.independent }}</strong><small>{{ t('闭卷写出，并能解释思路和边界') }}</small></div>
        <div class="stat unfamiliar"><span>{{ t('不熟练') }}</span><strong>{{ summary.unfamiliar }}</strong><small>{{ t('适合下次随机抽取复习') }}</small></div>
        <div class="stat"><span>{{ t('已记录练习时间') }}</span><strong>{{ hours(summary.totalMinutes) }}</strong><small>{{ t('每次记录的耗时累计') }}</small></div>
      </div>

      <section class="library">
        <div class="section-head">
          <div><span class="eyebrow">MY PRACTICE LIBRARY</span><h3>{{ t('我的题库') }}</h3></div>
          <button type="button" class="draw-button" :disabled="!weakPool.length" @click="drawRandom">{{ t('随机抽一道不熟练题 ↻') }}</button>
        </div>
        <div v-if="drawn" class="drawn" role="status">
          <div><span class="eyebrow">{{ t('这次复习') }}</span><strong>{{ drawn.number }} · {{ drawn.title }}</strong><small>{{ drawn.topic }} · {{ t('练习 {count} 次', { count: drawn.attempts }) }}</small></div>
          <button type="button" @click="openEditor(drawn)">{{ t('记录复习结果 ↗') }}</button>
        </div>
        <p v-else-if="!weakPool.length" class="hint">{{ selectedGroup === 'ALL' ? t('记录题目并选择“不熟练”后，就可以随机抽一道。') : t('这个题型暂时没有不熟练的题目。') }}</p>
        <form class="add-form" @submit.prevent="openCustom">
          <label>{{ t('LeetCode 题号') }}<input v-model.number="custom.number" type="number" min="1" max="99999" required :placeholder="t('例如 236')" /></label>
          <label>{{ t('题名（选填）') }}<input v-model.trim="custom.title" maxlength="200" :placeholder="t('例如 二叉树的最近公共祖先')" /></label>
          <label>{{ t('题型分组（选填）') }}<input v-model.trim="custom.topic" list="practice-group-choices" maxlength="80" :placeholder="t('例如 动态规划')" /></label>
          <div class="add-actions"><button type="submit">{{ t('记录这道题 ＋') }}</button><button type="button" class="note-open" @click="openCustomSolution">{{ t('写这题的题解') }}</button></div>
        </form>
        <datalist id="practice-group-choices"><option v-for="name in groupNames" :key="name" :value="name" /></datalist>
        <section class="group-section" :aria-label="t('题型分组与解题心得')">
          <div class="group-heading"><div><span class="eyebrow">PROBLEM TYPES</span><h4>{{ t('按题型分组') }}</h4></div><small>{{ t('已有题目的题型会自动成为分组') }}</small></div>
          <div class="group-tabs" role="group" :aria-label="t('选择题型分组')">
            <button type="button" :class="{ active: selectedGroup === 'ALL' }" @click="selectedGroup = 'ALL'">{{ t('全部题目') }} <b>{{ summary.total }}</b></button>
            <button v-for="name in groupNames" :key="name" type="button" :class="{ active: selectedGroup === name }" @click="selectedGroup = name">{{ name }} <b>{{ groupCount(name) }}</b></button>
          </div>
          <form class="group-create" @submit.prevent="createGroup"><input v-model.trim="newGroupName" maxlength="80" :aria-label="t('新题型名称')" :placeholder="t('新建题型，例如 动态规划')" /><button type="submit" :disabled="saving || !newGroupName.trim()">{{ t('新建分组 ＋') }}</button></form>
          <div v-if="selectedGroup !== 'ALL'" class="group-notes">
            <div><strong>{{ selectedGroup }} {{ t('· 解题心得') }}</strong><span>{{ t('{count} 道题 · {weak} 道不熟练', { count: groupCount(selectedGroup), weak: groupWeakCount(selectedGroup) }) }}</span></div>
            <p v-if="!editingGroupNote">{{ selectedGroupNote || t('还没有心得。可以记下这类题的识别方法、通用思路和常见错误。') }}</p>
            <textarea v-else v-model="groupNoteDraft" maxlength="4000" rows="5" :aria-label="t('同题型解题心得')" :placeholder="t('例如：先定义 dp[i] 的含义，再确定状态转移和初始化…')"></textarea>
            <div class="group-actions"><button v-if="!editingGroupNote" type="button" @click="editGroupNote">{{ selectedGroupNote ? t('修改心得') : t('写解题心得') }}</button><template v-else><button type="button" :disabled="saving" @click="cancelGroupNote">{{ t('取消') }}</button><button type="button" :disabled="saving" @click="saveGroupNote">{{ t('保存心得') }}</button></template></div>
          </div>
        </section>
        <div class="list-toolbar">
          <div class="filter-buttons" role="group" :aria-label="t('筛选我的刷题记录')">
            <button v-for="choice in filters" :key="choice.key" type="button" :class="{ active: filter === choice.key }" @click="filter = choice.key">{{ t(choice.name) }}</button>
          </div>
          <input v-model.trim="query" type="search" :aria-label="t('查找已记录题目')" :placeholder="t('查找题号或题名')" />
        </div>
        <div v-if="visibleRecords.length" class="record-list">
          <div v-for="record in visibleRecords" :key="record.number" class="record-entry">
            <button type="button" class="record-row" @click="openEditor(record)">
              <b>{{ record.number }}</b>
              <span><strong>{{ record.title }}</strong><small>{{ record.topic }} · {{ t('练习 {count} 次', { count: record.attempts }) }}<template v-if="record.totalMinutes"> · {{ record.totalMinutes }} {{ t('分钟') }}</template></small></span>
              <em :class="statusClass(record)">{{ statusName(record) }}</em>
              <span aria-hidden="true">↗</span>
            </button>
            <button type="button" class="solution-launch" @click="openSolution(record)">{{ solutionNumbers.includes(record.number) ? t('查看题解') : t('写题解') }}</button>
          </div>
        </div>
        <p v-else class="empty">{{ summary.total ? t('没有符合筛选条件的题目。') : t('还没有练习记录。也可以先为任意题号写题解。') }}</p>
        <section v-if="noteOnlyNumbers.length" class="note-only"><h4>{{ t('只写了题解的题目') }}</h4><p>{{ t('这些题目尚未记录练习结果，题解仍会独立保存。') }}</p><div><button v-for="number in noteOnlyNumbers" :key="number" type="button" @click="openSolution({ number, title: planByNumber.get(number)?.title || `LeetCode #${number}` })">{{ number }} · {{ planByNumber.get(number)?.title || t('查看题解') }} ↗</button></div></section>
      </section>

      <details class="suggestions">
        <summary><span><span class="eyebrow">OPTIONAL SUGGESTIONS</span><strong>{{ t('GPT 建议题单') }}</strong><small>{{ t('四周 30 道补充题及模拟安排，仅供参考；不按日期强制完成') }}</small></span><span aria-hidden="true">{{ t('展开 ↘') }}</span></summary>
        <div class="suggestion-body">
          <p>{{ t('你可以跳过、调换顺序或完全按自己的题单练。点一道建议题，也能把它加入“我的题库”。已记录建议题') }} {{ summary.suggestedAttempted }}/30。</p>
          <div class="week-tabs" role="group" :aria-label="t('选择建议周')">
            <button v-for="week in [1, 2, 3, 4]" :key="week" type="button" :class="{ active: selectedWeek === week }" @click="selectedWeek = week">{{ t('第 {week} 周', { week }) }}</button>
          </div>
          <p class="week-guide">{{ t(weekGuide[selectedWeek]) }}</p>
          <template v-if="selectedWeek < 4">
            <div v-for="day in visibleDays" :key="day.date" class="suggestion-day">
              <h4>{{ shortDate(day.date) }} · {{ day.topic }}</h4>
              <button v-for="problem in day.problems" :key="problem.number" type="button" @click="openEditor(recordFor(problem.number) || problem)">
                <b>{{ problem.number }}</b><span>{{ problem.title }}</span><small>{{ recordFor(problem.number) ? statusName(recordFor(problem.number)) : t('未记录') }}</small>
              </button>
            </div>
          </template>
          <div v-else class="special-list"><p v-for="item in fourthWeek" :key="item.date"><b>{{ shortDate(item.date) }} · {{ item.title }}</b><br />{{ item.detail }}</p></div>
          <p class="weekend" v-if="selectedWeek < 4" v-for="item in weekendDays" :key="item.date">{{ shortDate(item.date) }} · {{ item.title }}：{{ item.detail }}</p>
        </div>
      </details>
    </template>

    <div v-if="editing" class="modal-backdrop" @click.self="closeEditor">
      <section class="practice-modal" role="dialog" aria-modal="true" :aria-label="t('记录 {title} 的练习', { title: editing.title })">
        <header><div><span class="eyebrow">PRACTICE LOG</span><h3>{{ editing.number }} · {{ editing.title }}</h3></div><button type="button" class="close" :aria-label="t('关闭')" @click="closeEditor">×</button></header>
        <form @submit.prevent="save">
          <div class="form-grid"><label>{{ t('题名') }}<input v-model.trim="draft.title" maxlength="200" required /></label><label>{{ t('题型分组') }}<input v-model.trim="draft.topic" list="practice-group-choices" maxlength="80" required /></label></div>
          <button v-if="recordFor(editing.number) && draft.topic.trim() !== recordFor(editing.number).topic" type="button" class="move-button" :disabled="saving" @click="moveGroup">{{ t('只修改所属分组（不增加练习次数）') }}</button>
          <fieldset><legend>{{ t('这次完成得怎么样？') }}</legend>
            <label v-for="option in resultOptions" :key="option.key" :class="['result-option', option.key.toLowerCase(), { chosen: draft.result === option.key }]">
              <input v-model="draft.result" type="radio" name="result" :value="option.key" required />
              <strong>{{ t(option.name) }}</strong><small>{{ t(option.help) }}</small>
            </label>
          </fieldset>
          <div class="form-grid"><label>{{ t('练习耗时（分钟，可填 0）') }}<input v-model.number="draft.minutes" type="number" min="0" max="600" required /></label><label>{{ t('主要卡点') }}<select v-model="draft.mistake"><option v-for="(name, key) in mistakeNames" :key="key" :value="key">{{ t(name) }}</option></select></label></div>
          <label>{{ t('复盘笔记（选填）') }}<textarea v-model="draft.note" maxlength="1000" rows="3" :placeholder="t('例如：边界条件没想全；Java Map 用法不熟。')"></textarea></label>
          <p v-if="recordFor(editing.number)?.note" class="previous-note">{{ t('上次记录：') }}{{ recordFor(editing.number).note }}</p>
          <p v-if="modalError" class="error" role="alert">{{ t(modalError) }}</p>
          <footer><button v-if="recordFor(editing.number)" type="button" class="delete" :disabled="saving" @click="resetRecord">{{ t('删除这道题的记录') }}</button><button type="button" class="note-open" :disabled="saving" @click="openSolutionFromPractice">{{ t('写 / 查看专属题解') }}</button><button class="primary" type="submit" :disabled="saving || !draft.result">{{ saving ? t('保存中…') : t('保存记录') }}</button></footer>
        </form>
      </section>
    </div>
    <PracticeSolutionModal v-if="solutionTarget" :key="solutionTarget.number" :problem="solutionTarget" @close="solutionTarget = null" @saved="refreshSolutionNumbers" />
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { computed, onMounted, reactive, ref, watch } from 'vue'
import axios from 'axios'
import { planByNumber, planDays, practiceSummary, specialDays, pickUnfamiliar } from '../utils/practicePlan'
import PracticeSolutionModal from './PracticeSolutionModal.vue'

const weekGuide = {
  1: '数组、滑动窗口、双指针、二分答案和区间。可选两道主专题题，再混一道旧题。',
  2: '栈、树、图和堆。没学过并查集时先学习，再做 684。',
  3: '混合题型，不提前看标签；743 可以作为学习题。',
  4: '限时模拟、弱项复习和公司定向准备。'
}
const filters = [
  { key: 'ALL', name: '全部' }, { key: 'GREEN', name: '独立完成' }, { key: 'UNFAMILIAR', name: '不熟练' }
]
const resultOptions = [
  { key: 'GREEN', name: '独立完成', help: '闭卷写出，能讲清思路、复杂度和边界' },
  { key: 'YELLOW', name: '不熟练', help: '需要提示、看题解，或实现还不稳定' }
]
const mistakeNames = { NONE: '无明显卡点', METHOD: '没想到方法', BOUNDARY: '边界判断', JAVA: 'Java 实现', COMPLEXITY: '复杂度', OTHER: '其他' }
const records = ref([]), loading = ref(false), loaded = ref(false), saving = ref(false), error = ref(''), modalError = ref('')
const selectedWeek = ref(1), filter = ref('ALL'), query = ref(''), editing = ref(null), drawnNumber = ref(null)
const groups = ref([]), selectedGroup = ref('ALL'), newGroupName = ref(''), editingGroupNote = ref(false), groupNoteDraft = ref('')
const solutionTarget = ref(null), solutionNumbers = ref([])
const draft = reactive({ title: '', topic: '', result: '', minutes: 0, mistake: 'NONE', note: '' })
const custom = reactive({ number: null, title: '', topic: '' })
const byNumber = computed(() => new Map(records.value.map(item => [item.number, item])))
const summary = computed(() => practiceSummary(records.value))
const noteOnlyNumbers = computed(() => solutionNumbers.value.filter(number => !byNumber.value.has(number)))
const groupNames = computed(() => [...new Set([...groups.value.map(group => group.name), ...records.value.map(item => item.topic).filter(Boolean)])].sort((a, b) => a.localeCompare(b, dateLocale.value)))
const selectedGroupNote = computed(() => groups.value.find(group => group.name === selectedGroup.value)?.note || '')
const weakPool = computed(() => records.value.filter(item => (item.status === 'YELLOW' || item.status === 'RED') && (selectedGroup.value === 'ALL' || item.topic === selectedGroup.value)))
const drawn = computed(() => {
  const item = byNumber.value.get(drawnNumber.value)
  return weakPool.value.some(candidate => candidate.number === item?.number) ? item : null
})
watch(selectedGroup, () => { editingGroupNote.value = false; groupNoteDraft.value = ''; drawnNumber.value = null })
const visibleRecords = computed(() => records.value
  .filter(item => selectedGroup.value === 'ALL' || item.topic === selectedGroup.value)
  .filter(item => filter.value === 'ALL' || filter.value === 'GREEN' && item.status === 'GREEN' || filter.value === 'UNFAMILIAR' && item.status !== 'GREEN')
  .filter(item => !query.value || (item.number + ' ' + item.title + ' ' + item.topic).toLowerCase().includes(query.value.toLowerCase()))
  .sort((a, b) => (b.lastPracticedAt || 0) - (a.lastPracticedAt || 0) || a.number - b.number))
const visibleDays = computed(() => planDays.filter(day => day.week === selectedWeek.value))
const fourthWeek = specialDays.filter(day => day.date >= '2026-10-12')
const weekendDays = computed(() => specialDays.filter(day => selectedWeek.value === 1 && day.date <= '2026-09-27' ||
  selectedWeek.value === 2 && day.date >= '2026-10-03' && day.date <= '2026-10-04' ||
  selectedWeek.value === 3 && day.date >= '2026-10-10' && day.date <= '2026-10-11'))

function recordFor(number) { return byNumber.value.get(number) }
function statusName(item) { return t(item?.status === 'GREEN' ? '独立完成' : item ? '不熟练' : '未记录') }
function statusClass(item) { return item?.status === 'GREEN' ? 'status-green' : 'status-unfamiliar' }
function hours(minutes) { return t('{hours}小时{minutes}分', { hours: Math.floor(minutes / 60), minutes: String(minutes % 60).padStart(2, '0') }) }
function shortDate(value) { return Number(value.slice(5, 7)) + '/' + Number(value.slice(8, 10)) }
function groupCount(name) { return records.value.filter(item => item.topic === name).length }
function groupWeakCount(name) { return records.value.filter(item => item.topic === name && item.status !== 'GREEN').length }
function drawRandom() { drawnNumber.value = pickUnfamiliar(weakPool.value, drawnNumber.value)?.number ?? null }
async function refresh() {
  loading.value = true; error.value = ''
  try {
    const [progress, types, solutions] = await Promise.all([
      axios.get('/api/practice', { timeout: 12000 }), axios.get('/api/practice/groups', { timeout: 12000 }),
      axios.get('/api/practice/solutions', { timeout: 12000 })
    ])
    records.value = progress.data; groups.value = types.data; solutionNumbers.value = solutions.data; loaded.value = true
  }
  catch (failure) { error.value = failure.response?.data?.message || '刷题记录暂时无法读取，请重试' }
  finally { loading.value = false }
}
async function refreshSolutionNumbers() {
  try { solutionNumbers.value = (await axios.get('/api/practice/solutions', { timeout: 12000 })).data }
  catch { error.value = '题解已保存，但目录暂时未刷新；请点击“刷新记录”' }
}
function openSolution(problem) { solutionTarget.value = { number: Number(problem.number), title: problem.title || `LeetCode #${problem.number}` } }
function openCustomSolution() {
  if (!Number.isInteger(custom.number) || custom.number < 1 || custom.number > 99999) { error.value = '请先填写有效的 LeetCode 题号'; return }
  const existing = recordFor(custom.number) || planByNumber.get(custom.number)
  openSolution({ number: custom.number, title: custom.title || existing?.title })
}
function openSolutionFromPractice() {
  if (saving.value || !editing.value) return
  const target = { ...editing.value, title: draft.title.trim() || editing.value.title }
  editing.value = null
  openSolution(target)
}
async function persistGroup(name, note) {
  const old = groups.value.find(group => group.name === name)
  const response = await axios.post('/api/practice/groups', { name, note, version: old?.version ?? null }, { timeout: 12000 })
  groups.value = [...groups.value.filter(group => group.name !== response.data.name), response.data]
  return response.data
}
async function createGroup() {
  const name = newGroupName.value.trim()
  if (!name || saving.value) return
  if (groupNames.value.includes(name)) { selectedGroup.value = name; newGroupName.value = ''; return }
  saving.value = true; error.value = ''
  try { await persistGroup(name, ''); selectedGroup.value = name; newGroupName.value = '' }
  catch (failure) { error.value = failure.response?.data?.message || '新建分组失败，请重试'; if (failure.response?.status === 409) await refresh() }
  finally { saving.value = false }
}
function editGroupNote() { groupNoteDraft.value = selectedGroupNote.value; editingGroupNote.value = true }
function cancelGroupNote() { editingGroupNote.value = false; groupNoteDraft.value = '' }
async function saveGroupNote() {
  if (selectedGroup.value === 'ALL' || saving.value) return
  saving.value = true; error.value = ''
  try { await persistGroup(selectedGroup.value, groupNoteDraft.value); cancelGroupNote() }
  catch (failure) { error.value = failure.response?.data?.message || '保存心得失败，请重试'; if (failure.response?.status === 409) await refresh() }
  finally { saving.value = false }
}
function openEditor(problem) {
  editing.value = { number: Number(problem.number), title: problem.title, topic: problem.topic }
  draft.title = problem.title
  draft.topic = problem.topic
  draft.result = problem.status === 'GREEN' ? 'GREEN' : problem.status ? 'YELLOW' : ''
  draft.minutes = 0
  draft.mistake = 'NONE'
  draft.note = ''
  modalError.value = ''
}
function openCustom() {
  if (!Number.isInteger(custom.number) || custom.number < 1 || custom.number > 99999) return
  const existing = recordFor(custom.number) || planByNumber.get(custom.number)
  openEditor(existing || { number: custom.number, title: custom.title || 'LeetCode #' + custom.number, topic: custom.topic || '自行记录' })
  if (custom.title) draft.title = custom.title
  if (custom.topic) draft.topic = custom.topic
}
function closeEditor() { if (!saving.value) editing.value = null }
async function moveGroup() {
  const previous = editing.value && recordFor(editing.value.number)
  if (!previous || !draft.topic.trim() || saving.value) return
  saving.value = true; modalError.value = ''
  try {
    const response = await axios.patch('/api/practice/' + previous.number + '/group', { topic: draft.topic.trim(), version: previous.version }, { timeout: 12000 })
    records.value = [...records.value.filter(item => item.number !== previous.number), response.data]
    editing.value = null
  } catch (failure) {
    modalError.value = failure.response?.data?.message || '修改分组失败，请重试'
    if (failure.response?.status === 409) await refresh()
  } finally { saving.value = false }
}
async function save() {
  if (!editing.value || !draft.result || !draft.title.trim() || !draft.topic.trim() || saving.value) return
  saving.value = true; modalError.value = ''
  const previous = recordFor(editing.value.number)
  try {
    const response = await axios.post('/api/practice/attempts', {
      number: editing.value.number, title: draft.title.trim(), topic: draft.topic.trim(),
      result: draft.result, minutes: Number(draft.minutes), mistake: draft.mistake, note: draft.note,
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone, version: previous?.version ?? null
    }, { timeout: 12000 })
    records.value = [...records.value.filter(item => item.number !== response.data.number), response.data]
    editing.value = null
    custom.number = null; custom.title = ''; custom.topic = ''
  } catch (failure) {
    modalError.value = failure.response?.data?.message || '保存失败，请重试'
    if (failure.response?.status === 409) await refresh()
  } finally { saving.value = false }
}
async function resetRecord() {
  const existing = editing.value && recordFor(editing.value.number)
  if (!existing || saving.value || !window.confirm(t('确定删除 {number}「{title}」的全部练习记录吗？', { number: existing.number, title: existing.title }))) return
  saving.value = true; modalError.value = ''
  try {
    await axios.delete('/api/practice/' + existing.number, { params: { version: existing.version }, timeout: 12000 })
    records.value = records.value.filter(item => item.number !== existing.number)
    if (drawnNumber.value === existing.number) drawnNumber.value = null
    editing.value = null
  } catch (failure) {
    modalError.value = failure.response?.data?.message || '删除失败，请重试'
    if (failure.response?.status === 409) await refresh()
  } finally { saving.value = false }
}
onMounted(refresh)
</script>

<style scoped>
.practice-center{max-width:1220px;margin:auto;color:#1c2e48}.hero,.section-head{display:flex;justify-content:space-between;align-items:center;gap:20px}.hero{margin-bottom:24px}.eyebrow{font-size:11px;letter-spacing:.15em;color:#7890aa;font-weight:850}.hero h2{font-size:clamp(28px,3vw,42px);margin:8px 0;color:#193252}.hero p,.hint,.suggestion-body p{font-size:13px;color:#6b7f99;line-height:1.7}.soft-button{border:0;border-radius:12px;padding:12px 17px;background:#e3efff;color:#2b62b6;font-weight:800;white-space:nowrap}.stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:21px}.stat{min-height:138px;border:1px solid white;background:#ffffffdf;border-radius:23px;padding:20px;box-shadow:0 14px 35px #244f810c}.stat>span,.stat>small{display:block;color:#7088a6;font-size:11px}.stat strong{display:block;margin:15px 0 8px;font-size:31px;color:#213957}.stat.independent strong{color:#1e986f}.stat.unfamiliar strong{color:#d4944b}.library,.suggestions{border:1px solid white;background:#ffffffe8;border-radius:27px;padding:25px;box-shadow:0 18px 45px #27476f0d;margin-bottom:20px}.section-head h3{font-size:24px;margin:5px 0;color:#203755}.draw-button,.add-form button,.drawn button,.primary{border:0;border-radius:12px;background:#326fda;color:white;font-weight:800;padding:12px 16px}.draw-button:disabled{background:#d7e1ee;color:#7990a9}.drawn{display:flex;align-items:center;justify-content:space-between;gap:14px;background:#eef8ff;border:1px solid #c9e4fa;border-radius:16px;padding:17px;margin-top:20px}.drawn strong,.drawn small{display:block;margin-top:5px}.drawn small{color:#7187a0;font-size:12px}.hint{margin:17px 0 0}.add-form{display:grid;grid-template-columns:140px minmax(180px,1fr) minmax(180px,1fr) auto;gap:10px;align-items:end;background:#f3f8ff;border-radius:18px;padding:16px;margin:20px 0}.add-form label,.form-grid label,.practice-modal form>label{display:grid;gap:7px;color:#5b718c;font-size:12px;font-weight:750}.practice-center :is(input,select,textarea){box-sizing:border-box;width:100%;min-height:42px;padding:10px;border:1px solid #d6e2f1;border-radius:10px;background:white;color:#1c2e48;font:inherit}.list-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:11px}.filter-buttons{display:flex;gap:5px;padding:4px;border-radius:12px;background:#eef3fa}.filter-buttons button,.week-tabs button{border:0;background:transparent;color:#667e9e;border-radius:10px;padding:9px 12px;font-weight:750}.filter-buttons button.active,.week-tabs button.active{color:#2865c4;background:white;box-shadow:0 3px 10px #244f8116}.list-toolbar>input{max-width:250px}.record-list{max-height:515px;overflow:auto}.record-row,.suggestion-day button{display:flex;align-items:center;gap:16px;width:100%;padding:14px;border:0;border-bottom:1px solid #e4ecf6;text-align:left;background:transparent;color:#29415d}.record-row>b,.suggestion-day button>b{min-width:37px;color:#4280d4}.record-row>span:nth-child(2){flex:1;min-width:0}.record-row strong,.record-row small{display:block}.record-row small{margin-top:4px;color:#8296ad;font-size:11px}.record-row em,.suggestion-day small{font-style:normal;font-size:11px;font-weight:750;border-radius:99px;padding:6px 10px}.status-green{color:#168866;background:#e5f7ee}.status-unfamiliar{color:#ac6d2b;background:#fff2df}.empty{padding:24px;text-align:center;color:#8497ad}.suggestions{padding:0;overflow:hidden}.suggestions>summary{list-style:none;display:flex;align-items:center;justify-content:space-between;gap:16px;cursor:pointer;padding:22px 25px}.suggestions>summary::-webkit-details-marker{display:none}.suggestions>summary strong,.suggestions>summary small{display:block;margin-top:5px}.suggestions>summary strong{font-size:22px}.suggestions>summary small{color:#7c90a9;font-size:12px}.suggestion-body{border-top:1px solid #e5edf8;padding:20px 25px}.week-tabs{display:flex;gap:7px;background:#f2f6fc;border-radius:13px;padding:5px;width:max-content}.week-guide{margin:14px 0}.suggestion-day{margin-bottom:14px;border:1px solid #e1eaf6;border-radius:15px;overflow:hidden}.suggestion-day h4{margin:0;padding:11px 13px;background:#f4f8fe;font-size:13px}.suggestion-day button{padding:12px}.suggestion-day button span{flex:1}.suggestion-day button:last-child{border-bottom:0}.weekend{margin:5px 0}.special-list p{padding:9px;border-bottom:1px solid #e4ecf6}.error{border-radius:10px;padding:10px;background:#fff0ed;color:#b54848}.modal-backdrop{position:fixed;inset:0;z-index:1000;background:#142a467d;display:grid;place-items:center;padding:16px}.practice-modal{width:min(670px,100%);max-height:88vh;overflow:auto;background:white;border-radius:24px;padding:26px;box-shadow:0 25px 80px #10243e66}.practice-modal header{display:flex;justify-content:space-between;gap:16px;margin-bottom:18px}.practice-modal h3{margin:6px 0;font-size:24px}.close{border:0;background:#edf3fb;color:#476887;border-radius:50%;width:34px;height:34px;font-size:22px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:13px;margin:12px 0}.practice-modal fieldset{border:0;padding:0;margin:17px 0;display:grid;grid-template-columns:1fr 1fr;gap:11px}.practice-modal legend{font-size:12px;color:#5b718c;font-weight:750;margin-bottom:9px}.result-option{display:grid;gap:6px;border:1px solid #dbe5f2;border-radius:14px;padding:14px;cursor:pointer}.result-option.chosen{outline:2px solid #79aaf0}.result-option input{width:auto;min-height:0;justify-self:start}.result-option strong{font-size:14px}.result-option small{font-size:11px;color:#8498b2}.practice-modal textarea{resize:vertical}.previous-note{background:#f4f7fc;border-radius:10px;padding:10px;color:#6c809d;font-size:11px}.practice-modal footer{display:flex;justify-content:flex-end;gap:10px;margin-top:20px}.delete{border:0;background:transparent;color:#b76a68;margin-right:auto}.practice-center button{cursor:pointer;font-family:inherit}.practice-center button:disabled{cursor:default}.practice-center :is(button,input,select,textarea):focus-visible{outline:3px solid #6ca7f6;outline-offset:2px}@media(max-width:900px){.stat-grid{grid-template-columns:repeat(2,1fr)}.add-form{grid-template-columns:1fr 1fr}.add-form button{grid-column:1/-1}}@media(max-width:600px){.hero,.section-head,.drawn,.list-toolbar{align-items:stretch;flex-direction:column}.stat-grid,.add-form,.form-grid,.practice-modal fieldset{grid-template-columns:1fr}.week-tabs{width:100%;display:grid;grid-template-columns:repeat(4,1fr)}.library{padding:18px}.list-toolbar>input{max-width:none}.record-row{gap:8px;padding:11px 4px}.practice-modal{padding:18px}}
.group-section{margin:0 0 22px;padding:18px;border:1px solid #dfeaf7;border-radius:18px;background:#f9fbff}.group-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.group-heading h4{margin:5px 0 12px;font-size:18px;color:#234464}.group-heading small{color:#8295ab}.group-tabs{display:flex;flex-wrap:wrap;gap:8px}.group-tabs button{border:1px solid #d8e5f3;background:white;color:#4b6787;padding:9px 13px;border-radius:11px;font-weight:750}.group-tabs button.active{background:#2f6bcf;border-color:#2f6bcf;color:white}.group-tabs b{margin-left:6px;opacity:.75}.group-create{display:flex;gap:9px;max-width:490px;margin-top:15px}.group-create button,.group-actions button,.move-button{border:1px solid #a9caee;background:#eaf4ff;color:#2461a8;border-radius:10px;padding:9px 14px;font-weight:750;white-space:nowrap}.group-notes{margin-top:16px;padding:16px;background:white;border:1px solid #e1eaf5;border-radius:14px}.group-notes>div:first-child{display:flex;justify-content:space-between;gap:12px}.group-notes strong{color:#234464}.group-notes span,.group-notes p{color:#7186a0;font-size:13px}.group-notes p{white-space:pre-wrap;line-height:1.7;margin:12px 0}.group-notes textarea{margin:12px 0}.group-actions{text-align:right}.group-actions button{margin-left:7px}.move-button{margin:0 0 4px}.group-create button:disabled,.group-actions button:disabled{opacity:.55}@media(max-width:600px){.group-heading,.group-notes>div:first-child{align-items:flex-start;flex-direction:column}.group-create{flex-direction:column}}
.add-actions{display:flex;gap:8px;align-items:center}.add-actions button{white-space:nowrap}.add-actions .note-open,.practice-modal footer .note-open,.solution-launch{border:1px solid #bcd7f3;background:#edf6ff;color:#2761a5;border-radius:10px;padding:10px 13px;font-weight:750}.record-entry{display:flex;align-items:center;gap:8px;border-bottom:1px solid #e4ecf6}.record-entry .record-row{width:auto;flex:1;border-bottom:0;min-width:0}.solution-launch{flex:none;font-size:12px}.practice-modal footer .note-open{margin-right:auto}@media(max-width:900px){.add-actions{grid-column:1/-1}.add-actions button{flex:1}}@media(max-width:600px){.record-entry{align-items:stretch}.record-entry .record-row{gap:6px}.solution-launch{align-self:center;padding:8px}.practice-modal footer{flex-wrap:wrap}.practice-modal footer .note-open{margin-right:0}}
.note-only{margin-top:22px;border-top:1px solid #e2ebf5;padding-top:17px}.note-only h4{margin:0;color:#244260}.note-only p{font-size:12px;color:#8195ab}.note-only>div{display:flex;flex-wrap:wrap;gap:7px}.note-only button{border:1px solid #c9def5;border-radius:10px;background:#f2f8ff;color:#2863a6;padding:9px 12px;font-weight:750}
</style>
