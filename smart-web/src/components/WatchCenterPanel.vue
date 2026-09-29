<template>
  <section class="watch-center">
    <header class="watch-intro">
      <div>
        <span>DOUBAN × IMDb × ROTTEN TOMATOES</span>
        <h2>{{ t('追剧中心') }}</h2>
        <p>{{ t('收藏想看的作品、记录观看进度，也可以从三大热门榜单发现下一部。') }}</p>
      </div>
      <button v-if="section === 'rankings'" type="button" :disabled="loading" @click="$emit('refresh')">{{ loading ? t('更新中…') : t('刷新榜单') }}</button>
    </header>

    <nav class="center-tabs" :aria-label="t('追剧中心功能')">
      <button type="button" :class="{ active: section === 'mine' }" :aria-pressed="section === 'mine'" @click="section = 'mine'">{{ t('我的清单') }}<small>{{ savedEntries.length }}</small></button>
      <button type="button" :class="{ active: section === 'rankings' }" :aria-pressed="section === 'rankings'" @click="section = 'rankings'">{{ t('热门榜单') }}</button>
    </nav>
    <WatchListPanel v-show="section === 'mine'" ref="watchList" :active="section === 'mine'" @items-change="savedEntries = $event" @browse="section = 'rankings'" />
    <div v-show="section === 'rankings'">
    <div v-if="loading && !movies.length && !tvShows.length" class="loading-state"><span></span><p>{{ t('正在更新三大榜单…') }}</p></div>
    <div v-else class="watch-columns">
      <div class="ranking-column">
        <nav class="source-tabs" :aria-label="t('电影榜单来源')">
          <button v-for="ranking in movies" :key="ranking.id" type="button" :class="{ active: movieSource === ranking.id }" @click="movieSource = ranking.id">{{ t(ranking.name) }}<small>{{ ranking.id === 'douban' ? movieItems.length : (ranking.items?.length || 0) }}</small></button>
        </nav>
        <RankingSection icon="◉" :eyebrow="movieIsDouban ? t('豆瓣 · 一周口碑榜') : `${t(activeMovie.name) || 'MOVIES'} · TOP 10`" :title="movieIsDouban ? t(movieCollection?.name || '豆瓣口碑榜') : t('电影')" :items="movieItems" :empty-label="t('该榜单暂时不可用，请稍后刷新')" :saved-titles="savedTitles(movieKind)" :adding="adding" @add="addRanked($event, movieKind)" @details="openDetails($event, movieKind)">
          <template v-if="movieIsDouban" #footer><DoubanChartTabs :collections="activeMovie.collections || []" :current="movieCollection" :label="t('左侧豆瓣榜单分类')" @select="movieChart = $event" /></template>
        </RankingSection>
      </div>
      <div class="ranking-column">
        <nav class="source-tabs dark-tabs" :aria-label="t('电视剧榜单来源')">
          <button v-for="ranking in tvShows" :key="ranking.id" type="button" :class="{ active: tvSource === ranking.id }" @click="tvSource = ranking.id">{{ t(ranking.name) }}<small>{{ ranking.id === 'douban' ? tvItems.length : (ranking.items?.length || 0) }}</small></button>
        </nav>
        <RankingSection icon="▣" :eyebrow="tvIsDouban ? t('豆瓣 · 一周口碑榜') : `${t(activeTv.name) || 'TV SERIES'} · TOP 10`" :title="tvIsDouban ? t(tvCollection?.name || '豆瓣口碑榜') : t('电视剧')" :items="tvItems" :empty-label="t('该榜单暂时不可用，请稍后刷新')" :saved-titles="savedTitles(tvKind)" :adding="adding" @add="addRanked($event, tvKind)" @details="openDetails($event, tvKind)" dark>
          <template v-if="tvIsDouban" #footer><DoubanChartTabs :collections="activeTv.collections || []" :current="tvCollection" :label="t('右侧豆瓣榜单分类')" dark @select="tvChart = $event" /></template>
        </RankingSection>
      </div>
    </div>

    <footer v-if="sources.length" class="source-status">
      <span v-for="source in sources" :key="source.id" :class="{ unavailable: !source.available }">
        <i></i>{{ t(source.name) }} · {{ t(source.channel) }} · {{ t(source.status) }}
      </span>
    </footer>
    </div>
    <WatchDetailDialog :item="detailItem" :kind="detailKind" :saved="detailItem ? savedTitles(detailKind).includes(titleKey(detailItem.title)) : false" :adding="adding" @close="detailItem = null" @add="addRanked(detailItem, detailKind)" />
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed, ref, watch } from 'vue'
import RankingSection from './WatchRankingSection.vue'
import WatchListPanel from './WatchListPanel.vue'
import DoubanChartTabs from './DoubanChartTabs.vue'
import WatchDetailDialog from './WatchDetailDialog.vue'

