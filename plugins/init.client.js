import { AbsFileSystem } from '@/plugins/capacitor'
import { App } from '@capacitor/app'
import { Clipboard } from '@capacitor/clipboard'
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { StatusBar, Style } from '@capacitor/status-bar'
import { addDays, format, formatDistance, isDate, setDefaultOptions } from 'date-fns'
import * as locale from 'date-fns/locale'
import vClickOutside from 'v-click-outside'
import Vue from 'vue'

Vue.directive('click-outside', vClickOutside.directive)

if (Capacitor.getPlatform() != 'web') {
  const setStatusBarStyleDark = async () => {
    await StatusBar.setStyle({ style: Style.Dark })
  }
  setStatusBarStyleDark()
}

Vue.prototype.$setDateFnsLocale = (localeString) => {
  if (!locale[localeString]) return 0
  return setDefaultOptions({ locale: locale[localeString] })
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
    return `${Math.floor(seconds)} ${useFullNames ? Vue.prototype.$strings.LabelSeconds : Vue.prototype.$strings.LabelSec}`
  }
  var minutes = Math.floor(seconds / 60)
  if (minutes < 70) {
    return `${minutes} ${useFullNames ? `${minutes === 1 ? Vue.prototype.$strings.LabelMinute : Vue.prototype.$strings.LabelMinutes}` : Vue.prototype.$strings.LabelMin}`
  }
  var hours = Math.floor(minutes / 60)
  minutes -= hours * 60
  if (!minutes) {
    return `${hours} ${useFullNames ? Vue.prototype.$strings.LabelHours : Vue.prototype.$strings.LabelHr }`
  }
  return `${hours} ${useFullNames ? `${hours === 1 ? Vue.prototype.$strings.LabelHour : Vue.prototype.$strings.LabelHours}` : Vue.prototype.$strings.LabelHr} ${minutes} ${useFullNames ? minutes === 1 ? Vue.prototype.$strings.LabelMinute : Vue.prototype.$strings.LabelMinutes : Vue.prototype.$strings.LabelMin}`
}

Vue.prototype.$elapsedPrettyExtended = (seconds, useDays = true) => {
  if (isNaN(seconds) || seconds === null) return ''
  seconds = Math.round(seconds)

  var minutes = Math.floor(seconds / 60)
  seconds -= minutes * 60
  var hours = Math.floor(minutes / 60)
  minutes -= hours * 60

  var days = 0
  if (useDays || Math.floor(hours / 24) >= 100) {
    days = Math.floor(hours / 24)
    hours -= days * 24
  }

  var strs = []
  if (days) strs.push(`${days}d`)
  if (hours) strs.push(`${hours}h`)
  if (minutes) strs.push(`${minutes}m`)
  if (seconds) strs.push(`${seconds}s`)
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
  var from = "àáäâèéëêìíïîòóöôùúüûñçěščřžýúůďťň·/,:;"
  var to = "aaaaeeeeiiiioooouuuuncescrzyuudtn-----"

  for (var i = 0, l = from.length; i < l; i++) {
    str = str.replace(new RegExp(from.charAt(i), 'g'), to.charAt(i))
  }

  str = str.replace('.', '-') // replace a dot by a dash
    .replace(/[^a-z0-9 -_]/g, '') // remove invalid chars
    .replace(/\s+/g, '-') // collapse whitespace and replace by a dash
    .replace(/-+/g, '-') // collapse dashes
    .replace(/\//g, '') // collapse all forward-slashes

  return str
}

export default ({ store, app }, inject) => {
  const eventBus = new Vue()
  inject('eventBus', eventBus)

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
        title: Vue.prototype.$strings.LabelConfirm,
        message: Vue.prototype.$strings.MessageExit,
      })
      if (value) {
        App.exitApp()
      }
    } else {
      window.history.back()
    }
  })
}

export {
  decode, encode
}

