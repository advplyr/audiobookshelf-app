export default function ({ $axios, store }) {
  $axios.onRequest(config => {
    console.log('[Axios] Making request to ' + config.url)
    if (config.url.startsWith('http:') || config.url.startsWith('https:')) {
      return
    }

    const customHeaders = store.getters['user/getCustomHeaders']
    if (customHeaders) {
      for (const key in customHeaders) {
        config.headers.common[key] = customHeaders[key]
      }
    }

    const bearerToken = store.getters['user/getToken']
    if (bearerToken) {
      config.headers.common['Authorization'] = `Bearer ${bearerToken}`
    } else {
      console.warn('[Axios] No Bearer Token for request')
    }

    const serverUrl = store.getters['user/getServerAddress']
    if (serverUrl) {
      config.url = `${serverUrl}${config.url}`
    }
    console.log('[Axios] Request out', config.url)
  })

  $axios.onError(error => {
    console.error('Axios error code', error)
  })
}