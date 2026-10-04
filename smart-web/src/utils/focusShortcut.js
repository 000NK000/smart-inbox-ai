import { acceptsFocusShortcut } from './focusTime.js'

function hasOpenDialog(document) {
  return [...document.querySelectorAll('[role="dialog"], [role="alertdialog"], dialog[open]')].some(dialog => {
    for (let element = dialog; element; element = element.parentElement) {
      if (element.hidden || element.getAttribute('aria-hidden') === 'true') return false
      const style = document.defaultView.getComputedStyle(element)
      if (style.display === 'none' || style.visibility === 'hidden') return false
    }
    return true
  })
}

export function installFocusShortcut({ window, document, controller, isEnabled = () => true, onPending = () => {}, onResult = () => {} }) {
  let installed = true
  const handler = async event => {
    if (!isEnabled() || !acceptsFocusShortcut(event, document.hidden) || controller.busy.value || hasOpenDialog(document)) return
    event.preventDefault()
    onPending()
    const result = await controller.switchCategory({ canSwitch: () => installed && isEnabled() })
    if (installed) onResult(isEnabled() ? result : { status: 'ignored' })
  }
  window.addEventListener('keydown', handler)
  return () => { installed = false; window.removeEventListener('keydown', handler) }
}
