import { CapacitorHttp } from '@capacitor/core'

export default function ({ store }, inject) {
  const nativeHttp = {
    request(method, _url, data, options = {}) {
      let url = _url
      const headers = {}
      if (!url.startsWith('http') && !url.startsWith('capacitor')) {
        const bearerToken = store.getters['user/getToken']
        if (bearerToken) {
          headers['Authorization'] = `Bearer ${bearerToken}`
        } else {
          console.warn('[nativeHttp] No Bearer Token for request')
        }
        const serverUrl = store.getters['user/getServerAddress']
        if (serverUrl) {
          url = `${serverUrl}${url}`
        }
      }
      if (data) {
        headers['Content-Type'] = 'application/json'
      }
      console.log(`[nativeHttp] Making ${method} request to ${url}`)
      return CapacitorHttp.request({
        method,
        url,
        data,
        headers,
        ...options
      }).then(res => {
        if (res.status >= 400) {
          console.error(`[nativeHttp] ${res.status} status for url "${url}"`)
          throw new Error(res.data)
        }
        return res.data
      })
    },
    get(url, options = {}) {
      return this.request('GET', url, undefined, options)
    },
    post(url, data, options = {}) {
      return this.request('POST', url, data, options)
    },
    patch(url, data, options = {}) {
      return this.request('PATCH', url, data, options)
    },
    delete(url, options = {}) {
      return this.request('DELETE', url, undefined, options)
    }
  }
  inject('nativeHttp', nativeHttp)
}