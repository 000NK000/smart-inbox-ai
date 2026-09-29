# 界面语言

`useI18n()` 提供响应式 `locale`、`dateLocale`、`setLocale()` 和 `t()`。
默认 `zh-CN`，英文为 `en-US`。选择保存在当前浏览器/桌面 WebView 的
`smart-inbox.ui-language` 中，同一浏览器的标签页通过 `storage` 事件同步。
不同浏览器分别记住选择。桌面外壳通过只允许两个固定语言值的原生桥同步菜单语言。

## 添加界面文案

1. 用中文原文作为 key，在对应 `*.en.js` 文件添加人工编写的英文。
2. 模板使用 `{{ t('保存') }}`，动态属性使用 `:placeholder="t('搜索')"`。
3. 数值使用参数，例如 `t('已完成 {count} 项', { count })`，避免拼接不同语言的语序。
4. 选项的内部值、数据库字段、URL、请求参数保持不变，只翻译显示标签。
5. 数组中保留原始标签，在渲染处调用 `t(option.label)`；不要在初始化时固定翻译结果。
6. 持久显示的错误保存原始文案，在模板中 `t(error)`，使语言切换时一起更新。
7. 日期用 `Intl.DateTimeFormat(dateLocale.value, options)`；给后端发送的日期格式不变。

## 内容边界

邮件正文和标题、新闻正文和标题、影视标题和简介、AI 报告、用户任务、笔记、
日程和题解都保留原文。不要对整个 DOM 做替换，也不要将这些内容传入 `t()`。
哲学引言作为已有内容保留原文。语言切换不发起请求、不调用 AI、不重新生成内容。
美国新闻已有的“内容语言”按钮继续独立工作。

Element Plus 内置控件由 App 中的 `ElConfigProvider` 切换。
修改后运行前端测试及 `npm run build -- --configLoader runner`；桌面版使用 dist。
