import Vue from 'vue'
import enUsStrings from '../strings/en-us.json'

const defaultCode = 'en-us'
let $localStore = null

const languageCodeMap = {
  cs: { label: 'Čeština', dateFnsLocale: 'cs' },
  da: { label: 'Dansk', dateFnsLocale: 'da' },
  de: { label: 'Deutsch', dateFnsLocale: 'de' },
  'en-us': { label: 'English', dateFnsLocale: 'enUS' },
  es: { label: 'Español', dateFnsLocale: 'es' },
  fi: { label: 'Suomi', dateFnsLocale: 'fi' },
  fr: { label: 'Français', dateFnsLocale: 'fr' },
  hr: { label: 'Hrvatski', dateFnsLocale: 'hr' },
  it: { label: 'Italiano', dateFnsLocale: 'it' },
  lt: { label: 'Lietuvių', dateFnsLocale: 'lt' },
  hu: { label: 'Magyar', dateFnsLocale: 'hu' },
  nl: { label: 'Nederlands', dateFnsLocale: 'nl' },
  no: { label: 'Norsk', dateFnsLocale: 'no' },
  pl: { label: 'Polski', dateFnsLocale: 'pl' },
  'pt-br': { label: 'Português (Brasil)', dateFnsLocale: 'ptBR' },
  ru: { label: 'Русский', dateFnsLocale: 'ru' },
  sv: { label: 'Svenska', dateFnsLocale: 'sv' },
  uk: { label: 'Українська', dateFnsLocale: 'uk' },
  'vi-vn': { label: 'Tiếng Việt', dateFnsLocale: 'vi' },
  'zh-cn': { label: '简体中文 (Simplified Chinese)', dateFnsLocale: 'zhCN' }
}

function supplant(str, subs) {
  // source: http://crockford.com/javascript/remedial.html
  return str.replace(/{([^{}]*)}/g, function (a, b) {
    var r = subs[b]
    return typeof r === 'string' || typeof r === 'number' ? r : a
  })
}

Vue.prototype.$languageCodeOptions = Object.keys(languageCodeMap).map((code) => {
  return {
    text: languageCodeMap[code].label,
    value: code
  }
})

Vue.prototype.$languageCodes = {
  default: defaultCode,
  current: defaultCode,
  local: null,
  server: null
}

Vue.prototype.$strings = { ...enUsStrings }

Vue.prototype.$getString = (key, subs) => {
  if (!Vue.prototype.$strings[key]) return ''
  if (subs && Array.isArray(subs) && subs.length) {
    return supplant(Vue.prototype.$strings[key], subs)
  }
  return Vue.prototype.$strings[key]
}

var translations = {
  [defaultCode]: enUsStrings
}

function loadTranslationStrings(code) {
  return new Promise((resolve) => {
    import(`../strings/${code}`)
      .then((fileContents) => {
        resolve(fileContents.default)
      })
      .catch((error) => {
        console.error('Failed to load i18n strings', code, error)
        resolve(null)
      })
  })
}

async function loadi18n(code) {
  if (!code) return false
  if (Vue.prototype.$languageCodes.current == code) {
    // already set
    return false
  }

  const strings = translations[code] || (await loadTranslationStrings(code))
  if (!strings) {
    console.warn(`Invalid lang code ${code}`)
    return false
  }

  translations[code] = strings
  Vue.prototype.$languageCodes.current = code
  $localStore.setLanguage(code)

  for (const key in Vue.prototype.$strings) {
    Vue.prototype.$strings[key] = strings[key] || translations[defaultCode][key]
  }

  Vue.prototype.$setDateFnsLocale(languageCodeMap[code].dateFnsLocale)

  this.$eventBus.$emit('change-lang', code)
  return true
}

Vue.prototype.$setLanguageCode = loadi18n

// Set the servers default language code, does not override users local language code
Vue.prototype.$setServerLanguageCode = (code) => {
  if (!code) return

  if (!languageCodeMap[code]) {
    console.warn('invalid server language in', code)
  } else {
    Vue.prototype.$languageCodes.server = code
    if (!Vue.prototype.$languageCodes.local && code !== defaultCode) {
      loadi18n(code)
    }
  }
}

// Initialize with language code in localStorage if valid
async function initialize() {
  const localLanguage = await $localStore.getLanguage()
  if (!localLanguage) return

  if (!languageCodeMap[localLanguage]) {
    console.warn('Invalid local language code', localLanguage)
    $localStore.setLanguage(defaultCode)
  } else {
    Vue.prototype.$languageCodes.local = localLanguage
    loadi18n(localLanguage)
  }
}

export default ({ app, store }, inject) => {
  $localStore = app.$localStore
  initialize()
}
