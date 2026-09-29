<template>
  <section class="us-news-panel">
    <div class="us-news-intro">
      <div>
        <span class="section-label">U.S. MEDIA · LIVE SOURCES</span>
        <h2>{{ t('美国媒体新闻') }}</h2>
        <p>{{ t('综合 CNN、NBC、ABC、CBS 与 NPR，并支持查看每家媒体最近10条国际新闻。') }}</p>
      </div>
      <div class="intro-actions">
        <div class="language-switch" role="group" :aria-label="t('新闻语言')">
          <button type="button" :class="{ active: language === 'zh' }" @click="setLanguage('zh')">
            {{ translating && language === 'zh' ? t('AI 翻译中…') : t('中文') }}
          </button>
          <button type="button" :class="{ active: language === 'en' }" @click="setLanguage('en')">English</button>
        </div>
        <button class="refresh-news-button" type="button" :disabled="loading" @click="$emit('refresh')">{{ loading ? t('更新中…') : t('更新新闻') }}</button>
      </div>
    </div>

    <div class="media-health">
      <span v-for="source in sources" :key="source.id" :class="{ offline: !source.available }">
        <i></i>{{ source.name }}<small>{{ t(source.channel) }}</small>
      </span>
    </div>

    <div class="media-tabs" role="tablist" :aria-label="t('美国媒体来源')">
      <button type="button" :class="{ active: activeId === 'combined' }" @click="setActiveId('combined')">{{ t('综合热榜') }}</button>
      <button v-for="source in sources" :key="source.id" type="button" :class="{ active: activeId === source.id, offline: !source.available }" @click="setActiveId(source.id)">
        {{ shortName(source.name) }}
      </button>
    </div>

    <div v-if="loading && !combined.length" class="center-state"><span class="spinner"></span><p>{{ t('正在连接美国媒体…') }}</p></div>
    <div v-else-if="activeId !== 'combined' && activeSource && !activeSource.available" class="source-unavailable">
      <strong>{{ t('{source} 暂时无法连接', { source: activeSource.name }) }}</strong>
      <p>{{ t(activeSource.status) }}</p>
    </div>
    <div v-else class="news-board">
      <header>
        <div>
          <span>{{ activeId === 'combined' ? 'COMBINED TOP 10' : t(activeSource?.channel) }}</span>
          <h3>{{ activeId === 'combined' ? t('多家媒体综合热榜') : activeSource?.name }}</h3>
        </div>
        <em>{{ translating && language === 'zh' ? t('本地 AI 正在翻译') : activeId === 'combined' ? t('{count} 家在线', { count: onlineCount }) : t(activeSource?.status) }}</em>
      </header>

      <p v-if="translationError" class="translation-error">{{ t(translationError) }}</p>
      <div v-if="!displayItems.length" class="source-unavailable"><strong>{{ t('暂时没有可显示的新闻') }}</strong></div>
      <ol v-else>
        <li v-for="(item, index) in displayItems" :key="`${item.source}-${item.url}`">
          <button type="button" class="news-entry" :aria-label="t('查看新闻概括：{title}', { title: item.title })" @click="openNews(visibleItems[index])">
          <b>{{ String(index + 1).padStart(2, '0') }}</b>
          <div>
            <h4>{{ item.title }}</h4>
            <p v-if="displaySummary(item)">{{ item.summary }}</p>
            <footer><span>{{ item.source }}</span><time>{{ formatDate(item.publishedAt) }}</time></footer>
          </div>
          <i aria-hidden="true">›</i>
          </button>
        </li>
      </ol>
    </div>
    <NewsBriefDialog :item="selectedNews" @close="selectedNews = null" />
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { computed, ref } from 'vue'
import axios from 'axios'
import NewsBriefDialog from './NewsBriefDialog.vue'

