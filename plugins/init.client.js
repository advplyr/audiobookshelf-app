import Vue from 'vue'
import { App } from '@capacitor/app'
import { Dialog } from '@capacitor/dialog'
import { StatusBar, Style } from '@capacitor/status-bar';
import { formatDistance, format } from 'date-fns'

const setStatusBarStyleDark = async () => {
  await StatusBar.setStyle({ style: Style.Dark })
}
setStatusBarStyleDark()

App.addListener('backButton', async ({ canGoBack }) => {
  if (!canGoBack) {
    const { value } = await Dialog.confirm({
      title: 'Confirm',
      message: `Did you want to exit the app?`,
    })
    if (value) {
      App.exitApp()
    }

  } else {
    window.history.back()
  }
});

Vue.prototype.$eventBus = new Vue()

Vue.prototype.$isDev = process.env.NODE_ENV !== 'production'

Vue.prototype.$dateDistanceFromNow = (unixms) => {
  if (!unixms) return ''
  return formatDistance(unixms, Date.now(), { addSuffix: true })
}
Vue.prototype.$formatDate = (unixms, fnsFormat = 'MM/dd/yyyy HH:mm') => {
  if (!unixms) return ''
  return format(unixms, fnsFormat)
}

Vue.prototype.$bytesPretty = (bytes, decimals = 2) => {
  if (isNaN(bytes) || bytes === null) return 'Invalid Bytes'
  if (bytes === 0) {
    return '0 Bytes'
  }
  const k = 1024
  const dm = decimals < 0 ? 0 : decimals
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i]
}

Vue.prototype.$elapsedPretty = (seconds) => {
  var minutes = Math.floor(seconds / 60)
  if (minutes < 70) {
    return `${minutes} min`
  }
  var hours = Math.floor(minutes / 60)
  minutes -= hours * 60
  if (!minutes) {
    return `${hours} hr`
  }
  return `${hours} hr ${minutes} min`
}

Vue.prototype.$secondsToTimestamp = (seconds) => {
  var _seconds = seconds
  var _minutes = Math.floor(seconds / 60)
  _seconds -= _minutes * 60
  var _hours = Math.floor(_minutes / 60)
  _minutes -= _hours * 60
  _seconds = Math.floor(_seconds)
  if (!_hours) {
    return `${_minutes}:${_seconds.toString().padStart(2, '0')}`
  }
  return `${_hours}:${_minutes.toString().padStart(2, '0')}:${_seconds.toString().padStart(2, '0')}`
}

function isClickedOutsideEl(clickEvent, elToCheckOutside, ignoreSelectors = [], ignoreElems = []) {
  const isDOMElement = (element) => {
    return element instanceof Element || element instanceof HTMLDocument
  }

  const clickedEl = clickEvent.srcElement
  const didClickOnIgnoredEl = ignoreElems.filter((el) => el).some((element) => element.contains(clickedEl) || element.isEqualNode(clickedEl))
  const didClickOnIgnoredSelector = ignoreSelectors.length ? ignoreSelectors.map((selector) => clickedEl.closest(selector)).reduce((curr, accumulator) => curr && accumulator, true) : false

  if (isDOMElement(elToCheckOutside) && !elToCheckOutside.contains(clickedEl) && !didClickOnIgnoredEl && !didClickOnIgnoredSelector) {
    return true
  }

  return false
}

Vue.directive('click-outside', {
  bind: function (el, binding, vnode) {
    let vm = vnode.context;
    let callback = binding.value;
    if (typeof callback !== 'function') {
      console.error('Invalid callback', binding)
      return
    }
    el['__click_outside__'] = (ev) => {
      if (isClickedOutsideEl(ev, el)) {
        callback.call(vm, ev)
      }
    }
    document.addEventListener('click', el['__click_outside__'], false)
  },
  unbind: function (el, binding, vnode) {
    document.removeEventListener('click', el['__click_outside__'], false)
    delete el['__click_outside__']
  }
})

function xmlToJson(xml) {
  const json = {};
  for (const res of xml.matchAll(/(?:<(\w*)(?:\s[^>]*)*>)((?:(?!<\1).)*)(?:<\/\1>)|<(\w*)(?:\s*)*\/>/gm)) {
    const key = res[1] || res[3];
    const value = res[2] && xmlToJson(res[2]);
    json[key] = ((value && Object.keys(value).length) ? value : res[2]) || null;

  }
  return json;
}
Vue.prototype.$xmlToJson = xmlToJson

const encode = (text) => encodeURIComponent(Buffer.from(text).toString('base64'))
Vue.prototype.$encode = encode
const decode = (text) => Buffer.from(decodeURIComponent(text), 'base64').toString()
Vue.prototype.$decode = decode

export {
  encode,
  decode
}