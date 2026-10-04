import { ref } from 'vue'

export const focusControllerKey = Symbol('focus-controller')

// One command owner per workspace. The focus panel supplies its existing polling;
// other pages only fetch a snapshot when the user asks to switch the timer.
export function createFocusController(client, zone, now = Date.now) {
  const overview = ref(null), snapshotAt = ref(0), error = ref(''), busy = ref(false), refreshing = ref(false)
  let alive = true, requestId = 0, needsReconciliation = false
  const running = () => !!overview.value?.active?.id
    && ['EFFECTIVE', 'INEFFECTIVE'].includes(overview.value.active.category)
    && Number.isFinite(overview.value.serverNow)
    && overview.value.serverNow + Math.max(0, now() - snapshotAt.value) < overview.value.active.leaseUntil

  function apply(data) { overview.value = data; snapshotAt.value = now(); needsReconciliation = false }
  async function refresh({ reconcile = false } = {}) {
    if (!alive || !reconcile && (refreshing.value || busy.value)) return false
    refreshing.value = true
    const request = ++requestId
    try {
      const { data } = await client.get('/api/focus', { params: { zone }, timeout: 12000 })
      if (alive && request === requestId) { apply(data); error.value = ''; return true }
    } catch { if (alive && request === requestId) error.value = '计时记录暂时无法读取，请重试' }
    finally { if (request === requestId) refreshing.value = false }
    return false
  }
  async function switchCategory({ canSwitch = () => true } = {}) {
    if (!alive || busy.value || !canSwitch()) return { status: 'ignored' }
    busy.value = true; error.value = ''
    try {
      // A stale or uncertain session must be read again before sending its id.
      // A missing desktop session is never started from a browser shortcut.
      if (needsReconciliation || !running() || now() - snapshotAt.value >= 15000) {
        if (!await refresh({ reconcile: true })) return { status: alive ? 'error' : 'ignored', message: error.value }
      }
      if (!alive || !canSwitch()) return { status: 'ignored' }
      if (!running()) { error.value = '等待软件恢复计时…'; return { status: 'unavailable', message: error.value } }
      const request = ++requestId
      refreshing.value = false
      try {
        const { data } = await client.post('/api/focus/switch', { id: overview.value.active.id, zone }, { timeout: 12000 })
        if (!alive || request !== requestId) return { status: 'ignored' }
        apply(data)
        return { status: 'switched', category: data.active?.category }
      } catch (failure) {
        if (!alive) return { status: 'ignored' }
        // A timed-out write may have succeeded. Reconcile, never repeat a toggle.
        needsReconciliation = true
        const reconciled = await refresh({ reconcile: true })
        if (!alive) return { status: 'ignored' }
        error.value = reconciled ? failure.response?.status === 409 ? '计时状态已更新，请确认当前状态后再切换。' : '未能确认切换结果，已重新读取记录，请确认当前状态。' : '切换结果尚未确认，请刷新记录后再试。'
        return { status: 'error', message: error.value }
      }
    } finally { busy.value = false }
  }
  function dispose() { alive = false; requestId++; refreshing.value = false }
  return { overview, snapshotAt, error, busy, refreshing, refresh, switchCategory, dispose }
}
