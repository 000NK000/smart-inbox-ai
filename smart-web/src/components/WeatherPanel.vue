<template>
  <section class="weather-panel">
    <div class="weather-hero">
      <div class="weather-toolbar">
        <div>
          <span class="section-label">LIVE LOCAL WEATHER</span>
          <h2>{{ t(weather.location) }}</h2>
          <p class="location-note"><span>●</span>{{ weather.located ? t('已使用设备定位') : t('定位不可用时默认使用滑铁卢') }}</p>
        </div>
        <div class="location-actions">
          <button type="button" :disabled="locating" @click="$emit('request-location')">{{ locating ? t('定位中…') : t('⌖ 使用当前位置') }}</button>
          <select :value="locationKey" @change="$emit('location-change', $event.target.value)">
            <option v-for="location in locations" :key="location.key" :value="location.key">{{ t(location.name) }}</option>
          </select>
        </div>
      </div>
      <div v-if="weather.loading" class="weather-loading">{{ t('正在读取天气…') }}</div>
      <div v-else class="current-weather">
        <span class="current-icon">{{ weather.icon }}</span>
        <div class="current-temp"><strong>{{ weather.temperature }}°</strong><span>{{ t(weather.label) }}</span></div>
        <div class="weather-facts">
          <div><span>{{ t('体感温度') }}</span><b>{{ weather.feelsLike }}°</b></div>
          <div><span>{{ t('相对湿度') }}</span><b>{{ weather.humidity }}%</b></div>
          <div><span>{{ t('风速') }}</span><b>{{ weather.wind }} km/h</b></div>
        </div>
      </div>
    </div>

    <section class="ai-rain-card">
      <div class="ai-title"><span class="ai-mark">AI</span><div><h3>{{ t('今日剩余时间降雨分析') }}</h3><p>{{ t('本地 Qwen 根据当前时间至 24:00 的逐小时数据分析') }}</p></div></div>
      <div v-if="analysisLoading" class="analysis-loading"><span></span>{{ t('AI 正在判断最可能下雨的时段…') }}</div>
      <template v-else-if="analysis">
        <p class="analysis-summary">{{ analysis.summary }}</p>
        <p class="analysis-advice">{{ analysis.advice }}</p>
        <div class="rain-peaks">
          <div v-for="hour in analysis.likelyHours" :key="hour.time" :class="{ strongest: hour.time === analysis.peakTime }">
            <span>{{ hour.time }}</span><strong>{{ hour.probability }}%</strong><small>{{ t(hour.label) }} · {{ hour.temperature }}°</small>
          </div>
        </div>
      </template>
      <p v-else class="analysis-summary">{{ t('天气读取完成后会自动生成降雨分析。') }}</p>
    </section>

    <section class="forecast-card">
      <div class="forecast-title">
        <div><span class="section-label dark">HOURLY · UNTIL MIDNIGHT</span><h3>{{ t('从现在到今天 24:00') }}</h3></div>
        <div class="legend"><span><i class="temp-dot"></i>{{ t('气温') }}</span><span><i class="rain-dot"></i>{{ t('降雨概率') }}</span></div>
      </div>
      <div v-if="!weather.hourly?.length" class="empty-chart">{{ t('暂时没有剩余时段数据') }}</div>
      <div v-else class="chart-scroll">
        <svg class="weather-chart" :viewBox="`0 0 ${chart.width} ${chart.height}`" :style="{ width: `${chart.width}px` }" role="img" :aria-label="t('从当前时间到24点的连续气温和降雨概率图')">
          <defs>
            <linearGradient id="temperatureArea" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#ff9b52" stop-opacity=".35" /><stop offset="100%" stop-color="#ff9b52" stop-opacity="0" /></linearGradient>
            <linearGradient id="rainBars" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#3d8bff" stop-opacity=".72" /><stop offset="100%" stop-color="#7bb7ff" stop-opacity=".18" /></linearGradient>
          </defs>
          <g class="grid-lines">
            <g v-for="line in chart.grid" :key="line.value"><line :x1="chart.left" :x2="chart.width - chart.right" :y1="line.y" :y2="line.y" /><text :x="chart.left - 10" :y="line.y + 4" text-anchor="end">{{ line.value }}°</text></g>
          </g>
          <path :d="chart.areaPath" fill="url(#temperatureArea)" />
          <g class="rain-bars">
            <g v-for="point in chart.points" :key="`rain-${point.time}`"><rect :x="point.x - chart.barWidth / 2" :y="point.rainY" :width="chart.barWidth" :height="chart.baseY - point.rainY" rx="6" fill="url(#rainBars)" /><text v-if="point.rain >= 20" :x="point.x" :y="point.rainY - 7" text-anchor="middle">{{ point.rain }}%</text></g>
          </g>
          <polyline :points="chart.linePoints" fill="none" stroke="#ff8a3d" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" />
          <g v-for="(point, index) in chart.points" :key="point.time" class="temperature-points">
            <circle :cx="point.x" :cy="point.y" r="6" /><text :x="point.x" :y="point.y - 14" text-anchor="middle">{{ point.temperature }}°</text>
            <text v-if="index % chart.labelStep === 0 || index === chart.points.length - 1" class="time-label" :x="point.x" :y="chart.height - 25" text-anchor="middle">{{ point.isNow ? t('现在 {time}', { time: point.time }) : point.time }}</text>
            <title>{{ t('{time}：{label}，{temperature}°，降雨 {rain}%', { time: point.time, label: t(point.label), temperature: point.temperature, rain: point.rain }) }}</title>
          </g>
        </svg>
      </div>
      <p class="chart-caption">{{ t('曲线包含每一个小时；蓝色柱越高，表示该小时下雨的可能性越大。') }}</p>
    </section>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed } from 'vue'

