<template>
  <section class="news-panel" :class="region">
    <div class="news-intro">
      <div>
        <span class="section-label">{{ region === 'china' ? 'WEIBO · REALTIME TOP 10' : 'U.S. MEDIA · COMBINED TOP 10' }}</span>
        <h2>{{ region === 'china' ? t('微博热搜') : t('美国媒体综合热榜') }}</h2>
        <p>{{ region === 'china' ? t('微博实时热度榜前 10 条，点击可打开对应话题。') : t('综合 CNN、NBC News、ABC News、CBS News 与 NPR 的报道覆盖度和时效性。') }}</p>
      </div>
      <button type="button" :disabled="loading" @click="$emit('refresh')">{{ loading ? t('更新中…') : t('更新新闻') }}</button>
    </div>

    <div v-if="loading && !items.length" class="news-loading">
      <span></span><p>{{ t('正在连接新闻源…') }}</p>
    </div>
    <div v-else-if="!items.length" class="news-loading"><p>{{ t('新闻源暂时不可用，请稍后刷新。') }}</p></div>
    <div v-else class="news-list">
      <article v-for="(item, index) in items" :key="item.url" class="news-item" @click="openNews(item.url)">
        <span class="rank">{{ String(index + 1).padStart(2, '0') }}</span>
        <div class="story-copy">
          <h3>{{ item.title }}</h3>
          <p v-if="item.summary">{{ item.summary }}</p>
          <div class="story-meta"><span>{{ item.source }}</span><time>{{ formatDate(item.publishedAt) }}</time></div>
        </div>
        <span class="story-arrow">↗</span>
      </article>
    </div>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
defineProps({
  region: { type: String, default: 'china' },
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['refresh'])

function openNews(url) {
  if (url) window.open(url, '_blank', 'noopener,noreferrer')
}

function formatDate(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(dateLocale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}
</script>

<style scoped>
.news-panel { width: min(1040px, 100%); margin: 0 auto; }
.news-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 26px; }
.section-label { color: #c26137; font-size: 11px; font-weight: 900; letter-spacing: .15em; }
.world .section-label { color: #4c7fcf; }
.news-intro h2 { margin: 5px 0; font-size: clamp(36px, 5vw, 58px); letter-spacing: -.055em; }
.news-intro p { margin: 0; color: #788397; }
.news-intro button { border: 0; border-radius: 13px; padding: 11px 17px; color: #45536a; font-size: 13px; font-weight: 800; background: rgba(255,255,255,.76); box-shadow: 0 8px 24px rgba(52,65,89,.09); }
.news-list { display: grid; gap: 12px; }
.news-item { display: grid; grid-template-columns: 54px 1fr auto; gap: 18px; align-items: center; padding: 21px 23px; border: 1px solid rgba(255,255,255,.9); border-radius: 23px; background: rgba(255,255,255,.79); box-shadow: 0 12px 32px rgba(51,64,87,.075); cursor: pointer; transition: transform .18s, box-shadow .18s; }
.news-item:hover { transform: translateY(-3px); box-shadow: 0 18px 38px rgba(51,64,87,.13); }
.rank { color: #bf633f; font-size: 20px; font-weight: 900; }
.world .rank { color: #3975cf; }
.story-copy { min-width: 0; }
.story-copy h3 { margin: 0; color: #1f2a3d; font-size: 17px; line-height: 1.45; }
.story-copy p { display: -webkit-box; overflow: hidden; margin: 7px 0; color: #68758a; font-size: 13px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.story-meta { display: flex; gap: 12px; color: #98a1af; font-size: 11px; }
.story-meta span { color: #5f718c; font-weight: 800; }
.story-arrow { color: #9ba5b4; font-size: 22px; }
.news-loading { display: grid; min-height: 360px; place-items: center; align-content: center; color: #778397; }
.news-loading span { width: 36px; height: 36px; border: 4px solid #dfe6ef; border-top-color: #3c7ee7; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 620px) {
  .news-intro { align-items: flex-start; }
  .news-item { grid-template-columns: 38px 1fr; padding: 18px 15px; }
  .story-arrow { display: none; }
  .story-copy h3 { font-size: 15px; }
}
</style>
