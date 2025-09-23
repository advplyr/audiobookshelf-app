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

// Helper: map app theme -> native status bar content style.
// We want dark app theme to use light status bar icons (light content),
// and light app theme to use dark status bar icons (dark content).
const updateNativeStatusBarStyle = async (themeOrBool) => {
  if (Capacitor.getPlatform() === 'web') return
  try {
    let isDark = false
    if (typeof themeOrBool === 'boolean') {
      isDark = themeOrBool
    } else if (themeOrBool === 'system') {
      isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    } else {
      isDark = themeOrBool === 'dark'
    }
    // When app is dark -> request Light status bar content (white icons)
    // When app is light -> request Dark status bar content (dark icons)
    await StatusBar.setStyle({ style: isDark ? Style.Dark : Style.Light })
  } catch (e) {
    console.error('Failed to update native status bar style', e)
  }
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

/**
 * Compares two semantic versioning strings to determine if the current version meets
 * or exceeds the minimum version requirement.
 * Only supports 3 part versions, e.g. "1.2.3"
 *
 * @param {string} currentVersion - The current version string to compare, e.g., "1.2.3".
 * @param {string} minVersion - The minimum version string required, e.g., "1.0.0".
 * @returns {boolean} - Returns true if the current version is greater than or equal
 *                      to the minimum version, false otherwise.
 */
function isValidVersion(currentVersion, minVersion) {
  if (!currentVersion || !minVersion) return false
  const currentParts = currentVersion.split('.').map(Number)
  const minParts = minVersion.split('.').map(Number)

  for (let i = 0; i < minParts.length; i++) {
    if (currentParts[i] > minParts[i]) return true
    if (currentParts[i] < minParts[i]) return false
  }

  return true
}

export default ({ store, app }, inject) => {
  const eventBus = new Vue()
  inject('eventBus', eventBus)

  inject('isValidVersion', isValidVersion)

  // Expose helper so other parts of the app can toggle native status bar theme
  Vue.prototype.$updateStatusBarTheme = updateNativeStatusBarStyle

  // Calculate and store mini player bottom positions once at startup
  const calculateMiniPlayerPositions = () => {
    // Calculate the two positions the mini player should use
    let withTabBarPosition = '88px' // Default fallback
    let withoutTabBarPosition = '8px' // Default fallback

    try {
      // Get safe area bottom inset
      const safeInset = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-bottom')?.replace('px', '')) || 0
      const clampedSafeInset = Math.min(safeInset, 16) // Clamp like BookshelfNavBar does

      // Base navigation height (content area)
      const baseNavHeight = 56

      // Total tab bar height when visible (base + safe area) - back to original calculation
      const totalTabBarHeight = baseNavHeight + clampedSafeInset

      // Position 1: When tab bar is visible - above the tab bar with small gap
      // Account for the tab bar's 1px top border by reducing the gap
      withTabBarPosition = `${totalTabBarHeight + 10}px`

      // Position 2: When tab bar is NOT visible - maintain same visual position from bottom
      // This is the same distance from bottom as when tab bar is visible
      withoutTabBarPosition = `${clampedSafeInset + 4}px`

      console.log('[Init] Mini player positions calculated:', {
        safeInset,
        clampedSafeInset,
        baseNavHeight,
        totalTabBarHeight,
        withTabBar: withTabBarPosition,
        withoutTabBar: withoutTabBarPosition
      })
    } catch (e) {
      console.warn('[Init] Error calculating mini player positions, using fallbacks:', e)
    }

    // Store positions globally
    window.MINI_PLAYER_POSITIONS = {
      withTabBar: withTabBarPosition,
      withoutTabBar: withoutTabBarPosition
    }

    // Also set CSS custom properties for easier access
    document.documentElement.style.setProperty('--mini-player-bottom-with-tab', withTabBarPosition)
    document.documentElement.style.setProperty('--mini-player-bottom-without-tab', withoutTabBarPosition)

    // Notify components that positions are ready
    if (typeof window !== 'undefined' && window.dispatchEvent) {
      window.dispatchEvent(new CustomEvent('miniPlayerPositionsReady', {
        detail: window.MINI_PLAYER_POSITIONS
      }))
    }
  }

  // Expose function globally for app resume/orientation change scenarios
  window.recalculateMiniPlayerPositions = calculateMiniPlayerPositions

  // Calculate positions after safe area variables are ready
  const initMiniPlayerPositions = () => {
    const maxAttempts = 10
    let attempts = 0

    function tryCalculate() {
      attempts++
      const bottom = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-bottom')

      // Check if we have safe area values or reached max attempts
      if ((bottom && bottom.trim()) || attempts >= maxAttempts) {
        calculateMiniPlayerPositions()
        return
      }

      setTimeout(tryCalculate, 100)
    }

    if (typeof window !== 'undefined') tryCalculate()
  }

  // Ensure safe-area CSS variables are present and notify the document when ready.
  // Some WebView environments may not have them immediately available on first paint.
  const ensureSafeAreaVars = () => {
    const maxAttempts = 100 // Increased to 100 attempts (10 seconds total)
    let attempts = 0

    function check() {
      attempts++
      const top = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top')
      const bottom = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-bottom')

      // Check if we have meaningful values (not empty, not "0px", not just whitespace)
      const hasTop = top && top.trim() && top.trim() !== '0px'
      const hasBottom = bottom && bottom.trim() && bottom.trim() !== '0px'

      if (hasTop || hasBottom || attempts >= maxAttempts) {
        // Mark that safe-area values are available (or we've given up)
        document.documentElement.setAttribute('data-safe-area-ready', 'true')
        console.log(`[SafeArea] Ready after ${attempts} attempts. Top: ${top}, Bottom: ${bottom}`)
        return
      }

      // On Android, also try to trigger the inset listener by requesting layout
      if (attempts % 5 === 0 && window.requestAnimationFrame) {
        window.requestAnimationFrame(() => {
          if (document.documentElement.style) {
            document.documentElement.style.transform = 'translateZ(0)'
            setTimeout(() => {
              document.documentElement.style.transform = ''
            }, 10)
          }
        })
      }

      setTimeout(check, 100)
    }

    if (typeof window !== 'undefined') check()
  }

  // Use different initialization strategies for different platforms
  if (Capacitor.getPlatform() === 'android') {
    // For Android, wait for the next tick to ensure the WebView is fully initialized
    setTimeout(() => {
      ensureSafeAreaVars()
      // Initialize mini player positions after safe area is ready
      initMiniPlayerPositions()
    }, 50)

    // Also set fallback values immediately to prevent layout issues
    if (typeof window !== 'undefined') {
      // Set reasonable fallback values for Android devices
      const docStyle = document.documentElement.style
      if (!docStyle.getPropertyValue('--safe-area-inset-top')) {
        // Use more reliable fallback - check if device has status bar
        const statusBarHeight = window.devicePixelRatio > 1 ? '28px' : '24px'
        docStyle.setProperty('--safe-area-inset-top', statusBarHeight)
        console.log('[Init] Set Android status bar fallback:', statusBarHeight)
      }
      if (!docStyle.getPropertyValue('--safe-area-inset-bottom')) {
        docStyle.setProperty('--safe-area-inset-bottom', '0px') // Will be updated by native
      }
      if (!docStyle.getPropertyValue('--safe-area-inset-left')) {
        docStyle.setProperty('--safe-area-inset-left', '0px')
      }
      if (!docStyle.getPropertyValue('--safe-area-inset-right')) {
        docStyle.setProperty('--safe-area-inset-right', '0px')
      }
    }
  } else {
    // For other platforms, initialize immediately
    ensureSafeAreaVars()
    // Initialize mini player positions after safe area is ready
    initMiniPlayerPositions()
  }

  // Set theme with Material You integration for all themes
  app.$localStore?.getTheme()?.then((theme) => {
    if (theme) {
      if (theme === 'system') {
        // Use system theme - detect and apply based on Android system preference
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
        document.documentElement.dataset.theme = prefersDark ? 'dark' : 'light'

        // Listen for system theme changes
        if (window.matchMedia) {
          const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
          mediaQuery.addEventListener('change', (e) => {
            // Only apply if we're still using system theme
            app.$localStore?.getTheme()?.then((currentTheme) => {
              if (currentTheme === 'system') {
                document.documentElement.dataset.theme = e.matches ? 'dark' : 'light'
                // Reapply Material You colors for the new theme
                if (app.$dynamicColor) {
                  app.$dynamicColor.initialize()
                }
                // Update native status bar style to match the new effective theme
                updateNativeStatusBarStyle(e.matches)
              }

              // Re-check safe area variables when theme changes (in case they weren't ready initially)
              ensureSafeAreaVars()
            })
          })
        }
        // If using system theme initially, set native status bar according to current system
        if (theme === 'system') {
          updateNativeStatusBarStyle('system')
        } else {
          updateNativeStatusBarStyle(theme)
        }
      } else {
        // Use explicit theme (dark or light) with Material You
        document.documentElement.dataset.theme = theme
      }

      // Apply Material You colors for all themes if available
      if (app.$dynamicColor) {
        app.$dynamicColor.initialize()
      }
    } else {
      // Default to system theme if no theme is stored
      const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
      document.documentElement.dataset.theme = prefersDark ? 'dark' : 'light'

      // Apply Material You colors for default theme
      if (app.$dynamicColor) {
        app.$dynamicColor.initialize()
      }
      // No stored theme -> defaulted to system effective theme above. Update native status bar to match.
      updateNativeStatusBarStyle(prefersDark)
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
    // Close any open modal immediately on Android back.
    // Some modals (like the Add/Create playlist modal) may set a dedicated flag
    // before the shared `isModalOpen` is committed, so check those as well.
    if (store.state.globals.isModalOpen || store.state.globals.showPlaylistsAddCreateModal || store.state.globals.showSelectLocalFolderModal || store.state.globals.showRSSFeedOpenCloseModal) {
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

  // Listen for app state changes to recalculate mini player positions when needed
  App.addListener('appStateChange', ({ isActive }) => {
    if (isActive && window.recalculateMiniPlayerPositions) {
      // Recalculate positions when app becomes active (e.g., after background)
      setTimeout(() => {
        window.recalculateMiniPlayerPositions()
      }, 100)
    }
  })

  // Listen for orientation changes to recalculate positions
  if (typeof window !== 'undefined') {
    const handleOrientationChange = () => {
      if (window.recalculateMiniPlayerPositions) {
        setTimeout(() => {
          window.recalculateMiniPlayerPositions()
        }, 200) // Longer delay for orientation changes
      }
    }

    if (screen.orientation) {
      screen.orientation.addEventListener('change', handleOrientationChange)
    } else {
      window.addEventListener('orientationchange', handleOrientationChange)
    }
  }
}

export { encode, decode }
