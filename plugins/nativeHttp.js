import { CapacitorHttp } from '@capacitor/core'

export default function ({ store, $db, $socket }, inject) {
  const nativeHttp = {
    async request(method, _url, data, options = {}) {
      // When authorizing before a config is set, server config gets passed in as an option
      let serverConnectionConfig = options.serverConnectionConfig || store.state.user.serverConnectionConfig
      delete options.serverConnectionConfig

      let url = _url
      let headers = {}
      if (!url.startsWith('http') && !url.startsWith('capacitor')) {
        const bearerToken = store.getters['user/getToken']
        if (bearerToken) {
          headers['Authorization'] = `Bearer ${bearerToken}`
        } else {
          console.warn('[nativeHttp] No Bearer Token for request')
        }
        if (serverConnectionConfig?.address) {
          url = `${serverConnectionConfig.address}${url}`
        }
      }
      if (data) {
        headers['Content-Type'] = 'application/json'
      }
      if (options.headers) {
        headers = { ...headers, ...options.headers }
        delete options.headers
      }
      console.log(`[nativeHttp] Making ${method} request to ${url}`)

      return CapacitorHttp.request({
        method,
        url,
        data,
        headers,
        ...options
      }).then((res) => {
        if (res.status === 401) {
          console.error(`[nativeHttp] 401 status for url "${url}"`)
          // Handle refresh token automatically
          return this.handleTokenRefresh(method, url, data, headers, options, serverConnectionConfig)
        }
        if (res.status >= 400) {
          console.error(`[nativeHttp] ${res.status} status for url "${url}"`)
          const message = typeof res.data === 'string' ? res.data : `HTTP ${res.status}`
          throw new Error(message)
        }
        return res.data
      })
    },

    /**
     * Handles token refresh when a 401 Unauthorized response is received
     * @param {string} method - HTTP method
     * @param {string} url - Full URL
     * @param {*} data - Request data
     * @param {Object} headers - Request headers
     * @param {Object} options - Additional options
     * @param {{ id: string, address: string, version: string }} serverConnectionConfig
     * @returns {Promise} - Promise that resolves with the response data
     */
    async handleTokenRefresh(method, url, data, headers, options, serverConnectionConfig) {
      try {
        console.log('[nativeHttp] Attempting to refresh token...')

        if (!serverConnectionConfig?.id) {
          console.error('[nativeHttp] No server connection config ID available for token refresh')
          throw new Error('No server connection available')
        }

        // Get refresh token from secure storage
        const refreshToken = await $db.getRefreshToken(serverConnectionConfig.id)
        if (!refreshToken) {
          console.error('[nativeHttp] No refresh token available')
          throw new Error('No refresh token available')
        }

        // Attempt to refresh the token
        const refreshResult = await this.refreshAccessToken(refreshToken, serverConnectionConfig.address)
        if (refreshResult?._permanentFailure) {
          console.error('[nativeHttp] Token refresh permanently rejected - logging out')
          const err = new Error('Token refresh permanently failed (server rejected refresh token)')
          err.permanent = true
          throw err
        }
        if (refreshResult?._transientFailure) {
          console.error('[nativeHttp] Token refresh failed (transient): keeping credentials')
          const err = new Error('Token refresh failed with status ' + refreshResult.status + ' (server may be temporarily unavailable)')
          err.transient = true
          throw err
        }
        if (!refreshResult?.accessToken) {
          console.error('[nativeHttp] Failed to refresh access token')
          const err = new Error('Failed to refresh access token')
          err.permanent = true
          throw err
        }

        // Update the store with new tokens
        await this.updateTokens(refreshResult, serverConnectionConfig)

        // Retry the original request with the new token
        console.log('[nativeHttp] Retrying original request with new token...')
        const retryResponse = await CapacitorHttp.request({
          method,
          url,
          data,
          headers: {
            ...headers,
            Authorization: `Bearer ${refreshResult.accessToken}`
          },
          ...options
        })

        if (retryResponse.status >= 400) {
          console.error(`[nativeHttp] Retry request failed with status ${retryResponse.status}`)
          const message = typeof retryResponse.data === 'string' ? retryResponse.data : `HTTP ${retryResponse.status}`
          throw new Error(message)
        }

        return retryResponse.data
      } catch (error) {
        console.error('[nativeHttp] Token refresh failed:', error)

        // Only log out on permanent rejection (401); transient errors keep credentials
        if (!error?.transient) {
          await this.handleRefreshFailure(serverConnectionConfig?.id)
        }
        throw error
      }
    },

    /**
     * Refreshes the access token using the refresh token
     * @param {string} refreshToken - The refresh token
     * @param {string} serverAddress - The server address
     * @returns {Promise<Object|null>} - Promise that resolves with new tokens or null
     */
    async refreshAccessToken(refreshToken, serverAddress) {
      try {
        if (!serverAddress) {
          throw new Error('No server address available')
        }

        console.log('[nativeHttp] Refreshing access token...')

        const response = await CapacitorHttp.post({
          url: `${serverAddress}/auth/refresh`,
          headers: {
            'Content-Type': 'application/json',
            'x-refresh-token': refreshToken
          },
          data: {}
        })

        if (response.status === 401) {
          console.error('[nativeHttp] Token refresh request rejected (401): returning permanent failure')
          return { _permanentFailure: true }
        }
        if (response.status !== 200) {
          console.error('[nativeHttp] Token refresh request failed with status', response.status, '(transient, keeping credentials)')
          return { _transientFailure: true, status: response.status }
        }

        const userResponseData = response.data
        if (!userResponseData.user?.accessToken) {
          console.error('[nativeHttp] No access token in refresh response')
          return null
        }

        console.log('[nativeHttp] Successfully refreshed access token')
        return {
          accessToken: userResponseData.user.accessToken,
          // Refresh token gets returned when refresh token is sent in x-refresh-token header
          refreshToken: userResponseData.user.refreshToken
        }
      } catch (error) {
        console.error('[nativeHttp] Failed to refresh access token:', error)
        // A thrown exception here is a transport-level failure (network down, DNS, timeout, etc.),
        // not a genuine token rejection — treat it as transient and keep credentials.
        return { _transientFailure: true, status: 0, error: error?.message }
      }
    },

    /**
     * Updates the store and secure storage with new tokens
     * @param {Object} tokens - Object containing accessToken and refreshToken
     * @param {{ id: string, address: string, version: string }} serverConnectionConfig
     * @returns {Promise} - Promise that resolves when tokens are updated
     */
    async updateTokens(tokens, serverConnectionConfig) {
      try {
        if (!serverConnectionConfig?.id) {
          throw new Error('No server connection config ID available')
        }

        // Update the config with new tokens
        const updatedConfig = {
          ...serverConnectionConfig,
          token: tokens.accessToken,
          refreshToken: tokens.refreshToken
        }

        // Save updated config to secure storage, persists refresh token in secure storage
        const savedConfig = await $db.setServerConnectionConfig(updatedConfig)

        // Update the store
        store.commit('user/setAccessToken', tokens.accessToken)

        // Re-authenticate socket if necessary
        if ($socket?.connected && !$socket.isAuthenticated) {
          $socket.sendAuthenticate()
        } else if (!$socket) {
          console.warn('[nativeHttp] Socket not available, cannot re-authenticate')
        }

        if (savedConfig) {
          store.commit('user/setServerConnectionConfig', savedConfig)
        }

        console.log('[nativeHttp] Successfully updated tokens in store and secure storage')
      } catch (error) {
        console.error('[nativeHttp] Failed to update tokens:', error)
        throw error
      }
    },

    /**
     * Handles the case when token refresh fails
     * @param {string} [serverConnectionConfigId]
     * @returns {Promise} - Promise that resolves when logout is complete
     */
    async handleRefreshFailure(serverConnectionConfigId) {
      try {
        console.log('[nativeHttp] Handling refresh failure - logging out user')

        // Clear store
        await store.dispatch('user/logout')

        if (serverConnectionConfigId) {
          // Clear refresh token for server connection config
          await $db.clearRefreshToken(serverConnectionConfigId)
        }

        // Redirect to login page
        if (window.location.pathname !== '/connect') {
          window.location.href = '/connect?error=refreshTokenFailed&serverConnectionConfigId=' + serverConnectionConfigId
        }
      } catch (error) {
        console.error('[nativeHttp] Failed to handle refresh failure:', error)
      }
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
