export default function ({ $axios, store }) {
  $axios.onRequest(config => {
    console.log('[Axios] Making request to ' + config.url)
    if (config.url.startsWith('http:') || config.url.startsWith('https:')) {
      return
    }
    var bearerToken = store.getters['user/getToken']
    if (bearerToken) {
      config.headers.common['Authorization'] = `Bearer ${bearerToken}`
    } else {
      console.warn('[Axios] No Bearer Token for request')
    }

    var serverUrl = store.getters['user/getServerAddress']
    if (serverUrl) {
      config.url = `${serverUrl}${config.url}`
    }
    console.log('[Axios] Request out', config.url)
  })

  $axios.onError(error => {
    const code = parseInt(error.response && error.response.status)
    console.error('Axios error code', code)
  })
}