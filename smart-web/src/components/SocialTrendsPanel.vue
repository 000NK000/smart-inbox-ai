<template>
  <section class="trends-panel">
    <div class="trends-intro">
      <div>
        <span class="section-label">SOCIAL · REALTIME</span>
        <h2>{{ t('平台热榜') }}</h2>
        <p>{{ t('微博与B站分别显示实时前10条；抖音和小红书已预留接口，接入合规数据源后即可启用。') }}</p>
      </div>
      <button type="button" :disabled="loading" @click="$emit('refresh')">{{ loading ? t('更新中…') : t('更新热榜') }}</button>
    </div>

    <div class="source-tabs" role="tablist" :aria-label="t('热榜平台')">
      <button type="button" :class="{ active: activeId === 'all' }" @click="activeId = 'all'">{{ t('综合') }}</button>
      <button v-for="platform in platforms" :key="platform.id" type="button" :class="{ active: activeId === platform.id, pending: !platform.enabled }" @click="activeId = platform.id">
        {{ t(platform.name) }}<span v-if="!platform.enabled">{{ t('待接入') }}</span>
      </button>
    </div>

    <div v-if="loading && !platforms.length" class="center-state"><span class="spinner"></span><p>{{ t('正在连接平台热榜…') }}</p></div>
    <div v-else-if="activeId === 'all'" class="platform-grid">
      <TrendSourceCard v-for="platform in platforms" :key="platform.id" :platform="platform" />
    </div>
    <TrendSourceCard v-else-if="activePlatform" class="single-card" :platform="activePlatform" />
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { computed, defineComponent, h, ref } from 'vue'

