export default function ({ $axios, store, $db }) {
  // Track if we're currently refreshing to prevent multiple refresh attempts
  let isRefreshing = false
  let failedQueue = []

  const processQueue = (error, token = null) => {
    failedQueue.forEach(({ resolve, reject }) => {
      if (error) {
        reject(error)
      } else {
        resolve(token)
      }
    })
    failedQueue = []
  }

  /**
   * Handles the case when token refresh fails
   * @param {string} [serverConnectionConfigId]
   * @returns {Promise} - Promise that resolves when logout is complete
   */
  const handleRefreshFailure = async (serverConnectionConfigId) => {
    try {
      console.log('[axios] Handling refresh failure - logging out user')

      // Clear store
      await store.dispatch('user/logout')

      if (serverConnectionConfigId) {
        // Clear refresh token for server connection config
        await $db.clearRefreshToken(serverConnectionConfigId)
      }

      if (window.location.pathname !== '/connect') {
        window.location.href = '/connect?error=refreshTokenFailed&serverConnectionConfigId=' + serverConnectionConfigId
      }
    } catch (error) {
      console.error('[axios] Failed to handle refresh failure:', error)
    }
  }

  $axios.onRequest((config) => {
    console.log('[Axios] Making request to ' + config.url)
    if (config.url.startsWith('http:') || config.url.startsWith('https:') || config.url.startsWith('capacitor:')) {
      return
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

  $axios.onError(async (error) => {
    const originalRequest = error.config
    const code = parseInt(error.response && error.response.status)
    const message = error.response ? error.response.data || 'Unknown Error' : 'Unknown Error'

    console.error('Axios error', code, message)

    // Handle 401 Unauthorized (token expired)
    if (code === 401 && !originalRequest._retry) {
      // Skip refresh for auth endpoints to prevent infinite loops
      if (originalRequest.url.endsWith('/auth/refresh') || originalRequest.url.endsWith('/login')) {
        await handleRefreshFailure(store.getters['user/getServerConnectionConfigId'])
        return Promise.reject(error)
      }

      if (isRefreshing) {
        // If already refreshing, queue this request
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        })
          .then((token) => {
            if (!originalRequest.headers) {
              originalRequest.headers = {}
            }
            originalRequest.headers['Authorization'] = `Bearer ${token}`
            return $axios(originalRequest)
          })
          .catch((err) => {
            return Promise.reject(err)
          })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        // Attempt to refresh the token
        // Updates store if successful, otherwise clears store and throw error
        const newAccessToken = await store.dispatch('user/refreshToken')
        if (!newAccessToken) {
          console.error('No new access token received')
          return Promise.reject(error)
        }

        // Update the original request with new token
        if (!originalRequest.headers) {
          originalRequest.headers = {}
        }
        originalRequest.headers['Authorization'] = `Bearer ${newAccessToken}`

        // Process any queued requests
        processQueue(null, newAccessToken)

        // Retry the original request
        return $axios(originalRequest)
      } catch (refreshError) {
        console.error('Token refresh failed:', refreshError)

        // Process queued requests with error
        processQueue(refreshError, null)

        await handleRefreshFailure(store.getters['user/getServerConnectionConfigId'])

        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  })
}
