<template>
  <section class="personal-watch" :aria-label="t('我的追剧清单')">
    <header class="list-intro">
      <div><span class="eyebrow">MY WATCHLIST</span><h3>{{ t('把下一部好剧，先记在这里。') }}</h3><p>{{ t('想看的记下来，正在看的记进度。') }}</p></div>
      <div class="watch-count"><strong>{{ entries.filter(item => item.status !== 'COMPLETED').length }}</strong><span>{{ t('部待看 / 在看') }}</span></div>
    </header>

    <form class="quick-add" @submit.prevent="addTitle">
      <select v-model="newKind" :aria-label="t('添加作品类型')" :disabled="saving"><option value="TV">{{ t('电视剧') }}</option><option value="MOVIE">{{ t('电影') }}</option><option value="VARIETY">{{ t('综艺') }}</option></select>
      <input ref="titleInput" v-model="newTitle" :aria-label="t('想看的片名')" maxlength="300" :placeholder="t('下一部想看什么？输入片名…')" autocomplete="off" :disabled="saving">
      <button class="primary" type="submit" :disabled="saving || !newTitle.trim()">{{ saving ? t('保存中…') : t('＋ 添加到清单') }}</button>
    </form>

    <div class="list-tools">
      <div class="status-tabs" role="group" :aria-label="t('观看状态筛选')">
        <button v-for="filter in filters" :key="filter.key" type="button" :class="{ active: statusFilter === filter.key }" :aria-pressed="statusFilter === filter.key" @click="statusFilter = filter.key">{{ t(filter.label) }}<small>{{ filter.key === 'ALL' ? entries.length : entries.filter(item => item.status === filter.key).length }}</small></button>
      </div>
      <div class="search-tools">
        <select v-model="kindFilter" :aria-label="t('筛选作品类型')"><option value="ALL">{{ t('全部类型') }}</option><option value="TV">{{ t('电视剧') }}</option><option value="MOVIE">{{ t('电影') }}</option><option value="VARIETY">{{ t('综艺') }}</option></select>
        <input v-model="search" type="search" :aria-label="t('搜索追剧清单')" :placeholder="t('搜索片名或备注')">
      </div>
    </div>
    <div class="sync-note" :class="{ failed: error }" role="status"><i></i>{{ error ? t(error) : t(loading ? '正在读取清单…' : '已保存到本机 · 不同浏览器自动同步') }}<button v-if="error" type="button" @click="loadEntries()">{{ t('重试') }}</button></div>

    <div v-if="loading && !entries.length" class="empty-list">{{ t('正在读取你的追剧清单…') }}</div>
    <ul v-else-if="visibleEntries.length" class="watch-items">
      <li v-for="item in visibleEntries" :key="item.id" class="watch-row" :data-watch-id="item.id">
        <template v-if="editingId !== item.id">
          <div class="watch-symbol" :class="item.kind.toLowerCase()">{{ item.kind !== 'MOVIE' ? '▣' : '▶' }}</div>
          <div class="watch-copy">
            <div class="title-line"><h4>{{ item.title }}</h4><span :class="['state-pill', item.status.toLowerCase()]">{{ t(statusLabel(item.status)) }}</span></div>
            <div class="watch-meta"><span>{{ { TV: t('电视剧'), MOVIE: t('电影'), VARIETY: t('综艺') }[item.kind] }}</span><span v-if="item.kind !== 'MOVIE'">{{ item.currentEpisode ? t('已看至第 {count} 集', { count: item.currentEpisode }) : t('尚未开始') }}{{ item.totalEpisodes ? t(' / 共 {count} 集', { count: item.totalEpisodes }) : '' }}</span><a v-if="item.url" :href="item.url" target="_blank" rel="noopener noreferrer">{{ t(item.source || '相关链接') }} ↗</a></div>
            <div v-if="item.kind !== 'MOVIE' && item.totalEpisodes" class="progress-track" role="progressbar" :aria-label="t('{title} 观看进度', { title: item.title })" :aria-valuenow="item.currentEpisode" :aria-valuemax="item.totalEpisodes" aria-valuemin="0"><span :style="{ width: `${Math.min(100, item.currentEpisode / item.totalEpisodes * 100)}%` }"></span></div>
            <p v-if="item.notes" class="watch-notes">{{ item.notes }}</p>
          </div>
          <div class="row-actions">
            <button v-if="item.status !== 'COMPLETED'" class="complete" type="button" :disabled="saving" :aria-label="t('将 {title} 标为已看完', { title: item.title })" @click="completeEntry(item)">{{ t('✓ 已看完') }}</button>
            <button type="button" :disabled="saving" :aria-label="t('编辑 {title}', { title: item.title })" @click="startEdit(item)">{{ t('修改') }}</button>
            <button class="danger" type="button" :disabled="saving" :aria-label="t('删除 {title}', { title: item.title })" @click="removeEntry(item)">{{ t('删除') }}</button>
          </div>
        </template>
        <form v-else class="edit-watch" @submit.prevent="saveEdit" @keydown.esc="cancelEdit">
          <div class="edit-heading"><strong>{{ t('编辑追剧记录') }}</strong><small>{{ t('保存后在其他浏览器同步更新') }}</small></div>
          <label class="wide">{{ t('片名') }}<input v-model="draft.title" :aria-label="t('编辑片名')" maxlength="300" required :disabled="saving"></label>
          <label>{{ t('类型') }}<select v-model="draft.kind" :aria-label="t('编辑作品类型')" :disabled="saving"><option value="TV">{{ t('电视剧') }}</option><option value="MOVIE">{{ t('电影') }}</option><option value="VARIETY">{{ t('综艺') }}</option></select></label>
          <label>{{ t('观看状态') }}<select v-model="draft.status" :aria-label="t('观看状态')" :disabled="saving"><option value="PLANNED">{{ t('想看') }}</option><option value="WATCHING">{{ t('在看') }}</option><option value="COMPLETED">{{ t('已看完') }}</option></select></label>
          <template v-if="draft.kind !== 'MOVIE'"><label>{{ t('已看集数') }}<input v-model="draft.currentEpisode" type="number" min="0" :max="draft.totalEpisodes || 100000" step="1" :aria-label="t('已看集数')" :disabled="saving"></label><label>{{ t('总集数（选填）') }}<input v-model="draft.totalEpisodes" type="number" min="1" max="100000" step="1" :aria-label="t('总集数')" :placeholder="t('待定')" :disabled="saving"></label></template>
          <label class="wide">{{ t('备注') }}<textarea v-model="draft.notes" :aria-label="t('追剧备注')" maxlength="2000" rows="3" :placeholder="t('比如：朋友推荐、周末看、从第二季开始…')" :disabled="saving"></textarea></label>
          <label class="wide">{{ t('相关链接（选填）') }}<input v-model="draft.url" :aria-label="t('相关链接')" type="url" maxlength="2048" placeholder="https://…" :disabled="saving"></label>
          <div class="edit-actions wide"><button type="button" :disabled="saving" @click="cancelEdit">{{ t('取消') }}</button><button class="primary" type="submit" :disabled="saving">{{ saving ? t('保存中…') : t('保存修改') }}</button></div>
        </form>
      </li>
    </ul>
    <div v-else class="empty-list"><div class="empty-icon">▣</div><h4>{{ entries.length ? t('没有符合条件的作品') : t('你的下一部好剧，从这里开始') }}</h4><p>{{ entries.length ? t('换个筛选条件，或搜索其他片名。') : t('在上方输入片名，或到热门榜单点「＋ 想看」。') }}</p><button v-if="!entries.length" type="button" @click="$emit('browse')">{{ t('去热门榜单逛逛 →') }}</button><button v-else type="button" @click="resetFilters">{{ t('显示全部') }}</button></div>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({ active: { type: Boolean, default: true } })
