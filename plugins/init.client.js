import Vue from 'vue'
import vClickOutside from 'v-click-outside'
import { App } from '@capacitor/app'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'
import { StatusBar, Style } from '@capacitor/status-bar'
import { Clipboard } from '@capacitor/clipboard'
import { Capacitor } from '@capacitor/core'
import { formatDistance, format, addDays, isDate, setDefaultOptions } from 'date-fns'
import * as locale from 'date-fns/locale'

Vue.directive('click-outside', vClickOutside.directive)

if (Capacitor.getPlatform() != 'web') {
  const setStatusBarStyleDark = async () => {
    await StatusBar.setStyle({ style: Style.Dark })
  }
  setStatusBarStyleDark()

  const setStatusBarOverlays = async () => {
    // Defaults to true in capacitor v7
    await StatusBar.setOverlaysWebView({ overlay: false })
  }
  setStatusBarOverlays()
}

Vue.prototype.$showHideStatusBar = async (show) => {
  if (Capacitor.getPlatform() === 'web') return
  if (show) {
    StatusBar.show()
  } else {
    StatusBar.hide()
  }
}

Vue.prototype.$isDev = process.env.NODE_ENV !== 'production'

Vue.prototype.$getAndroidSDKVersion = async () => {
  if (Capacitor.getPlatform() !== 'android') return null
  const data = await AbsFileSystem.getSDKVersion()
  if (isNaN(data?.version)) return null
  return Number(data.version)
}

