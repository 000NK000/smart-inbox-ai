import { computed, onBeforeUnmount, onMounted, readonly, ref } from 'vue'
import axios from 'axios'

const tasks = ref([])
const summary = ref({ version: '', total: 0, open: 0, completed: 0, dueToday: 0, overdue: 0, upcoming: 0, noDeadline: 0 })
const loading = ref(false), error = ref(''), now = ref(Date.now())
let subscribers = 0, listSubscribers = 0, timer, inFlight, listVersion = null
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
export const priorities = { HIGH: '高优先级', NORMAL: '普通优先级', LOW: '低优先级' }

export async function refreshTasks({ force = false } = {}) {
  if (inFlight) {
    await inFlight
    if (force) return refreshTasks({ force: true })
    return
  }
  loading.value = true
  inFlight = (async () => {
    try {
      now.value = Date.now()
      const { data } = await axios.get('/api/tasks/summary', { params: { zone }, timeout: 15000 })
      summary.value = data
      if (listSubscribers > 0 && (force || listVersion !== data.version)) {
        const response = await axios.get('/api/tasks', { timeout: 15000 })
        tasks.value = Array.isArray(response.data) ? response.data : []
        listVersion = data.version
      }
      error.value = ''
    } catch (failure) {
      error.value = failure.response?.data?.message || '任务同步失败，请稍后重试'
    } finally { loading.value = false }
  })()
  try { await inFlight } finally { inFlight = null }
}
function visibleRefresh() { if (!document.hidden) refreshTasks() }
export function useTaskStore({ items = true } = {}) {
  onMounted(() => {
    subscribers++; if (items) listSubscribers++
    if (subscribers === 1) {
      timer = setInterval(visibleRefresh, 15000)
      document.addEventListener('visibilitychange', visibleRefresh)
      window.addEventListener('focus', visibleRefresh)
    }
    visibleRefresh()
  })
  onBeforeUnmount(() => {
    subscribers--; if (items) listSubscribers--
    if (!subscribers) {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', visibleRefresh)
      window.removeEventListener('focus', visibleRefresh)
    }
  })
  return { tasks: readonly(tasks), summary: readonly(summary), loading: readonly(loading), error: readonly(error), now: readonly(now),
    openTasks: computed(() => tasks.value.filter(task => task.status !== 'COMPLETED')), refresh: refreshTasks }
}
async function write(method, url, body, config) {
  const response = await axios[method](url, body, config)
  listVersion = null
  await refreshTasks({ force: true })
  return response.data
}
export const createTask = input => write('post', '/api/tasks', input)
export const editTask = (task, input) => write('put', '/api/tasks/' + encodeURIComponent(task.id), { ...input, version: task.version })
export const completeTask = (task, completed) => write('patch', '/api/tasks/' + encodeURIComponent(task.id) + '/completion', { completed, version: task.version })
export const convertMailTask = input => write('post', '/api/tasks/from-mail', input)
export const importTasks = (items, replace = false) => write(replace ? 'put' : 'post', '/api/tasks/' + (replace ? 'replace' : 'import'), items)
export async function deleteTask(task) {
  await axios.delete('/api/tasks/' + encodeURIComponent(task.id), { params: { version: task.version } })
  listVersion = null; await refreshTasks({ force: true })
}
export async function fetchTaskBackup() { return (await axios.get('/api/tasks', { timeout: 15000 })).data }
export function dateInput(value) {
  if (value == null) return ''
  const d = new Date(value)
  return [d.getFullYear(), '-', String(d.getMonth() + 1).padStart(2, '0'), '-', String(d.getDate()).padStart(2, '0'), 'T',
    String(d.getHours()).padStart(2, '0'), ':', String(d.getMinutes()).padStart(2, '0')].join('')
}
export function parseDue(value) {
  if (!value) return null
  const stamp = new Date(value).getTime()
  if (!Number.isFinite(stamp) || stamp < 0 || stamp > 4102444800000) throw new Error('请选择有效截止日期')
  return stamp
}
export function formatTaskDate(value) { return value == null ? '未设置截止时间' : new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
export function taskError(failure) { return failure.response?.data?.message || failure.message || '保存失败，请重试' }
