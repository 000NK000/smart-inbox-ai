<template>
  <section class="stock-history" :aria-label="t('账户与股票走势')" :aria-busy="loading">
    <header class="history-heading">
      <div><span class="history-eyebrow">ACCOUNT & MARKET HISTORY</span><h4>{{ t('账户与股票走势') }}</h4></div>
      <label class="history-selection">{{ t('查看走势') }}<select :value="target" :disabled="!connected" @change="$emit('update:target', $event.target.value)"><option value="PORTFOLIO">{{ t('总体账户（含现金）') }}</option><option v-for="item in choices" :key="item.contractId" :value="String(item.contractId)">{{ item.symbol || item.name }} · {{ item.currency || t('币种未知') }}</option></select></label>
    </header>
    <div class="history-toolbar"><div class="history-ranges" role="group" :aria-label="t('走势时间范围')"><button v-for="item in ranges" :key="item.id" type="button" :disabled="!connected" :aria-pressed="range === item.id" @click="$emit('update:range', item.id)">{{ t(item.label) }}</button></div><button class="history-refresh" type="button" :disabled="!connected || loading" @click="$emit('retry')">{{ t('刷新走势') }}</button></div>
    <p class="history-explanation">{{ target === 'PORTFOLIO' ? t('总体显示 IBKR 账户净值，包含现金与持仓。入金、出金也会改变净值，因此这不是投资收益率。') : t('个股显示历史价格，不是你的持仓盈亏；行情时间和粒度以 IBKR 返回数据为准。') }}</p>
    <div v-if="!connected" class="history-empty" role="status">{{ t('连接 IBKR 后查看走势。') }}</div>
    <div v-else-if="loading" class="history-empty" role="status"><span class="history-loading"></span>{{ t('正在读取历史数据…') }}</div>
    <div v-else-if="error" class="history-empty history-error" role="alert"><p>{{ error }}</p><button type="button" @click="$emit('retry')">{{ t('重新读取走势') }}</button></div>
    <template v-else>
      <p v-if="historyMessage" class="history-message" role="status">{{ historyMessage }}</p>
      <template v-if="history?.available && points.length">
        <div class="history-value"><div><span>{{ history.metric === 'NAV' ? t('账户净值') : t('股票价格') }}<b v-if="history.currency"> · {{ history.currency }}</b></span><strong>{{ formatMoney(selectedPoint.value) }}</strong><time :datetime="selectedPoint.time">{{ pointTime(selectedPoint.time) }}</time></div><span v-if="history.stale" class="history-stale">{{ t('缓存数据 · 请刷新') }}</span></div>
        <div class="history-plot-wrap">
          <div class="history-yaxis" aria-hidden="true"><span>{{ axisNumber(geometry.maximum) }}</span><span>{{ axisNumber((geometry.maximum + geometry.minimum) / 2) }}</span><span>{{ axisNumber(geometry.minimum) }}</span></div>
          <div class="history-plot">
            <svg viewBox="0 0 1000 260" preserveAspectRatio="none" role="img" :aria-label="chartDescription" @pointermove="selectAtPointer" @pointerdown="selectAtPointer">
              <title>{{ chartDescription }}</title>
              <path class="chart-grid" d="M0 12 H1000 M0 130 H1000 M0 248 H1000" />
              <path v-if="points.length > 1" class="chart-line" :d="geometry.path" />
              <path class="chart-crosshair" :d="`M${selectedDot.x} 0 V260`" />
            </svg>
            <span class="chart-dot" :style="{ left: `${selectedDot.x / 10}%`, top: `${selectedDot.y / 2.6}%` }" aria-hidden="true"></span>
          </div>
        </div>
        <div class="history-xaxis" aria-hidden="true"><span>{{ axisTime(points[0].time) }}</span><span>{{ points.length > 1 ? axisTime(points.at(-1).time) : '' }}</span></div>
        <label v-if="points.length > 1" class="history-scrubber"><span>{{ t('拖动查看时间点，也可用键盘左右键。') }}</span><input v-model.number="selectedIndex" type="range" min="0" :max="points.length - 1" step="1" :aria-label="t('选择图表时间点')" :aria-valuetext="`${pointTime(selectedPoint.time)} · ${formatMoney(selectedPoint.value)}`" /></label>
        <p v-else class="history-message">{{ t('此范围只有一个真实记录，暂时无法连成趋势线。') }}</p>
        <div class="history-meta"><span>{{ t('来源') }}: {{ t(history.source || 'IBKR') }}</span><span>{{ t('数据粒度') }}: {{ resolutionLabel }}</span><span>{{ t('读取时间') }}: {{ retrievedTime }}</span><span v-if="history.delayed === true || Number(history.delayed) > 0">{{ t('IBKR 标记为延迟行情') }}</span></div>
      </template>
      <div v-else class="history-empty" role="status"><strong>{{ t('此范围暂无可用历史数据') }}</strong><span>{{ t('可切换其他时间范围；缺少的数据不会补画。') }}</span><button type="button" @click="$emit('retry')">{{ t('重新读取走势') }}</button></div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from '../i18n/index.js'