const props = defineProps({
  platforms: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['refresh'])

const activeId = ref('all')
const activePlatform = computed(() => props.platforms.find(platform => platform.id === activeId.value))

function formatHeat(value) {
  const heat = Number(value || 0)
  if (!heat) return ''
  return new Intl.NumberFormat(dateLocale.value, { notation: heat >= 10000 ? 'compact' : 'standard', maximumFractionDigits: 1 }).format(heat)
}

const TrendSourceCard = defineComponent({
  props: { platform: { type: Object, required: true } },
  setup(cardProps) {
    const openTrend = url => { if (url) window.open(url, '_blank', 'noopener,noreferrer') }
    return () => h('article', { class: ['source-card', `source-${cardProps.platform.id}`, { unavailable: !cardProps.platform.available }] }, [
      h('header', [
        h('div', [h('span', 'PLATFORM'), h('h3', t(cardProps.platform.name))]),
        h('em', { class: cardProps.platform.available ? 'live' : 'pending' }, t(cardProps.platform.available ? '实时' : '待接入'))
      ]),
      cardProps.platform.available && cardProps.platform.items?.length
        ? h('ol', cardProps.platform.items.map((item, index) => h('li', {
          key: item.url,
          tabindex: 0,
          onClick: () => openTrend(item.url),
          onKeydown: event => { if (event.key === 'Enter') openTrend(item.url) }
        }, [
          h('b', String(index + 1).padStart(2, '0')),
          h('span', item.title),
          item.badge ? h('i', t(item.badge)) : null,
          item.heat ? h('small', formatHeat(item.heat)) : null
        ])))
        : h('div', { class: 'source-message' }, [
          h('strong', t(cardProps.platform.enabled ? '数据源暂时不可用' : '适配器已预留')),
          h('p', t(cardProps.platform.status))
        ]),
      h('footer', cardProps.platform.available ? t('{status} · 每10分钟缓存', { status: t(cardProps.platform.status) }) : t(cardProps.platform.status))
    ])
  }
})
</script>

<style>
.trends-panel { width: min(1180px, 100%); margin: 0 auto; }
.trends-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 22px; }
.section-label { color: #c45b3e; font-size: 11px; font-weight: 900; letter-spacing: .15em; }
.trends-intro h2 { margin: 5px 0; font-size: clamp(36px, 5vw, 58px); letter-spacing: -.055em; }
.trends-intro p { max-width: 720px; margin: 0; color: #788397; line-height: 1.65; }
.trends-intro > button { border: 0; border-radius: 13px; padding: 11px 17px; color: #fff; font-size: 13px; font-weight: 800; background: linear-gradient(145deg,#ff765d,#e64b37); box-shadow: 0 8px 24px rgba(193,75,53,.2); }
.source-tabs { display: flex; gap: 7px; overflow-x: auto; margin-bottom: 20px; padding: 5px; border: 1px solid rgba(255,255,255,.82); border-radius: 16px; background: rgba(255,255,255,.48); }
.source-tabs button { flex: 0 0 auto; border: 0; border-radius: 11px; padding: 9px 15px; color: #657187; font-weight: 800; background: transparent; }
.source-tabs button.active { color: #b54831; background: #fff; box-shadow: 0 5px 16px rgba(55,75,110,.1); }
.source-tabs button span { margin-left: 6px; color: #a2a9b5; font-size: 9px; }
.platform-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 18px; }
.source-card { display: flex; min-height: 550px; flex-direction: column; padding: 24px; border: 1px solid rgba(255,255,255,.9); border-radius: 28px; background: rgba(255,255,255,.82); box-shadow: 0 15px 38px rgba(50,64,88,.09); }
.source-card.source-bilibili { background: linear-gradient(155deg, rgba(255,255,255,.93), rgba(238,249,255,.92)); }
.source-card.unavailable { min-height: 220px; opacity: .82; background: rgba(244,246,249,.7); }
.source-card header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 13px; }
.source-card header span { color: #9aa4b2; font-size: 9px; font-weight: 900; letter-spacing: .14em; }
.source-card h3 { margin: 3px 0 0; color: #1d293c; font-size: 28px; }
.source-card header em { border-radius: 999px; padding: 6px 10px; font-size: 10px; font-style: normal; font-weight: 900; }
.source-card header em.live { color: #238453; background: #e3f7eb; }
.source-card header em.pending { color: #7e8795; background: #e9edf2; }
.source-card ol { display: grid; padding: 0; margin: 0; list-style: none; }
.source-card li { display: grid; grid-template-columns: 32px minmax(0,1fr) auto auto; align-items: center; gap: 10px; padding: 12px 2px; border-bottom: 1px solid rgba(119,133,154,.14); cursor: pointer; }
.source-card li:hover span { color: #316fcf; }
.source-card li b { color: #c46143; font-size: 13px; }
.source-bilibili li b { color: #2f84ad; }
.source-card li span { overflow: hidden; color: #273347; font-size: 13px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.source-card li i { border-radius: 6px; padding: 3px 5px; color: #d1533e; font-size: 9px; font-style: normal; background: #ffebe5; }
.source-card li small { color: #98a2b0; font-size: 10px; }
.source-message { display: grid; min-height: 120px; place-items: center; align-content: center; text-align: center; }
.source-message strong { color: #5f6b7d; }
.source-message p { margin: 7px 0 0; color: #8c96a5; font-size: 12px; }
.source-card footer { margin-top: auto; padding-top: 14px; color: #9aa3b1; font-size: 10px; }
.single-card { width: min(720px, 100%); margin: 0 auto; }
.center-state { display: grid; min-height: 420px; place-items: center; align-content: center; color: #7b8799; }
.spinner { width: 34px; height: 34px; border: 4px solid #dfe6ef; border-top-color: #ea6249; border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .trends-intro { align-items: flex-start; flex-direction: column; }
  .platform-grid { grid-template-columns: 1fr; }
  .source-card { min-height: 500px; padding: 19px 16px; }
  .source-card.unavailable { min-height: 190px; }
  .source-card li { grid-template-columns: 28px minmax(0,1fr) auto; }
  .source-card li small { display: none; }
}
</style>
