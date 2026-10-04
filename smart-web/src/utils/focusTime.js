export const localDay = (stamp, zone) => new Intl.DateTimeFormat('sv-SE', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(stamp))

// Find actual local midnight, including 23/25-hour daylight-saving days.
export function nextDayStart(stamp, zone) {
  const day = localDay(stamp, zone)
  let low = Math.floor(stamp), high = low + 27 * 3600000
  while (low < high) { const mid = Math.floor((low + high) / 2); if (localDay(mid, zone) === day) low = mid + 1; else high = mid }
  return low
}

export function elapsedSinceSnapshot(overview, receivedAt, now, midnight) {
  if (!overview?.active || !Number.isFinite(overview.serverNow) || !Number.isFinite(overview.active.leaseUntil)) return 0
  const estimated = overview.serverNow + Math.max(0, now - receivedAt)
  return Math.max(0, Math.min(estimated, overview.active.leaseUntil, midnight) - overview.serverNow)
}

export function acceptsFocusShortcut(event, hidden = false) {
  if (event.key !== 'Enter' || hidden || event.defaultPrevented || event.repeat || event.isComposing || event.keyCode === 229 || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return false
  const targets = event.composedPath?.() || [event.target]
  // Enter must still submit forms, activate focused buttons, and edit notes normally.
  return !targets.some(target => target?.isContentEditable || target?.closest?.('input, textarea, select, [contenteditable], button, a, summary, [tabindex], [inert], [role="button"], [role="link"], [role="textbox"], [role="combobox"], [role="menu"], [role="listbox"], [role="slider"], [role="switch"], [role="checkbox"], [role="radio"], [role="tab"], [role="tree"], [role="grid"]'))
}

export function clockTime(ms = 0) {
  const seconds = Math.floor(Math.max(0, ms) / 1000)
  return `${String(Math.floor(seconds / 3600)).padStart(2, '0')}:${String(Math.floor(seconds % 3600 / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function rangeDays(days, today, range) {
  if (!today) return []
  const end = new Date(`${today}T12:00:00Z`), start = new Date(end)
  if (range === 'week') start.setUTCDate(start.getUTCDate() - 6)
  else if (range === 'month') {
    const day = start.getUTCDate(); start.setUTCDate(1); start.setUTCMonth(start.getUTCMonth() - 1)
    const lastDay = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate()
    start.setUTCDate(Math.min(day, lastDay))
  } else return [...days].filter(day => day.date <= today).sort((a, b) => a.date.localeCompare(b.date))
  const first = start.toISOString().slice(0, 10)
  return days.filter(day => day.date >= first && day.date <= today).sort((a, b) => a.date.localeCompare(b.date))
}

export function chartBuckets(days, size = 7) {
  const buckets = []
  for (let i = 0; i < days.length; i += size) {
    const group = days.slice(i, i + size)
    buckets.push({ start: group[0].date, end: group.at(-1).date, totals: group.reduce((sum, day) => ({ EFFECTIVE: sum.EFFECTIVE + (day.totals.EFFECTIVE || 0), INEFFECTIVE: sum.INEFFECTIVE + (day.totals.INEFFECTIVE || 0) }), { EFFECTIVE: 0, INEFFECTIVE: 0 }) })
  }
  return buckets
}