const props = defineProps({ weather: { type: Object, required: true }, locations: { type: Array, default: () => [] }, locationKey: { type: String, default: 'waterloo' }, analysis: { type: Object, default: null }, analysisLoading: { type: Boolean, default: false }, locating: { type: Boolean, default: false } })
defineEmits(['location-change', 'request-location'])

const chart = computed(() => {
  const hours = props.weather.hourly || []
  const width = Math.max(920, hours.length * 74), height = 350, left = 55, right = 28, top = 42, baseY = 286
  const temperatures = hours.map(item => Number(item.temperature))
  const rawMin = temperatures.length ? Math.min(...temperatures) : 0, rawMax = temperatures.length ? Math.max(...temperatures) : 1
  const min = Math.floor(rawMin - 2), max = Math.ceil(rawMax + 2), range = Math.max(1, max - min), usableWidth = width - left - right
  const xAt = index => left + (hours.length <= 1 ? usableWidth / 2 : usableWidth * index / (hours.length - 1))
  const yAt = value => top + (max - value) / range * (baseY - top)
  const points = hours.map((hour, index) => ({ ...hour, x: xAt(index), y: yAt(Number(hour.temperature)), rainY: baseY - Math.max(2, Number(hour.rain) / 100 * 135) }))
  const linePoints = points.map(point => `${point.x},${point.y}`).join(' ')
  const areaPath = points.length ? `M ${points[0].x} ${baseY} L ${points.map(point => `${point.x} ${point.y}`).join(' L ')} L ${points.at(-1).x} ${baseY} Z` : ''
  const grid = Array.from({ length: 5 }, (_, index) => { const value = Math.round(max - range * index / 4); return { value, y: yAt(value) } })
  return { width, height, left, right, baseY, points, linePoints, areaPath, grid, barWidth: Math.max(14, Math.min(34, usableWidth / Math.max(1, hours.length) * .45)), labelStep: hours.length > 14 ? 2 : 1 }
})
</script>

