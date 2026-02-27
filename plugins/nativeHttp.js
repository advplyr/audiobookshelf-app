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
      // Add custom headers from server connection config (e.g., CloudFlare Access)
      if (serverConnectionConfig?.customHeaders) {
        headers = { ...headers, ...serverConnectionConfig.customHeaders }
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
          throw new Error(res.data)
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
        const newTokens = await this.refreshAccessToken(refreshToken, serverConnectionConfig.address)
        if (!newTokens?.accessToken) {
          console.error('[nativeHttp] Failed to refresh access token')
          throw new Error('Failed to refresh access token')
        }

        // Update the store with new tokens
        await this.updateTokens(newTokens, serverConnectionConfig)

        // Retry the original request with the new token
        console.log('[nativeHttp] Retrying original request with new token...')
        const retryResponse = await CapacitorHttp.request({
          method,
          url,
          data,
          headers: {
            ...headers,
            Authorization: `Bearer ${newTokens.accessToken}`
          },
          ...options
        })

        if (retryResponse.status >= 400) {
          console.error(`[nativeHttp] Retry request failed with status ${retryResponse.status}`)
          throw new Error(retryResponse.data)
        }

        return retryResponse.data
      } catch (error) {
        console.error('[nativeHttp] Token refresh failed:', error)

        // If refresh fails, redirect to login
        await this.handleRefreshFailure(serverConnectionConfig?.id)
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

        if (response.status !== 200) {
          console.error('[nativeHttp] Token refresh request failed:', response.status)
          return null
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
        return null
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