const props = defineProps({
  combined: { type: Array, default: () => [] },
  sources: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['refresh'])

const activeId = ref('combined')
const selectedNews = ref(null)
const language = ref('en')
const translationCache = ref(new Map())
const translationPending = ref(0)
const translationError = ref('')
const pendingKeys = new Set()
const activeSource = computed(() => props.sources.find(source => source.id === activeId.value))
const visibleItems = computed(() => activeId.value === 'combined' ? props.combined : (activeSource.value?.items || []))
const translating = computed(() => translationPending.value > 0)
const displayItems = computed(() => {
  if (language.value === 'en') return visibleItems.value
  return visibleItems.value.map(item => translationCache.value.get(itemKey(item)) || item)
})
const onlineCount = computed(() => props.sources.filter(source => source.available).length)

function itemKey(item) {
  return `${item?.source || ''}|${item?.url || ''}|${item?.title || ''}`
}

function setActiveId(id) {
  activeId.value = id
  if (language.value === 'zh') ensureTranslations(visibleItems.value)
}

function setLanguage(nextLanguage) {
  language.value = nextLanguage
  translationError.value = ''
  if (nextLanguage === 'zh') ensureTranslations(visibleItems.value)
}

async function ensureTranslations(items) {
  const originals = (items || []).filter(item => {
    const key = itemKey(item)
    return !translationCache.value.has(key) && !pendingKeys.has(key)
  })
  if (!originals.length) return

  originals.forEach(item => pendingKeys.add(itemKey(item)))
  translationPending.value += 1
  translationError.value = ''
  try {
    const response = await axios.post('/api/dashboard/us-news/translate', { items: originals })
    const translated = Array.isArray(response.data?.items) ? response.data.items : []
    const nextCache = new Map(translationCache.value)
    originals.forEach((item, index) => {
      if (translated[index]?.title) nextCache.set(itemKey(item), translated[index])
    })
    translationCache.value = nextCache
  } catch (error) {
    console.warn('Failed to translate U.S. news', error)
    translationError.value = '本地 AI 翻译暂时不可用，请稍后重试。'
  } finally {
    originals.forEach(item => pendingKeys.delete(itemKey(item)))
    translationPending.value = Math.max(0, translationPending.value - 1)
  }
}

function shortName(name) {
  return String(name || '').replace(' News', '')
}

function openNews(item) {
  selectedNews.value = item
}

function displaySummary(item) {
  const title = String(item?.title || '').trim().toLowerCase()
  const summary = String(item?.summary || '').trim().toLowerCase()
  if (!summary) return false
  return !(summary.startsWith(title) && summary.length <= title.length + 24)
}

function formatDate(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(dateLocale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}
</script>

<style scoped>
.us-news-panel { width: min(1120px, 100%); margin: 0 auto; }
.us-news-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 20px; }
.section-label { color: #4c7fcf; font-size: 11px; font-weight: 900; letter-spacing: .15em; }
.us-news-intro h2 { margin: 5px 0; font-size: clamp(36px,5vw,58px); letter-spacing: -.055em; }
.us-news-intro p { max-width: 720px; margin: 0; color: #788397; line-height: 1.65; }
.intro-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 10px; }
.language-switch { display: flex; gap: 3px; padding: 4px; border: 1px solid rgba(255,255,255,.9); border-radius: 14px; background: rgba(255,255,255,.58); box-shadow: 0 8px 22px rgba(43,66,100,.08); }
.language-switch button { border: 0; border-radius: 10px; padding: 8px 12px; color: #6a7688; font-size: 12px; font-weight: 850; background: transparent; transition: .18s ease; }
.language-switch button.active { color: #fff; background: linear-gradient(145deg,#4a8cff,#235fca); box-shadow: 0 5px 14px rgba(43,101,202,.22); }
.refresh-news-button { border: 0; border-radius: 13px; padding: 11px 17px; color: #fff; font-size: 13px; font-weight: 800; background: linear-gradient(145deg,#4a8cff,#235fca); box-shadow: 0 8px 24px rgba(43,101,202,.2); }
.refresh-news-button:disabled { cursor: wait; opacity: .68; }
.media-health { display: flex; gap: 8px; overflow-x: auto; margin-bottom: 14px; }
.media-health > span { display: flex; flex: 0 0 auto; align-items: center; gap: 6px; border-radius: 999px; padding: 7px 10px; color: #536177; font-size: 11px; font-weight: 800; background: rgba(255,255,255,.65); }
.media-health i { width: 7px; height: 7px; border-radius: 50%; background: #24b46b; box-shadow: 0 0 0 3px rgba(36,180,107,.12); }
.media-health small { color: #9aa3b0; font-size: 9px; font-weight: 600; }
.media-health .offline i { background: #a4acb8; box-shadow: none; }
.media-tabs { display: flex; gap: 7px; overflow-x: auto; margin-bottom: 18px; padding: 5px; border: 1px solid rgba(255,255,255,.82); border-radius: 16px; background: rgba(255,255,255,.48); }
.media-tabs button { flex: 0 0 auto; border: 0; border-radius: 11px; padding: 9px 15px; color: #657187; font-weight: 800; background: transparent; }
.media-tabs button.active { color: #245fb9; background: #fff; box-shadow: 0 5px 16px rgba(55,75,110,.1); }
.media-tabs button.offline { color: #a3aab4; }
.news-board { overflow: hidden; border: 1px solid rgba(255,255,255,.9); border-radius: 28px; background: rgba(255,255,255,.8); box-shadow: 0 16px 42px rgba(49,64,90,.1); }
.news-board > header { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 23px 27px; border-bottom: 1px solid rgba(116,132,154,.14); background: linear-gradient(135deg,rgba(31,50,80,.98),rgba(18,30,49,.98)); }
.news-board header span { color: #9db4d7; font-size: 9px; font-weight: 900; letter-spacing: .14em; }
.news-board header h3 { margin: 3px 0 0; color: #fff; font-size: 24px; }
.news-board header em { border-radius: 999px; padding: 7px 11px; color: #dce9ff; font-size: 10px; font-style: normal; font-weight: 800; background: rgba(255,255,255,.1); }
.news-board ol { padding: 0; margin: 0; list-style: none; }
.news-board li { border-bottom: 1px solid rgba(116,132,154,.13); }
.news-entry { display: grid; width: 100%; grid-template-columns: 42px minmax(0,1fr) auto; gap: 16px; align-items: center; padding: 18px 25px; border: 0; background: transparent; text-align: left; font: inherit; cursor: pointer; transition: background .18s; }
.news-entry:focus-visible { outline: 2px solid #397ef0; outline-offset: -3px; }
.news-board li:last-child { border-bottom: 0; }
.news-board li:hover { background: rgba(231,240,253,.7); }
.news-entry > b { color: #3673cf; font-size: 15px; }
.news-board h4 { margin: 0; color: #223047; font-size: 15px; line-height: 1.45; }
.news-board p { display: -webkit-box; overflow: hidden; margin: 6px 0; color: #6f7d91; font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
.news-board footer { display: flex; gap: 12px; color: #9ba5b4; font-size: 10px; }
.news-board footer span { color: #56719a; font-weight: 800; }
.news-entry > i { color: #98a3b2; font-size: 20px; font-style: normal; }
.translation-error { margin: 0; padding: 11px 24px; color: #a64848; font-size: 12px; font-weight: 700; background: #fff0f0; }
.source-unavailable,.center-state { display: grid; min-height: 320px; place-items: center; align-content: center; color: #7b8799; text-align: center; }
.source-unavailable p { margin: 7px 0 0; }
.spinner { width: 34px; height: 34px; border: 4px solid #dfe6ef; border-top-color: #3878d7; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 700px) {
  .us-news-intro { align-items: flex-start; flex-direction: column; }
  .intro-actions { width: 100%; justify-content: space-between; }
  .media-health small { display: none; }
  .news-board > header { align-items: flex-start; padding: 19px; }
  .news-entry { grid-template-columns: 30px minmax(0,1fr); padding: 16px 14px; }
  .news-entry > i { display: none; }
}
</style>