Vue.prototype.$encodeUriPath = (path) => {
  return path.replace(/\\/g, '/').replace(/%/g, '%25').replace(/#/g, '%23')
}

Vue.prototype.$setDateFnsLocale = (localeString) => {
  if (!locale[localeString]) return 0
  return setDefaultOptions({ locale: locale[localeString] })
}
Vue.prototype.$dateDistanceFromNow = (unixms) => {
  if (!unixms) return ''
  return formatDistance(unixms, Date.now(), { addSuffix: true })
}
Vue.prototype.$formatDate = (unixms, fnsFormat = 'MM/dd/yyyy HH:mm') => {
  if (!unixms) return ''
  return format(unixms, fnsFormat)
}
Vue.prototype.$formatJsDate = (jsdate, fnsFormat = 'MM/dd/yyyy HH:mm') => {
  if (!jsdate || !isDate(jsdate)) return ''
  return format(jsdate, fnsFormat)
}
Vue.prototype.$addDaysToToday = (daysToAdd) => {
  var date = addDays(new Date(), daysToAdd)
  if (!date || !isDate(date)) return null
  return date
}
Vue.prototype.$addDaysToDate = (jsdate, daysToAdd) => {
  var date = addDays(jsdate, daysToAdd)
  if (!date || !isDate(date)) return null
  return date
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

Vue.prototype.$elapsedPretty = (seconds, useFullNames = false) => {
  if (seconds < 60) {
    return `${Math.floor(seconds)} sec${useFullNames ? 'onds' : ''}`
  }
  var minutes = Math.floor(seconds / 60)
  if (minutes < 70) {
    return `${minutes} min${useFullNames ? `ute${minutes === 1 ? '' : 's'}` : ''}`
  }
  var hours = Math.floor(minutes / 60)
  minutes -= hours * 60
  if (!minutes) {
    return `${hours} ${useFullNames ? 'hours' : 'hr'}`
  }
  return `${hours} ${useFullNames ? `hour${hours === 1 ? '' : 's'}` : 'hr'} ${minutes} ${useFullNames ? `minute${minutes === 1 ? '' : 's'}` : 'min'}`
}

Vue.prototype.$elapsedPrettyExtended = (seconds, useDays = true, showSeconds = true) => {
  if (isNaN(seconds) || seconds === null) return ''
  seconds = Math.round(seconds)

  let minutes = Math.floor(seconds / 60)
  seconds -= minutes * 60
  let hours = Math.floor(minutes / 60)
  minutes -= hours * 60

  let days = 0
  if (useDays || Math.floor(hours / 24) >= 100) {
    days = Math.floor(hours / 24)
    hours -= days * 24
  }

  // If not showing seconds then round minutes up
  if (minutes && seconds && !showSeconds) {
    if (seconds >= 30) minutes++
  }

  const strs = []
  if (days) strs.push(`${days}d`)
  if (hours) strs.push(`${hours}h`)
  if (minutes) strs.push(`${minutes}m`)
  if (seconds && showSeconds) strs.push(`${seconds}s`)
  return strs.join(' ')
}

Vue.prototype.$secondsToTimestamp = (seconds) => {
  let _seconds = seconds
  let _minutes = Math.floor(seconds / 60)
  _seconds -= _minutes * 60
  let _hours = Math.floor(_minutes / 60)
  _minutes -= _hours * 60
  _seconds = Math.floor(_seconds)
  if (!_hours) {
    return `${_minutes}:${_seconds.toString().padStart(2, '0')}`
  }
  return `${_hours}:${_minutes.toString().padStart(2, '0')}:${_seconds.toString().padStart(2, '0')}`
}

Vue.prototype.$secondsToTimestampFull = (seconds) => {
  let _seconds = Math.round(seconds)
  let _minutes = Math.floor(seconds / 60)
  _seconds -= _minutes * 60
  let _hours = Math.floor(_minutes / 60)
  _minutes -= _hours * 60
  _seconds = Math.floor(_seconds)
  return `${_hours.toString().padStart(2, '0')}:${_minutes.toString().padStart(2, '0')}:${_seconds.toString().padStart(2, '0')}`
}

Vue.prototype.$sanitizeFilename = (input, colonReplacement = ' - ') => {
  if (typeof input !== 'string') {
    return false
  }

  // Max is actually 255-260 for windows but this leaves padding incase ext wasnt put on yet
  const MAX_FILENAME_LEN = 240

  var replacement = ''
  var illegalRe = /[\/\?<>\\:\*\|"]/g
  var controlRe = /[\x00-\x1f\x80-\x9f]/g
  var reservedRe = /^\.+$/
  var windowsReservedRe = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i
  var windowsTrailingRe = /[\. ]+$/
  var lineBreaks = /[\n\r]/g

  var sanitized = input
    .replace(':', colonReplacement) // Replace first occurrence of a colon
    .replace(illegalRe, replacement)
    .replace(controlRe, replacement)
    .replace(reservedRe, replacement)
    .replace(lineBreaks, replacement)
    .replace(windowsReservedRe, replacement)
    .replace(windowsTrailingRe, replacement)

  if (sanitized.length > MAX_FILENAME_LEN) {
    var lenToRemove = sanitized.length - MAX_FILENAME_LEN
    var ext = Path.extname(sanitized)
    var basename = Path.basename(sanitized, ext)
    basename = basename.slice(0, basename.length - lenToRemove)
    sanitized = basename + ext
  }

  return sanitized
}

function xmlToJson(xml) {
  const json = {}
  for (const res of xml.matchAll(/(?:<(\w*)(?:\s[^>]*)*>)((?:(?!<\1).)*)(?:<\/\1>)|<(\w*)(?:\s*)*\/>/gm)) {
    const key = res[1] || res[3]
    const value = res[2] && xmlToJson(res[2])
    json[key] = (value && Object.keys(value).length ? value : res[2]) || null
  }
  return json
}
Vue.prototype.$xmlToJson = xmlToJson

const encode = (text) => encodeURIComponent(Buffer.from(text).toString('base64'))
Vue.prototype.$encode = encode
const decode = (text) => Buffer.from(decodeURIComponent(text), 'base64').toString()
Vue.prototype.$decode = decode

Vue.prototype.$setOrientationLock = (orientationLockSetting) => {
  if (!window.screen?.orientation) return

  if (orientationLockSetting == 'PORTRAIT') {
    window.screen.orientation.lock?.('portrait').catch((error) => {
      console.error(error)
    })
  } else if (orientationLockSetting == 'LANDSCAPE') {
    window.screen.orientation.lock?.('landscape').catch((error) => {
      console.error(error)
    })
  } else {
    window.screen.orientation.unlock?.()
  }
}

Vue.prototype.$copyToClipboard = (str) => {
  return Clipboard.write({
    string: str
  })
}

// SOURCE: https://gist.github.com/spyesx/561b1d65d4afb595f295
//   modified: allowed underscores
Vue.prototype.$sanitizeSlug = (str) => {
  if (!str) return ''

  str = str.replace(/^\s+|\s+$/g, '') // trim
  str = str.toLowerCase()

  // remove accents, swap ñ for n, etc
  var from = 'àáäâèéëêìíïîòóöôùúüûñçěščřžýúůďťň·/,:;'
  var to = 'aaaaeeeeiiiioooouuuuncescrzyuudtn-----'

  for (var i = 0, l = from.length; i < l; i++) {
    str = str.replace(new RegExp(from.charAt(i), 'g'), to.charAt(i))
  }

  str = str
    .replace('.', '-') // replace a dot by a dash
    .replace(/[^a-z0-9 -_]/g, '') // remove invalid chars
    .replace(/\s+/g, '-') // collapse whitespace and replace by a dash
    .replace(/-+/g, '-') // collapse dashes
    .replace(/\//g, '') // collapse all forward-slashes

  return str
}

export default ({ store, app }, inject) => {
  const eventBus = new Vue()
  inject('eventBus', eventBus)

  // Set theme
  app.$localStore?.getTheme()?.then((theme) => {
    if (theme) {
      document.documentElement.dataset.theme = theme
    }
  })

  // iOS Only
  //  backButton event does not work with iOS swipe navigation so use this workaround
  if (app.router && Capacitor.getPlatform() === 'ios') {
    app.router.beforeEach((to, from, next) => {
      if (store.state.globals.isModalOpen) {
        eventBus.$emit('close-modal')
      }
      if (store.state.playerIsFullscreen) {
        eventBus.$emit('minimize-player')
      }
      if (store.state.showReader) {
        eventBus.$emit('close-ebook')
      }
      next()
    })
  }

  // Android only
  App.addListener('backButton', async ({ canGoBack }) => {
    if (store.state.globals.isModalOpen) {
      eventBus.$emit('close-modal')
      return
    }
    if (store.state.showReader) {
      eventBus.$emit('close-ebook')
      return
    }
    if (store.state.playerIsFullscreen) {
      eventBus.$emit('minimize-player')
      return
    }
    if (!canGoBack) {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Did you want to exit the app?`
      })
      if (value) {
        App.exitApp()
      }
    } else {
      window.history.back()
    }
  })

  /**
   * @see https://capacitorjs.com/docs/apis/app#addlistenerappurlopen-
   * Listen for url open events for the app. This handles both custom URL scheme links as well as URLs your app handles
   */
  App.addListener('appUrlOpen', (data) => {
    eventBus.$emit('url-open', data.url)
  })
}

export { encode, decode }