const emit = defineEmits(['items-change', 'browse'])
const endpoint = '/api/dashboard/watchlist'
const entries = ref([]), newTitle = ref(''), newKind = ref('TV'), titleInput = ref(null)
const statusFilter = ref('ALL'), kindFilter = ref('ALL'), search = ref('')
const loading = ref(false), saving = ref(false), error = ref(''), editingId = ref(null), draft = ref({})
const filters = [{ key: 'ALL', label: '全部' }, { key: 'PLANNED', label: '想看' }, { key: 'WATCHING', label: '在看' }, { key: 'COMPLETED', label: '已看完' }]
let timer, requestGeneration = 0, disposed = false
const visibleEntries = computed(() => entries.value.filter(item => (statusFilter.value === 'ALL' || item.status === statusFilter.value) && (kindFilter.value === 'ALL' || item.kind === kindFilter.value) && `${item.title} ${item.notes || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
function statusLabel(status) { return filters.find(item => item.key === status)?.label || status }
function applyEntries(value) { entries.value = value; emit('items-change', value) }
function replaceEntry(item) { applyEntries([item, ...entries.value.filter(value => value.id !== item.id)]) }
function resetFilters() { statusFilter.value = 'ALL'; kindFilter.value = 'ALL'; search.value = '' }
function message(failure, fallback) { return t(failure.response?.data?.message || fallback) }
async function loadEntries() {
  if (saving.value || editingId.value || loading.value) return
  const generation = ++requestGeneration
  loading.value = true
  try {
    const { data } = await axios.get(endpoint, { timeout: 15000 })
    if (!disposed && generation === requestGeneration) { applyEntries(data); error.value = '' }
  } catch { if (!disposed && generation === requestGeneration) error.value = '暂时无法连接后端，清单未能同步。' }
  finally { if (!disposed) loading.value = false }
}
async function createEntry(value) {
  if (saving.value) return false
  saving.value = true; requestGeneration++
  try {
    const { data } = await axios.post(endpoint, value, { timeout: 15000 })
    replaceEntry(data); error.value = ''; resetFilters()
    ElMessage.success(t('已加入我的清单'))
    return true
  } catch (failure) { ElMessage.error(message(failure, '添加失败，输入内容已保留，请重试')); return false }
  finally { saving.value = false }
}
async function addTitle() {
  if (!newTitle.value.trim()) return
  if (await createEntry({ title: newTitle.value.trim(), kind: newKind.value, status: 'PLANNED' })) newTitle.value = ''
}
async function addFromRanking(item, kind) {
  return createEntry({ title: item.title, kind, status: 'PLANNED', url: item.url || '', source: item.source || '' })
}
function startEdit(item) { requestGeneration++; editingId.value = item.id; draft.value = { ...item, totalEpisodes: item.totalEpisodes ?? '' } }
function cancelEdit() { if (!saving.value) { editingId.value = null; draft.value = {}; loadEntries() } }
function payload(value) {
  const current = value.kind !== 'MOVIE' ? Number(value.currentEpisode || 0) : 0
  const total = value.kind !== 'MOVIE' && value.totalEpisodes !== '' && value.totalEpisodes != null ? Number(value.totalEpisodes) : null
  if (!value.title.trim()) throw new Error('请填写片名')
  if (!Number.isInteger(current) || current < 0 || current > 100000 || (total !== null && (!Number.isInteger(total) || total < 1 || total > 100000 || current > total))) throw new Error('请检查集数，已看集数不能超过总集数')
  return { ...value, title: value.title.trim(), currentEpisode: current, totalEpisodes: total }
}
async function updateEntry(item) {
  if (saving.value) return false
  let body
  try { body = payload(item) } catch (failure) { ElMessage.warning(t(failure.message)); return false }
  saving.value = true; requestGeneration++
  try {
    const { data } = await axios.put(`${endpoint}/${encodeURIComponent(item.id)}`, body, { timeout: 15000 })
    replaceEntry(data); error.value = ''; return true
  } catch (failure) { ElMessage.error(message(failure, '保存失败，修改内容已保留，请重试')); return false }
  finally { saving.value = false }
}
async function saveEdit() {
  if (await updateEntry(draft.value)) { editingId.value = null; draft.value = {}; ElMessage.success(t('追剧记录已更新')) }
}
async function completeEntry(item) {
  if (await updateEntry({ ...item, status: 'COMPLETED', currentEpisode: item.totalEpisodes ?? item.currentEpisode })) ElMessage.success(t('已标为看完，可以在「已看完」中找到'))
}
async function removeEntry(item) {
  try { await ElMessageBox.confirm(t('确定从清单删除《{title}》吗？', { title: item.title }), t('删除追剧记录'), { confirmButtonText: t('删除'), cancelButtonText: t('取消'), type: 'warning' }) }
  catch { return }
  if (saving.value) return
  saving.value = true; requestGeneration++
  try {
    await axios.delete(`${endpoint}/${encodeURIComponent(item.id)}`, { params: { version: item.version }, timeout: 15000 })
    applyEntries(entries.value.filter(value => value.id !== item.id)); error.value = ''; ElMessage.success(t('已从清单删除'))
  } catch (failure) { ElMessage.error(message(failure, '删除失败，请重试')) }
  finally { saving.value = false }
}
defineExpose({ addFromRanking })
onMounted(() => { loadEntries(); timer = setInterval(() => { if (props.active && !document.hidden) loadEntries() }, 5000) })
onBeforeUnmount(() => { disposed = true; requestGeneration++; clearInterval(timer) })
</script>

<style scoped>
.personal-watch{max-width:1060px;margin:0 auto}.list-intro{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:24px 0}.eyebrow{font-size:10px;font-weight:850;letter-spacing:.16em;color:#bc596d}.list-intro h3{font-size:clamp(22px,3vw,32px);letter-spacing:-.04em;margin:6px 0}.list-intro p{color:#7a8598;margin:0;font-size:13px}.watch-count{display:grid;text-align:right;white-space:nowrap}.watch-count strong{font-size:38px;line-height:1.1;color:#cc425b}.watch-count span{font-size:11px;color:#8a94a5}
button,input,select,textarea{font:inherit;box-sizing:border-box}button{cursor:pointer;border:0;border-radius:11px;padding:10px 14px;font-size:12px;font-weight:750;color:#5c687b;background:#eaf0f7}button:disabled{opacity:.5;cursor:not-allowed}input,select,textarea{color:#202c3d;background:white;border:1px solid #e0e6ef;border-radius:12px;padding:12px;min-width:0}input:focus,select:focus,textarea:focus{outline:2px solid #e58a9b;outline-offset:2px}button:focus-visible{outline:2px solid #397ef0;outline-offset:3px}.primary{color:#fff;background:linear-gradient(135deg,#ee6577,#c83955)}
.quick-add{display:grid;grid-template-columns:110px 1fr auto;gap:10px;background:#ffffffb8;border:1px solid white;border-radius:22px;padding:12px;box-shadow:0 12px 36px #2336540b}.quick-add input{width:100%}.quick-add .primary{padding:0 22px}
.list-tools{display:flex;flex-wrap:wrap;justify-content:space-between;gap:14px;margin:24px 0 12px}.status-tabs{display:flex;gap:3px;padding:4px;border-radius:15px;background:#e5ebf3}.status-tabs button{background:transparent;color:#8490a3;display:flex;align-items:center;gap:6px}.status-tabs button.active{background:white;color:#c13c57;box-shadow:0 3px 9px #293e5710}.status-tabs small{font-size:10px;background:#edf1f6;border-radius:6px;padding:1px 5px}.search-tools{display:flex;gap:8px}.search-tools input{width:180px;font-size:12px}.search-tools select{font-size:12px}
.sync-note{display:flex;align-items:center;gap:8px;color:#8b96a6;font-size:11px;margin:14px 4px}.sync-note i{width:6px;height:6px;background:#22b580;border-radius:50%}.sync-note.failed{color:#ba6640}.sync-note.failed i{background:#db8e50}.sync-note button{padding:3px 9px}.watch-items{list-style:none;padding:0;display:grid;gap:12px}.watch-row{display:flex;align-items:flex-start;gap:16px;background:#ffffffdc;border:1px solid white;border-radius:22px;padding:22px;box-shadow:0 9px 30px #23365408}.watch-symbol{width:46px;height:52px;display:grid;place-items:center;background:#edf0ff;border-radius:14px;color:#627cc7;font-size:22px;flex-shrink:0}.watch-symbol.movie{color:#cb5971;background:#fcecf0}.watch-copy{flex:1;min-width:0}.title-line{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.title-line h4{font-size:17px;margin:2px 0 8px;overflow-wrap:anywhere}.state-pill{padding:4px 8px;border-radius:7px;background:#f0f3f8;color:#8391a5;font-size:10px}.state-pill.watching{background:#eaf1ff;color:#3677da}.state-pill.completed{background:#e1f5ec;color:#28916b}.watch-meta{display:flex;gap:12px;flex-wrap:wrap;font-size:11px;color:#8d98a8}.watch-meta a{color:#678ab5;text-decoration:none}.watch-notes{font-size:13px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere;color:#647187;margin:12px 0 0}.progress-track{height:4px;width:min(260px,100%);background:#eaf0f7;border-radius:5px;margin-top:12px;overflow:hidden}.progress-track span{display:block;height:100%;background:#84a4e1}.row-actions{display:flex;gap:5px;flex-wrap:wrap;justify-content:flex-end}.row-actions button{padding:8px 11px}.row-actions .complete{color:#2b9a74;background:#e9f7ef}.row-actions .danger{color:#cb5361;background:#fff0f1}
.edit-watch{width:100%;display:grid;grid-template-columns:1fr 1fr;gap:16px}.edit-watch label{display:grid;gap:7px;font-size:12px;color:#728196}.edit-watch input,.edit-watch select,.edit-watch textarea{width:100%}.edit-heading{grid-column:1/-1;display:flex;justify-content:space-between;gap:12px;color:#2c3b51}.edit-heading small{color:#8c97a7;font-size:11px}.wide{grid-column:1/-1}.edit-actions{display:flex;gap:8px;justify-content:flex-end}.empty-list{text-align:center;padding:65px 16px;color:#8b96a8}.empty-icon{font-size:30px;color:#cc647d;background:#f9e8ee;border-radius:22px;width:68px;height:68px;display:grid;place-items:center;margin:auto}.empty-list h4{color:#4a5870;font-size:18px;margin:18px 0 6px}.empty-list p{font-size:13px}.empty-list button{margin-top:8px;background:#fff;color:#bf4d65}
@media(max-width:760px){.watch-row{flex-wrap:wrap;padding:16px}.row-actions{width:100%;justify-content:flex-end}.quick-add{grid-template-columns:100px 1fr}.quick-add .primary{grid-column:1/-1;min-height:42px}.list-tools{flex-direction:column}.search-tools input{flex:1}.status-tabs button{flex:1;padding:10px}.edit-heading{flex-direction:column}.watch-count strong{font-size:30px}}
.quick-add{grid-template-columns:140px 1fr auto}@media(max-width:760px){.quick-add{grid-template-columns:130px 1fr}}
</style>
