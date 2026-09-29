<template>
  <ElDialog :model-value="!!item" :title="t('新闻概括')" width="min(740px, calc(100vw - 32px))"
    align-center append-to-body destroy-on-close class="news-brief-dialog" @update:model-value="value => { if (!value) $emit('close') }">
    <template v-if="item">
      <div class="brief-meta"><strong>{{ item.source }}</strong><time>{{ formatDate(item.publishedAt) }}</time><span>{{ t('中文概括') }}</span></div>
      <div v-if="loading" class="brief-loading" role="status"><span class="brief-spinner"></span><h3>{{ t('正在整理中文概括…') }}</h3><p>{{ t('首次生成需要一点时间，完成后会自动显示。') }}</p></div>
      <div v-else-if="error" class="brief-error" role="alert"><h3>{{ t('暂时无法生成中文概括') }}</h3><p>{{ t(error) }}</p><button type="button" @click="loadBrief">{{ t('重新生成') }}</button></div>
      <article v-else-if="brief" class="brief-content">
        <h2>{{ brief.title }}</h2>
        <section :aria-label="t('中文新闻概括')"><h3>{{ t('新闻概括') }}</h3><p>{{ brief.summary }}</p></section>
        <p class="brief-basis">{{ brief.basis === 'title' ? t('该来源目前仅提供标题，以上仅整理标题中的已知信息，暂无更多报道细节。') : t('由本地 AI 根据媒体提供的新闻摘要整理，完整报道请查看官网原文。') }}</p>
      </article>
      <footer class="brief-footer">
        <small v-if="!official.article">{{ t('该条目暂未提供官网原文直链，可前往媒体官网查找报道。') }}</small>
        <small v-else>{{ t('完整报道来自 {source}', { source: item.source }) }}</small>
        <a v-if="official.url" :href="official.url" target="_blank" rel="noopener noreferrer" class="brief-official">{{ official.article ? t('查看官网原文') : t('打开 {source} 官网', { source: item.source }) }} ↗</a>
      </footer>
    </template>
  </ElDialog>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import axios from 'axios'
import { newsOfficialLink } from '../utils/newsOfficialLink'

const props = defineProps({ item: Object })
defineEmits(['close'])
const brief = ref(null), loading = ref(false), error = ref('')
const official = computed(() => newsOfficialLink(props.item))
let requestId = 0, controller

async function loadBrief() {
  const id = ++requestId
  controller?.abort()
  brief.value = null
  error.value = ''
  loading.value = !!props.item
  if (!props.item) return
  controller = new AbortController()
  try {
    const response = await axios.post('/api/dashboard/us-news/brief', { source: props.item.source, url: props.item.url }, { signal: controller.signal, timeout: 90000 })
    if (id === requestId) brief.value = response.data
  } catch (failure) {
    if (id === requestId && !axios.isCancel(failure)) {
      error.value = failure.response?.status === 404 ? '这条新闻已不在当前榜单中，请关闭卡片并更新新闻。' : '本地 AI 暂时未能完成整理，请稍后重试，也可以通过下方按钮阅读官网报道。'
    }
  } finally { if (id === requestId) loading.value = false }
}
watch(() => props.item, loadBrief, { immediate: true })
onBeforeUnmount(() => { requestId++; controller?.abort() })

function formatDate(value) {
  const date = new Date(value)
  return value && !Number.isNaN(date.getTime()) ? new Intl.DateTimeFormat(dateLocale.value, { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date) : ''
}
</script>

<style scoped>
.brief-meta{display:flex;align-items:center;flex-wrap:wrap;gap:12px;font-size:12px;color:#8793a5}.brief-meta strong{color:#4275c4}.brief-meta>span{margin-left:auto;padding:5px 9px;background:#eaf1ff;border-radius:7px;color:#4373b5;font-size:11px}.brief-content h2{color:#20324c;font-size:26px;line-height:1.5;margin:20px 0 24px;overflow-wrap:anywhere}.brief-content section{padding:23px;border-radius:18px;background:#edf4ff}.brief-content h3{margin:0 0 12px;font-size:13px;color:#3e6fb3}.brief-content section p{margin:0;color:#41546f;white-space:pre-wrap;line-height:1.95;font-size:16px;overflow-wrap:anywhere}.brief-content .brief-basis{margin:15px 0 0;font-size:12px;color:#8b97a8;line-height:1.7}.brief-footer{display:flex;align-items:center;gap:20px;justify-content:space-between;margin-top:25px;padding-top:20px;border-top:1px solid #e6edf6}.brief-footer small{max-width:390px;font-size:12px;line-height:1.7;color:#8d99aa}.brief-official{flex-shrink:0;border-radius:12px;background:linear-gradient(145deg,#4a8cff,#235fca);color:#fff;padding:13px 18px;text-decoration:none;font-size:13px;font-weight:750}.brief-loading,.brief-error{padding:50px 16px;text-align:center;color:#6f7f94}.brief-loading h3,.brief-error h3{font-size:18px;color:#364e70}.brief-loading p,.brief-error p{font-size:13px;line-height:1.8}.brief-error button{border:0;padding:10px 16px;border-radius:10px;color:#2868c5;background:#eaf1ff;cursor:pointer}.brief-spinner{display:inline-block;width:28px;height:28px;border:3px solid #dee8f6;border-top-color:#3c7edf;border-radius:50%;animation:brief-spin .8s linear infinite}.brief-official:focus-visible,.brief-error button:focus-visible{outline:2px solid #397ef0;outline-offset:3px}@keyframes brief-spin{to{transform:rotate(360deg)}}@media(max-width:560px){.brief-content h2{font-size:21px}.brief-content section{padding:17px}.brief-content section p{font-size:15px}.brief-footer{align-items:flex-start;flex-direction:column;gap:12px}.brief-official{align-self:flex-end}}
</style>

<style>
.el-dialog.news-brief-dialog{border-radius:26px;padding:28px;background:#fbfcff;max-height:calc(100dvh - 32px);overflow:auto}.news-brief-dialog .el-dialog__header{padding:0 32px 22px 0}.news-brief-dialog .el-dialog__title{font-size:14px;color:#71829a;font-weight:650}.news-brief-dialog .el-dialog__headerbtn{top:14px;right:14px}
</style>