<style scoped>
.weather-panel { display: grid; width: min(1220px, 100%); gap: 22px; margin: 0 auto; }
.weather-hero { overflow: hidden; padding: clamp(26px, 5vw, 46px); border-radius: 34px; color: #fff; background: radial-gradient(circle at 82% 20%, rgba(255,255,255,.38), transparent 12rem), linear-gradient(135deg,#347ef3,#79b4ff); box-shadow: 0 24px 55px rgba(50,116,219,.24); }
.weather-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.section-label { font-size: 11px; font-weight: 900; letter-spacing: .16em; opacity: .78; }
.section-label.dark { color: #77859b; opacity: 1; }
.weather-toolbar h2 { margin: 5px 0 0; font-size: 31px; }
.location-note { display: flex; align-items: center; gap: 6px; margin: 8px 0 0; font-size: 11px; opacity: .8; }.location-note span { font-size: 7px; }
.location-actions { display: flex; gap: 9px; }.location-actions button, .location-actions select { border: 1px solid rgba(255,255,255,.4); border-radius: 13px; padding: 10px 13px; color: #fff; font: 700 12px inherit; background: rgba(255,255,255,.14); outline: none; }.location-actions button:disabled { opacity: .6; }.location-actions option { color: #1e293b; }
.current-weather { display: grid; grid-template-columns: auto auto 1fr; align-items: center; gap: 24px; margin-top: 28px; }.current-icon { font-size: 72px; filter: drop-shadow(0 12px 16px rgba(0,0,0,.13)); }.current-temp { display: grid; min-width: 150px; }.current-temp strong { font-size: 72px; line-height: .9; letter-spacing: -.08em; }.current-temp span { margin-top: 10px; opacity: .86; }
.weather-facts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-left: auto; }.weather-facts div { display: grid; min-width: 105px; gap: 5px; padding: 15px; border-radius: 17px; background: rgba(255,255,255,.15); }.weather-facts span { font-size: 11px; opacity: .75; }.weather-facts b { font-size: 17px; }.weather-loading { display: grid; min-height: 175px; place-items: center; }
.ai-rain-card, .forecast-card { border: 1px solid rgba(255,255,255,.9); border-radius: 28px; padding: 24px 26px; background: rgba(255,255,255,.78); box-shadow: 0 14px 38px rgba(52,66,89,.08); backdrop-filter: blur(18px); }
.ai-title { display: flex; align-items: center; gap: 13px; }.ai-title h3, .forecast-title h3 { margin: 0; color: #202c40; font-size: 21px; }.ai-title p { margin: 4px 0 0; color: #8792a3; font-size: 11px; }.ai-mark { display: grid; width: 43px; height: 43px; place-items: center; border-radius: 14px; color: #fff; font-size: 12px; font-weight: 900; background: linear-gradient(145deg,#7857ff,#428cff); box-shadow: 0 8px 20px rgba(94,86,238,.22); }
.analysis-summary { margin: 18px 0 4px; color: #26344a; font-size: 17px; font-weight: 760; line-height: 1.6; }.analysis-advice { margin: 0 0 17px; color: #738096; font-size: 13px; }.analysis-loading { display: flex; align-items: center; gap: 10px; min-height: 72px; color: #718097; }.analysis-loading span { width: 20px; height: 20px; border: 3px solid #dce5f2; border-top-color: #5a75f0; border-radius: 50%; animation: spin .8s linear infinite; }
.rain-peaks { display: flex; gap: 10px; flex-wrap: wrap; }.rain-peaks > div { display: grid; min-width: 145px; grid-template-columns: 1fr auto; gap: 2px 12px; padding: 12px 14px; border-radius: 15px; color: #506078; background: #f0f5fb; }.rain-peaks > div.strongest { color: #1e5eb8; background: #e6f1ff; box-shadow: inset 0 0 0 1px #c7dcff; }.rain-peaks strong { font-size: 17px; }.rain-peaks small { grid-column: 1 / -1; color: #8995a7; font-size: 10px; }
.forecast-title { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 12px; }.forecast-title h3 { margin-top: 4px; }.legend { display: flex; gap: 15px; color: #7c889a; font-size: 11px; }.legend span { display: flex; align-items: center; gap: 6px; }.legend i { width: 9px; height: 9px; border-radius: 50%; }.temp-dot { background: #ff8a3d; }.rain-dot { background: #438df5; }
.chart-scroll { overflow-x: auto; overflow-y: hidden; padding: 4px 0 2px; }.weather-chart { display: block; height: 350px; overflow: visible; }.grid-lines line { stroke: #dfe6ef; stroke-width: 1; stroke-dasharray: 5 8; }.grid-lines text, .rain-bars text { fill: #99a4b5; font-size: 10px; }.temperature-points circle { fill: #fff; stroke: #ff8a3d; stroke-width: 4; }.temperature-points text { fill: #d66627; font-size: 12px; font-weight: 850; }.temperature-points .time-label { fill: #788599; font-size: 11px; font-weight: 700; }.chart-caption { margin: 4px 0 0; color: #919bac; font-size: 11px; text-align: center; }.empty-chart { display: grid; min-height: 260px; place-items: center; color: #8c98aa; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 780px) { .current-weather { grid-template-columns: auto 1fr; }.weather-facts { grid-column: 1 / -1; width: 100%; }.forecast-title { align-items: flex-start; flex-direction: column; } }
@media (max-width: 560px) { .weather-hero { border-radius: 25px; }.weather-toolbar, .location-actions { align-items: stretch; flex-direction: column; }.current-icon { font-size: 55px; }.current-temp strong { font-size: 56px; }.weather-facts { grid-template-columns: 1fr; }.ai-rain-card, .forecast-card { padding: 20px 16px; border-radius: 22px; } }
</style>