const props = defineProps({
  movies: { type: Array, default: () => [] },
  tvShows: { type: Array, default: () => [] },
  sources: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['refresh'])

const section = ref('mine')
const watchList = ref(null)
const savedEntries = ref([])
const adding = ref(false)
const detailItem = ref(null)
const detailKind = ref('MOVIE')
function openDetails(item, kind) { detailItem.value = item; detailKind.value = kind }
const titleKey = title => title.normalize('NFKC').trim().toLowerCase().replace(/\s+/g, ' ')
const savedTitles = kind => savedEntries.value.filter(item => item.kind === kind).map(item => titleKey(item.title))
async function addRanked(item, kind) {
  if (adding.value) return
  adding.value = true
  try { if (await watchList.value?.addFromRanking(item, kind)) { detailItem.value = null; section.value = 'mine' } }
  finally { adding.value = false }
}
const movieSource = ref('douban')
const tvSource = ref('douban')
const activeMovie = computed(() => props.movies.find(item => item.id === movieSource.value) || props.movies[0] || { name: '', items: [] })
const activeTv = computed(() => props.tvShows.find(item => item.id === tvSource.value) || props.tvShows[0] || { name: '', items: [] })
const movieChart = ref('movie_weekly_best')
const tvChart = ref('tv_chinese_best_weekly')
const movieIsDouban = computed(() => activeMovie.value.id === 'douban')
const tvIsDouban = computed(() => activeTv.value.id === 'douban')
const movieCollection = computed(() => activeMovie.value.collections?.find(chart => chart.id === movieChart.value) || activeMovie.value.collections?.[0])
const tvCollection = computed(() => activeTv.value.collections?.find(chart => chart.id === tvChart.value) || activeTv.value.collections?.[0])
const movieItems = computed(() => (movieIsDouban.value ? movieCollection.value?.items : activeMovie.value.items) || [])
const tvItems = computed(() => (tvIsDouban.value ? tvCollection.value?.items : activeTv.value.items) || [])
const movieKind = computed(() => movieIsDouban.value ? movieCollection.value?.kind || 'MOVIE' : 'MOVIE')
const tvKind = computed(() => tvIsDouban.value ? tvCollection.value?.kind || 'TV' : 'TV')
watch(() => props.movies, value => { if (value.length && !value.some(item => item.id === movieSource.value)) movieSource.value = value[0].id })
watch(() => props.tvShows, value => { if (value.length && !value.some(item => item.id === tvSource.value)) tvSource.value = value[0].id })
</script>

<style scoped>
.center-tabs{display:flex;gap:5px;width:fit-content;padding:5px;margin:0 0 28px;border:1px solid white;border-radius:17px;background:#ffffff80;box-shadow:0 6px 20px #23365408}
.center-tabs button{border:0;background:transparent;color:#8290a5;border-radius:12px;padding:12px 22px;font-weight:800;cursor:pointer}.center-tabs button.active{background:#fff;color:#cb415c;box-shadow:0 3px 12px #23365410}.center-tabs small{margin-left:6px;font-weight:600}.center-tabs button:focus-visible{outline:2px solid #397ef0;outline-offset:3px}

.watch-center { width: min(1280px,100%); margin: 0 auto; }
.watch-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 22px; margin-bottom: 24px; }
.watch-intro span { color: #8490a4; font-size: 10px; font-weight: 900; letter-spacing: .16em; }
.watch-intro h2 { margin: 5px 0 4px; font-size: clamp(28px,4vw,42px); letter-spacing: -.05em; }
.watch-intro p { margin: 0; color: #748096; font-size: 13px; }
.watch-intro button { border: 0; border-radius: 13px; padding: 11px 17px; color: #fff; font-weight: 850; background: linear-gradient(145deg,#f15a68,#c93049); }
.watch-columns { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 22px; align-items: start; }
.ranking-column { position: relative; min-width: 0; }
.source-tabs { position: relative; z-index: 2; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 5px; margin: 0 17px -18px; padding: 5px; border: 1px solid rgba(255,255,255,.9); border-radius: 16px; background: rgba(237,241,247,.94); box-shadow: 0 8px 24px rgba(48,63,88,.12); }
.source-tabs button { display: flex; align-items: center; justify-content: center; gap: 7px; min-width: 0; border: 0; border-radius: 11px; padding: 9px 7px; color: #758196; font-size: 11px; font-weight: 850; background: transparent; }
.source-tabs button.active { color: #fff; background: linear-gradient(145deg,#f05b68,#ca354c); box-shadow: 0 6px 16px rgba(201,53,76,.22); }
.source-tabs small { display: grid; min-width: 18px; height: 18px; place-items: center; border-radius: 999px; font-size: 8px; background: rgba(112,125,147,.12); }
.source-tabs button.active small { background: rgba(255,255,255,.2); }
.dark-tabs { border-color: rgba(255,255,255,.13); background: rgba(34,48,72,.96); }
.dark-tabs button { color: #aab9d0; }
.dark-tabs button.active { background: linear-gradient(145deg,#669eff,#3267c9); box-shadow: 0 6px 16px rgba(47,99,190,.3); }
.ranking-column :deep(.ranking-section) { padding-top: 40px; }
.source-status { display: flex; flex-wrap: wrap; gap: 9px 18px; margin: 18px 4px 0; color: #788499; font-size: 10px; }
.source-status span { display: flex; align-items: center; gap: 6px; }
.source-status i { width: 7px; height: 7px; border-radius: 50%; background: #21b46b; box-shadow: 0 0 0 3px rgba(33,180,107,.12); }
.source-status .unavailable i { background: #d58b32; box-shadow: 0 0 0 3px rgba(213,139,50,.12); }
.loading-state { display: grid; min-height: 480px; place-items: center; align-content: center; gap: 12px; color: #758196; }
.loading-state span { width: 34px; height: 34px; border: 3px solid #d9e5f7; border-top-color: #3478e5; border-radius: 50%; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 920px) { .watch-columns { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .watch-intro { align-items: stretch; flex-direction: column; } .watch-intro button { align-self: flex-start; } }
.source-tabs button{overflow-wrap:anywhere}.source-tabs small{flex-shrink:0}
</style>
