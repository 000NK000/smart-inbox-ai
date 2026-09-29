import { defineAsyncComponent, defineComponent, h, shallowRef } from 'vue'
import { useI18n } from '../i18n/index.js'

const cardStyle = {
  maxWidth: '680px', margin: '28px auto', padding: '32px', borderRadius: '24px',
  background: 'rgba(255,255,255,.9)', color: '#26344a', textAlign: 'center',
  boxShadow: '0 14px 38px rgba(52,66,89,.08)', lineHeight: '1.7',
}
const buttonStyle = {
  border: '1px solid #d5e1f3', borderRadius: '12px', padding: '10px 16px',
  background: '#fff', color: '#2764bc', cursor: 'pointer', font: 'inherit',
}

const LoadingPanel = defineComponent({
  name: 'AsyncPanelLoading',
  inheritAttrs: false,
  setup() {
    const { t } = useI18n()
    return () => h('section', { class: 'async-panel-loading', role: 'status', 'aria-busy': 'true', style: cardStyle }, t('正在加载页面…'))
  },
})

// A running window can still refer to old chunk URLs after a frontend update.
// Keep recovery local to this panel instance; never reload or discard drafts automatically.
export function createAsyncPanel(loader, { delay = 200, timeout = 15000, reload = () => window.location.reload() } = {}) {
  return defineComponent({
    name: 'RecoverableAsyncPanel',
    inheritAttrs: false,
    setup(_props, { attrs, slots }) {
      const { t } = useI18n()
      const component = shallowRef()
      const ErrorPanel = defineComponent({
        name: 'AsyncPanelError',
        inheritAttrs: false,
        setup: () => () => h('section', { class: 'async-panel-error', role: 'alert', style: cardStyle }, [
          h('h3', { style: { margin: '0 0 10px' } }, t('页面暂时无法加载')),
          h('p', t('可以先重试；如果刚更新过软件，请刷新页面以加载最新版本。')),
          h('div', { style: { display: 'flex', justifyContent: 'center', gap: '12px', flexWrap: 'wrap' } }, [
            h('button', { type: 'button', style: buttonStyle, onClick: retry }, t('重试加载')),
            h('button', { type: 'button', style: buttonStyle, onClick: reload }, t('刷新页面并返回主菜单')),
          ]),
          h('p', { style: { marginBottom: '0', color: '#738096', fontSize: '13px' } }, t('刷新会回到主菜单，尚未保存的输入将丢失。')),
        ]),
      })
      function retry() {
        component.value = defineAsyncComponent({
          loader, delay, timeout, suspensible: false,
          loadingComponent: LoadingPanel, errorComponent: ErrorPanel,
        })
      }
      retry()
      return () => h(component.value, attrs, slots)
    },
  })
}
