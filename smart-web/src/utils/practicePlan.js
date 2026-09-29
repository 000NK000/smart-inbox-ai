// The user's September 21–October 18, 2026 practice plan. Catalog entries are
// suggestions, never automatically marked complete from prior Hot 100 work.
const days = [
  ['2026-09-21', '前缀和＋哈希表', [[525, '连续数组'], [523, '连续的子数组和']]],
  ['2026-09-22', '滑动窗口', [[209, '长度最小的子数组'], [424, '替换后的最长重复字符']]],
  ['2026-09-23', '双指针', [[167, '两数之和 II—输入有序数组'], [16, '最接近的三数之和']]],
  ['2026-09-24', '二分答案', [[875, '爱吃香蕉的珂珂'], [1011, '在 D 天内送达包裹的能力']]],
  ['2026-09-25', '区间＋贪心', [[57, '插入区间'], [435, '无重叠区间']]],
  ['2026-09-28', '单调栈', [[402, '移掉 K 位数字'], [901, '股票价格跨度']]],
  ['2026-09-29', '二叉树进阶', [[662, '二叉树最大宽度'], [863, '二叉树中所有距离为 K 的结点']]],
  ['2026-09-30', '图／网格遍历', [[130, '被围绕的区域'], [417, '太平洋大西洋水流问题']]],
  ['2026-10-01', '拓扑排序＋并查集', [[210, '课程表 II'], [684, '冗余连接']]],
  ['2026-10-02', '堆＋调度', [[973, '最接近原点的 K 个点'], [621, '任务调度器']]],
  ['2026-10-05', '混刷 · 方法自选', [[213, '打家劫舍 II'], [380, 'O(1) 时间插入、删除和获取随机元素']]],
  ['2026-10-06', '混刷 · 方法自选', [[91, '解码方法'], [40, '组合总和 II']]],
  ['2026-10-07', '混刷 · 方法自选', [[518, '零钱兑换 II'], [981, '基于时间的键值存储']]],
  ['2026-10-08', '混刷 · 方法自选', [[494, '目标和'], [785, '判断二分图']]],
  ['2026-10-09', '混刷 · 方法自选', [[90, '子集 II'], [743, '网络延迟时间']]]
]

export const planDays = days.map(([date, topic, entries], index) => ({
  date, topic, week: Math.floor(index / 5) + 1,
  problems: entries.map(([number, title]) => ({ number, title, topic, date, week: Math.floor(index / 5) + 1 }))
}))
export const planProblems = planDays.flatMap(day => day.problems)
export const planByNumber = new Map(planProblems.map(problem => [problem.number, problem]))

export const specialDays = [
  { date: '2026-09-26', title: '70 分钟模拟 OA', detail: '两道陌生 Medium，隐藏标签，复盘选法和边界。' },
  { date: '2026-09-27', title: '轻量复习', detail: '60–90 分钟重写本周最不熟的 2–3 题。' },
  { date: '2026-10-03', title: '70 分钟模拟 OA', detail: '两道陌生 Medium；把卡点记入相应题目的练习记录。' },
  { date: '2026-10-04', title: '轻量复习', detail: '闭卷重写薄弱题，不强制做新题。' },
  { date: '2026-10-10', title: '70 分钟模拟 OA', detail: '混刷陌生题，先独立判断题型。' },
  { date: '2026-10-11', title: '轻量复习', detail: '重做红黄题、整理错误原因。' },
  { date: '2026-10-12', title: '45 分钟模拟面试', detail: '一道陌生题，全程英文讲思路与复杂度。' },
  { date: '2026-10-13', title: '公司定向或薄弱点', detail: '有明确公司时做两道代表题；否则补最薄弱题型。' },
  { date: '2026-10-14', title: '70 分钟模拟 OA', detail: '练习读题、时间分配和边界检查。' },
  { date: '2026-10-15', title: '闭卷复测', detail: '重做两道曾需要提示的题，再做一道变式。' },
  { date: '2026-10-16', title: 'Java＋项目表达', detail: '实现小功能并测试；用英文讲解 Smart Inbox。' },
  { date: '2026-10-17', title: '45 分钟模拟面试', detail: '陌生题限时完成，口述关键取舍。' },
  { date: '2026-10-18', title: '总结与收尾', detail: '复测薄弱点、回看错误记录，不大量开新题。' }
]

export function localDate(date = new Date()) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

export function practiceSummary(records) {
  const byNumber = new Map(records.map(record => [record.number, record]))
  const planned = planProblems.map(problem => ({ ...problem, progress: byNumber.get(problem.number) }))
  return {
    total: records.length,
    independent: records.filter(item => item.status === 'GREEN').length,
    unfamiliar: records.filter(item => item.status === 'YELLOW' || item.status === 'RED').length,
    suggestedAttempted: planned.filter(item => item.progress).length,
    totalMinutes: records.reduce((sum, item) => sum + (item.totalMinutes || 0), 0)
  }
}

export function pickUnfamiliar(records, previousNumber = null, random = Math.random) {
  const unfamiliar = records.filter(item => item.status === 'YELLOW' || item.status === 'RED')
  const pool = unfamiliar.length > 1 ? unfamiliar.filter(item => item.number !== previousNumber) : unfamiliar
  if (!pool.length) return null
  return pool[Math.floor(Math.min(Math.max(random(), 0), 0.999999999) * pool.length)]
}
