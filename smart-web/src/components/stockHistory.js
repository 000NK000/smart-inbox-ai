// Only plot provider observations. Invalid values never become a zero-price point.
export function historyPoints(raw) {
  const points = new Map()
  for (const point of Array.isArray(raw) ? raw : []) {
    const time = Date.parse(point?.time)
    const value = point?.value == null || point.value === '' || typeof point.value === 'boolean' || !Number.isFinite(Number(point.value)) ? null : Number(point.value)
    if (!Number.isFinite(time)) continue
    points.set(time, { time: new Date(time).toISOString(), timestamp: time, value })
  }
  return [...points.values()].sort((a, b) => a.timestamp - b.timestamp)
}

export function historyGeometry(points) {
  const observations = points.filter(point => point.value != null)
  if (!observations.length) return { path: '', dots: [], minimum: null, maximum: null }
  const values = observations.map(point => point.value)
  const low = Math.min(...values), high = Math.max(...values)
  const padding = high === low ? Math.max(Math.abs(high) * 0.01, 0.01) : (high - low) * 0.12
  const minimum = low - padding, maximum = high + padding
  const first = observations[0].timestamp, span = observations.at(-1).timestamp - first
  const dots = observations.map(point => ({
    ...point,
    x: span ? 8 + ((point.timestamp - first) / span) * 984 : 500,
    y: 12 + ((maximum - point.value) / (maximum - minimum)) * 236
  }))
  let connected = false, index = 0
  const path = []
  for (const point of points) {
    if (point.value == null) { connected = false; continue }
    const dot = dots[index++]
    path.push(`${connected ? 'L' : 'M'}${dot.x.toFixed(2)},${dot.y.toFixed(2)}`)
    connected = true
  }
  return { minimum, maximum, dots, path: path.join(' ') }
}