import { historyPoints, historyGeometry } from './stockHistory.js'

const props = defineProps({ history: { type: Object, default: null }, loading: Boolean, error: { type: String, default: '' }, connected: Boolean, positions: { type: Array, default: () => [] }, target: { type: String, default: 'PORTFOLIO' }, range: { type: String, default: '1M' } })
defineEmits(['update:target', 'update:range', 'retry'])
const { t, dateLocale } = useI18n()
const ranges = [{ id: '2H', label: '2 小时' }, { id: '1D', label: '1 天' }, { id: '1W', label: '1 周' }, { id: '15D', label: '15 天' }, { id: '1M', label: '1 个月' }, { id: '1Y', label: '1 年' }]
const choices = computed(() => [...new Map(props.positions.filter(item => Number.isSafeInteger(Number(item.contractId)) && Number(item.contractId) > 0).map(item => [String(item.contractId), item])).values()])
const observations = computed(() => historyPoints(props.history?.points))
const points = computed(() => observations.value.filter(point => point.value != null))
const geometry = computed(() => historyGeometry(observations.value))
const selectedIndex = ref(0)
watch(points, value => { selectedIndex.value = Math.max(0, value.length - 1) }, { immediate: true })
const selectedPoint = computed(() => points.value[Math.max(0, Math.min(selectedIndex.value, points.value.length - 1))])
const selectedDot = computed(() => geometry.value.dots[Math.max(0, Math.min(selectedIndex.value, points.value.length - 1))])
const daily = computed(() => ['1d', 'ONE_DAY', 'daily'].includes(props.history?.resolution))
const resolutionLabel = computed(() => t(({ '1min': '1 分钟', '5min': '5 分钟', '1m': '1 分钟', '5m': '5 分钟', '15m': '15 分钟', '30m': '30 分钟', '1h': '1 小时', '1d': '每日', daily: '每日', ONE_DAY: '每日', snapshot: '手动刷新时记录' })[props.history?.resolution] || props.history?.resolution || '未提供'))
const historyMessage = computed(() => t(String(props.history?.message || '').replace(/^该范围内只有一个真实数据点，暂时无法形成折线。/, '')))
function formatMoney(value) { try { return new Intl.NumberFormat(dateLocale.value, { style: 'currency', currency: props.history?.currency, currencyDisplay: 'code', maximumFractionDigits: 2 }).format(value) } catch { return axisNumber(value) } }
function axisNumber(value) { return new Intl.NumberFormat(dateLocale.value, { maximumFractionDigits: 2 }).format(value) }
function pointTime(value) { return new Intl.DateTimeFormat(dateLocale.value, daily.value ? { dateStyle: 'medium', timeZone: 'UTC' } : { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
function axisTime(value) { return new Intl.DateTimeFormat(dateLocale.value, daily.value ? { month: 'short', day: 'numeric', timeZone: 'UTC' } : { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
const retrievedTime = computed(() => { const date = new Date(props.history?.asOf); return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat(dateLocale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date) : t('时间不可用') })
const chartDescription = computed(() => points.value.length ? t('{name}，{start} 至 {end}，共 {count} 个真实数据点。', { name: props.target === 'PORTFOLIO' ? t('账户净值') : props.history?.symbol || t('股票价格'), start: pointTime(points.value[0].time), end: pointTime(points.value.at(-1).time), count: points.value.length }) : '')
function selectAtPointer(event) {
  const bounds = event.currentTarget.getBoundingClientRect()
  if (!bounds.width || !points.value.length) return
  const x = Math.max(0, Math.min(1000, (event.clientX - bounds.left) / bounds.width * 1000))
  let closest = 0
  geometry.value.dots.forEach((point, index) => { if (Math.abs(point.x - x) < Math.abs(geometry.value.dots[closest].x - x)) closest = index })
  selectedIndex.value = closest
}
</script>

<style scoped>
.history-toolbar{display:flex;align-items:center;justify-content:space-between;gap:15px;margin:24px 0 12px}.history-toolbar .history-ranges{margin:0}.history-refresh{border:1px solid #d7e4dd!important;background:#fff!important;color:#568571!important;font-size:11px!important;padding:10px 12px!important;min-height:42px;white-space:nowrap}@media(max-width:620px){.history-toolbar{align-items:stretch;flex-direction:column;gap:9px;margin-top:17px}.history-refresh{align-self:flex-end}.history-toolbar .history-ranges{margin:0}}
.stock-history{margin:28px 0;padding:24px;border:1px solid #dce9e5;border-radius:20px;background:linear-gradient(150deg,#f8fbfa,#fff);min-width:0}.history-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.history-eyebrow{font-size:9px;letter-spacing:.15em;font-weight:800;color:#7c9b90}.history-heading h4{font-size:20px;margin:7px 0;color:#24483f}.history-selection{display:grid;gap:7px;font-size:11px;color:#718c82;min-width:0;max-width:100%;font-weight:600}.history-selection select{width:240px;max-width:100%;padding:10px 12px;border:1px solid #d8e5df;border-radius:10px;background:#fff;color:#294f43;font:inherit;font-size:13px;min-height:44px}.history-ranges{display:flex;flex-wrap:wrap;gap:5px;margin:24px 0 12px;padding:5px;border-radius:12px;background:#eaf2ef;width:fit-content;max-width:100%}.history-ranges button{border:0!important;color:#708b80!important;background:transparent!important;padding:10px 18px!important;font-size:12px!important;min-height:40px;border-radius:9px!important}.history-ranges button[aria-pressed=true]{background:#fff!important;color:#21614f!important;box-shadow:0 2px 7px #25493709}.history-ranges button:disabled{opacity:.5}.history-explanation{font-size:12px;line-height:1.7;color:#7e928b;margin:0 0 24px}.history-empty{display:flex;min-height:240px;align-items:center;justify-content:center;flex-direction:column;gap:13px;text-align:center;color:#7a9286;font-size:13px}.history-empty>span{font-size:12px;line-height:1.7}.history-empty button{background:#fff!important;color:#286e68!important;border:1px solid #d3e3dc!important;font-size:12px!important}.history-error{color:#a0603b}.history-loading{width:22px;height:22px;border:2px solid #e3eee8;border-top-color:#468374;border-radius:50%;animation:history-spin 1s linear infinite}.history-message{font-size:12px;line-height:1.8;background:#edf5f0;border-radius:10px;padding:11px 14px;color:#658174}.history-value{display:flex;justify-content:space-between;gap:15px;align-items:flex-start;min-height:92px;margin:23px 0 16px}.history-value>div{display:grid;gap:7px}.history-value span{font-size:11px;color:#7b9389}.history-value b{font-weight:500}.history-value strong{font-size:clamp(24px,3vw,32px);letter-spacing:-.035em;font-variant-numeric:tabular-nums;color:#235846}.history-value time{font-size:11px;color:#8b9e95}.history-value .history-stale{background:#fff0d9;padding:6px 9px;border-radius:7px;color:#967440}.history-plot-wrap{display:flex;gap:14px;height:240px}.history-yaxis{display:flex;flex-direction:column;justify-content:space-between;align-items:flex-end;padding:11px 0;font-size:10px;color:#93a79d;min-width:48px;font-variant-numeric:tabular-nums}.history-plot{position:relative;min-width:0;flex:1}.history-plot svg{width:100%;height:100%;overflow:visible;touch-action:pan-y}.chart-grid{fill:none;stroke:#e6eeea;stroke-width:1;stroke-dasharray:4 7;vector-effect:non-scaling-stroke}.chart-line{fill:none;stroke:#368c74;stroke-width:2.5;stroke-linejoin:round;stroke-linecap:round;vector-effect:non-scaling-stroke}.chart-crosshair{fill:none;stroke:#90b4a5;stroke-width:1;stroke-dasharray:3 5;vector-effect:non-scaling-stroke}.chart-dot{position:absolute;width:9px;height:9px;border:2px solid #fff;background:#368c74;border-radius:50%;transform:translate(-50%,-50%);box-shadow:0 0 0 4px #368c7417;pointer-events:none}.history-xaxis{display:flex;justify-content:space-between;gap:10px;margin:9px 0 0 62px;font-size:10px;color:#93a79d}.history-scrubber{display:flex;flex-direction:column;margin:18px 0 0 62px;gap:0;color:#8aa093;font-size:10px}.history-scrubber input{padding:0!important;width:100%;height:36px;accent-color:#368c74;border:0!important;cursor:pointer;background:transparent!important}.history-meta{display:flex;flex-wrap:wrap;gap:7px 17px;border-top:1px solid #e7eeea;padding-top:15px;margin-top:16px;font-size:10px;color:#8a9e93}.stock-history :focus-visible{outline:3px solid #7db4a2;outline-offset:3px}@keyframes history-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.history-loading{animation:none}}@media(max-width:620px){.stock-history{padding:16px;margin:23px 0}.history-heading{align-items:flex-start;flex-direction:column;gap:12px}.history-heading h4{font-size:18px}.history-selection{width:100%}.history-selection select{width:100%}.history-ranges{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));width:auto;margin-top:17px}.history-ranges button{padding:10px 6px!important;min-height:44px}.history-explanation{font-size:11px}.history-plot-wrap{gap:8px;height:200px}.history-yaxis{min-width:44px;font-size:9px}.history-xaxis,.history-scrubber{margin-left:52px}.history-value strong{font-size:26px}.history-scrubber input{height:44px}.history-empty{min-height:210px}}
</style>
