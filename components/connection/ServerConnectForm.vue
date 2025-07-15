<template>
  <div class="w-full max-w-md mx-auto px-2 sm:px-4 lg:px-8 z-10">
    <div v-show="!loggedIn" class="mt-8 bg-primary overflow-hidden shadow rounded-lg px-4 py-6 w-full">
      <!-- list of server connection configs -->
      <template v-if="!showForm">
        <div v-for="config in serverConnectionConfigs" :key="config.id" class="border-b border-fg/10 py-4">
          <div class="flex items-center my-1 relative" @click="connectToServer(config)">
            <span class="material-symbols text-xl text-fg-muted">dns</span>
            <p class="pl-3 pr-6 text-base text-fg">{{ config.name }}</p>

            <div class="absolute top-0 right-0 h-full px-4 flex items-center" @click.stop="editServerConfig(config)">
              <span class="material-symbols text-2xl text-fg-muted">more_vert</span>
            </div>
          </div>
          <!-- warning message if server connection config is using an old user id -->
          <div v-if="!checkIdUuid(config.userId)" class="flex flex-nowrap justify-between items-center space-x-4 pt-4">
            <p class="text-xs text-warning">{{ $strings.MessageOldServerConnectionWarning }}</p>
            <ui-btn class="text-xs whitespace-nowrap" :padding-x="2" :padding-y="1" @click="showOldUserIdWarningDialog">{{ $strings.LabelMoreInfo }}</ui-btn>
          </div>
          <!-- warning message if server connection config is using an old auth method -->
          <div v-if="config.version && checkIsUsingOldAuth(config)" class="flex flex-nowrap justify-between items-center space-x-4 pt-4">
            <p class="text-xs text-warning">{{ $strings.MessageOldServerAuthWarning }}</p>
            <ui-btn class="text-xs whitespace-nowrap" :padding-x="2" :padding-y="1" @click="showOldAuthWarningDialog">{{ $strings.LabelMoreInfo }}</ui-btn>
          </div>
          <div v-else-if="!config.version" class="flex flex-nowrap justify-between items-center space-x-4 pt-4">
            <p class="text-xs text-warning">No server version set. Connect to update server config.</p>
          </div>
        </div>
        <div class="my-1 py-4 w-full">
          <ui-btn class="w-full" @click="newServerConfigClick">{{ $strings.ButtonAddNewServer }}</ui-btn>
        </div>
      </template>
      <!-- form to add a new server connection config -->
      <div v-else class="w-full">
        <!-- server address input -->
        <form v-if="!showAuth" @submit.prevent="submit" novalidate class="w-full">
          <div v-if="serverConnectionConfigs.length" class="flex items-center mb-4" @click="showServerList">
            <span class="material-symbols text-fg-muted">arrow_back</span>
          </div>
          <h2 class="text-lg leading-7 mb-2">{{ $strings.LabelServerAddress }}</h2>
          <ui-text-input v-model="serverConfig.address" :disabled="processing || !networkConnected || !!serverConfig.id" placeholder="http://55.55.55.55:13378" type="url" class="w-full h-10" />
          <div class="flex justify-end items-center mt-6">
            <ui-btn :disabled="processing || !networkConnected" type="submit" :padding-x="3" class="h-10">{{ networkConnected ? $strings.ButtonSubmit : $strings.MessageNoNetworkConnection }}</ui-btn>
          </div>
        </form>
        <!-- username/password and auth methods -->
        <template v-else>
          <div v-if="serverConfig.id" class="flex items-center mb-4" @click="showServerList">
            <span class="material-symbols text-fg-muted">arrow_back</span>
          </div>

          <div class="flex items-center">
            <p class="text-fg-muted">{{ serverConfig.address }}</p>
            <div class="flex-grow" />
            <span v-if="!serverConfig.id" class="material-symbols" style="font-size: 1.1rem" @click="editServerAddress">edit</span>
          </div>
          <div class="w-full h-px bg-fg/10 my-2" />
          <form v-if="isLocalAuthEnabled" @submit.prevent="submitAuth" class="pt-3">
            <ui-text-input v-model="serverConfig.username" :disabled="processing" :placeholder="$strings.LabelUsername" class="w-full mb-2 text-lg" />
            <ui-text-input v-model="password" type="password" :disabled="processing" :placeholder="$strings.LabelPassword" class="w-full mb-2 text-lg" />

            <div class="flex items-center pt-2">
              <ui-icon-btn v-if="serverConfig.id" small bg-color="error" icon="delete" type="button" @click="removeServerConfigClick" />
              <div class="flex-grow" />
              <ui-btn :disabled="processing || !networkConnected" type="submit" class="mt-1 h-10">{{ networkConnected ? $strings.ButtonSubmit : $strings.MessageNoNetworkConnection }}</ui-btn>
            </div>
          </form>
          <div v-if="isLocalAuthEnabled && isOpenIDAuthEnabled" class="w-full h-px bg-fg/10 my-4" />
          <ui-btn v-if="isOpenIDAuthEnabled" :disabled="processing" class="h-10 w-full" @click="clickLoginWithOpenId">{{ oauth.buttonText }}</ui-btn>
        </template>
      </div>

      <!-- auth error message -->
      <div v-show="error" class="w-full rounded-lg bg-red-600 bg-opacity-10 border border-error border-opacity-50 py-3 px-2 flex items-center mt-4">
        <span class="material-symbols mr-2 text-error" style="font-size: 1.1rem">warning</span>
        <p class="text-error">{{ error }}</p>
      </div>
    </div>

    <div :class="processing ? 'opacity-100' : 'opacity-0 pointer-events-none'" class="fixed w-full h-full top-0 left-0 bg-black/75 flex items-center justify-center z-30 transition-opacity duration-500">
      <div>
        <div class="absolute top-0 left-0 w-full p-6 flex items-center flex-col justify-center z-0 short:hidden">
          <img src="/Logo.png" class="h-20 w-20 mb-2" />
        </div>
        <svg class="animate-spin w-16 h-16" viewBox="0 0 24 24">
          <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
        </svg>
      </div>
    </div>

    <p v-if="!serverConnectionConfigs.length" class="mt-2 text-center text-error" v-html="$strings.MessageAudiobookshelfServerRequired" />

    <modals-custom-headers-modal v-model="showAddCustomHeaders" :custom-headers.sync="serverConfig.customHeaders" />
  </div>
</template>

<script>
import { Browser } from '@capacitor/browser'
import { CapacitorHttp } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'

// TODO: when backend ready. See validateLoginFormResponse()
//const requiredServerVersion = '2.5.0'

export default {
  data() {
    return {
      loggedIn: false,
      showAuth: false,
      processing: false,
      serverConfig: {
        address: null,
        version: null,
        username: null,
        customHeaders: null
      },
      password: null,
      error: null,
      showForm: false,
      showAddCustomHeaders: false,
      authMethods: [],
      oauth: {
        state: null,
        verifier: null,
        challenge: null,
        buttonText: 'Login with OpenID',
        enforceHTTPs: true // RFC 6749, Section 10.9 requires https
      }
    }
  },
  computed: {
    deviceData() {
      return this.$store.state.deviceData || {}
    },
    deviceSettings() {
      return this.deviceData.deviceSettings || {}
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    serverConnectionConfigs() {
      return this.deviceData?.serverConnectionConfigs || []
    },
    lastServerConnectionConfigId() {
      return this.deviceData?.lastServerConnectionConfigId || null
    },
    lastServerConnectionConfig() {
      if (!this.lastServerConnectionConfigId || !this.serverConnectionConfigs.length) return null
      return this.serverConnectionConfigs.find((s) => s.id == this.lastServerConnectionConfigId)
    },
    isLocalAuthEnabled() {
      return this.authMethods.includes('local') || !this.authMethods.length
    },
    isOpenIDAuthEnabled() {
      return this.authMethods.includes('openid')
    }
  },
  methods: {
    showOldUserIdWarningDialog() {
      Dialog.alert({
        title: 'Old Server Connection Warning',
        message: this.$strings.MessageOldServerConnectionWarningHelp,
        cancelText: this.$strings.ButtonOk
      })
    },
    async showOldAuthWarningDialog() {
      const confirmResult = await Dialog.confirm({
        title: 'Old Server Auth Warning',
        message: this.$strings.MessageOldServerAuthWarningHelp,
        cancelButtonTitle: this.$strings.ButtonReadMore
      })
      if (!confirmResult.value) {
        window.open('https://github.com/advplyr/audiobookshelf/discussions/4460', '_blank')
      }
    },
    checkIdUuid(userId) {
      return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(userId)
    },
    checkIsUsingOldAuth(config) {
      if (!config.version) return true
      return !this.$isValidVersion(config.version, '2.26.0')
    },
    /**
     * Initiates the login process using OpenID via OAuth2.0.
     * 1. Verifying the server's address
     * 2. Calling oauthRequest() to obtain the special OpenID redirect URL
     *      including a challenge and specying audiobookshelf://oauth as redirect URL
     * 3. Open this redirect URL in browser (which is a website of the SSO provider)
     *
     * When the browser is open, the following flow is expected:
     * a. The user authenticates and the provider redirects back to custom URL audiobookshelf://oauth
     * b. The app calls appUrlOpen() when `audiobookshelf://oauth` is called
     * b. appUrlOpen() handles the incoming URL and extracts the authorization code from GET parameter
     * c. oauthExchangeCodeForToken() exchanges the authorization code for an access token
     *
     *
     * @async
     * @throws Will log a console error if the browser fails to open the URL and display errors via this.error to the user.
     */
    async clickLoginWithOpenId() {
      // oauth standard requires https explicitly
      if (!this.serverConfig.address.startsWith('https') && this.oauth.enforceHTTPs) {
        console.warn(`[SSO] Oauth2 requires HTTPS`)
        this.$toast.error(`SSO: The URL to the server must be https:// secured`)
        return
      }

      // First request that we want to do oauth/openid and get the URL which a browser window should open
      const redirectUrl = await this.oauthRequest(this.serverConfig.address)
      if (!redirectUrl) {
        // error message handled by oauthRequest
        return
      }

      // Actually we should be able to use the redirectUrl directly for Browser.open below
      // However it seems that when directly using it there is a malformation and leads to the error
      //    Unhandled Promise Rejection: DataCloneError: The object can not be cloned.
      //    (On calling Browser.open)
      // Which is hard to debug
      // So we simply extract the important elements and build the required URL ourselves
      //  which also has the advantage that we can replace the callbackurl with the app url

      const client_id = redirectUrl.searchParams.get('client_id')
      const scope = redirectUrl.searchParams.get('scope')
      const state = redirectUrl.searchParams.get('state')
      let redirect_uri_param = redirectUrl.searchParams.get('redirect_uri')
      // Backwards compatability with 2.6.0
      if (this.serverConfig.version === '2.6.0') {
        redirect_uri_param = 'audiobookshelf://oauth'
      }

      if (!client_id || !scope || !state || !redirect_uri_param) {
        console.warn(`[SSO] Invalid OpenID URL - client_id scope state or redirect_uri missing: ${redirectUrl}`)
        this.$toast.error(`SSO: Invalid answer`)
        return
      }

      if (redirectUrl.protocol !== 'https:' && this.oauth.enforceHTTPs) {
        console.warn(`[SSO] Insecure Redirection by SSO provider: ${redirectUrl.protocol} is not allowed. Use HTTPS`)
        this.$toast.error(`SSO: The SSO provider must return a HTTPS secured URL`)
        return
      }

      // We need to verify if the state is the same later
      this.oauth.state = state

      const host = `${redirectUrl.protocol}//${redirectUrl.host}`
      const buildUrl = `${host}${redirectUrl.pathname}?response_type=code` + `&client_id=${encodeURIComponent(client_id)}&scope=${encodeURIComponent(scope)}&state=${encodeURIComponent(state)}` + `&redirect_uri=${encodeURIComponent(redirect_uri_param)}` + `&code_challenge=${encodeURIComponent(this.oauth.challenge)}&code_challenge_method=S256`

      // example url for authentik
      // const authURL = "https://authentik/application/o/authorize/?response_type=code&client_id=41cd96f...&redirect_uri=audiobookshelf%3A%2F%2Foauth&scope=openid%20openid%20email%20profile&state=asdds..."

      // Open the browser. The browser/identity provider in turn will redirect to an in-app link supplementing a code
      try {
        await Browser.open({ url: buildUrl })
      } catch (error) {
        console.error('Error opening browser', error)
      }
    },
    /**
     * Requests the OAuth/OpenID URL from the backend server to open in browser
     *
     * @async
     * @param {string} url - The base URL of the server to append the OAuth request parameters to.
     * @return {Promise<URL|null>} OAuth URL which should be opened in a browser
     * @throws Logs an error and displays a toast notification if the token exchange fails.
     */
    async oauthRequest(url) {
      // Generate oauth2 PKCE challenge
      //  In accordance to RFC 7636 Section 4
      function base64URLEncode(arrayBuffer) {
        let base64String = btoa(String.fromCharCode.apply(null, new Uint8Array(arrayBuffer)))
        return base64String.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
      }

      async function sha256(plain) {
        const encoder = new TextEncoder()
        const data = encoder.encode(plain)
        return await window.crypto.subtle.digest('SHA-256', data)
      }

      function generateRandomString() {
        var array = new Uint32Array(42)
        window.crypto.getRandomValues(array)
        return Array.from(array, (dec) => ('0' + dec.toString(16)).slice(-2)).join('') // hex
      }

      const verifier = generateRandomString()

      const challenge = base64URLEncode(await sha256(verifier))

      this.oauth.verifier = verifier
      this.oauth.challenge = challenge

      let backendEndpoint = `${url}/auth/openid?code_challenge=${challenge}&code_challenge_method=S256&redirect_uri=${encodeURIComponent('audiobookshelf://oauth')}&client_id=${encodeURIComponent('Audiobookshelf-App')}&response_type=code`
      // Backwards compatability with 2.6.0
      if (this.serverConfig.version === '2.6.0') {
        backendEndpoint += '&isRest=true'
      }

      try {
        const response = await CapacitorHttp.get({
          url: backendEndpoint,
          disableRedirects: true,
          webFetchExtra: {
            redirect: 'manual'
          }
        })

        // Every kind of redirection is allowed [RFC6749 - 1.7]
        if (!(response.status >= 300 && response.status < 400)) {
          throw new Error(`Unexpected response from server: ${response.status}`)
        }

        // Depending on iOS or Android, it can be location or Location...
        const locationHeader = response.headers[Object.keys(response.headers).find((key) => key.toLowerCase() === 'location')]
        if (!locationHeader) {
          throw new Error(`No location header in SSO answer`)
        }

        const url = new URL(locationHeader)
        return url
      } catch (error) {
        console.error(`[SSO] ${error.message}`)
        this.$toast.error(`SSO Error: ${error.message}`)
      }
    },
    /**
     * Handles the callback received from the OAuth/OpenID provider.
     *
     * @async
     * @function appUrlOpen
     * @param {string} url - The callback URL received from the OAuth/OpenID provider.
     * @throws Logs a warning and displays a toast notification if the URL is invalid or the state doesn't match.
     */
    async appUrlOpen(url) {
      if (!url) return

      // Handle the OAuth callback
      const urlObj = new URL(url)

      // audiobookshelf://oauth?code...
      // urlObj.hostname for iOS and urlObj.pathname for android
      if (url.startsWith('audiobookshelf://oauth')) {
        // Extract possible errors thrown by the SSO provider
        const authError = urlObj.searchParams.get('error')
        if (authError) {
          console.warn(`[SSO] Received the following error: ${authError}`)
          this.$toast.error(`SSO: Received the following error: ${authError}`)
          return
        }

        // Extract oauth2 code to be exchanged for a token
        const authCode = urlObj.searchParams.get('code')
        // Extract the state variable
        const state = urlObj.searchParams.get('state')

        if (this.oauth.state !== state) {
          console.warn(`[SSO] Wrong state returned by SSO Provider`)
          this.$toast.error(`SSO: The response from the SSO Provider was invalid (wrong state)`)
          return
        }

        // Clear the state variable from the component config
        this.oauth.state = null

        if (authCode) {
          await this.oauthExchangeCodeForToken(authCode, state)
        } else {
          console.warn(`[SSO] No code received`)
          this.$toast.error(`SSO: The response from the SSO Provider did not include a code (authentication error?)`)
        }
      } else {
        console.warn(`[ServerConnectForm] appUrlOpen: Unknown url: ${url} - host: ${urlObj.hostname} - path: ${urlObj.pathname}`)
      }
    },
    /**
     * Exchanges an oauth2 authorization code for a JWT token.
     * And uses that token to finalise the log in process using authenticateToken()
     *
     * @async
     * @function oauthExchangeCodeForToken
     * @param {string} code - The authorization code provided by the OpenID provider.
     * @param {string} state - The state value used to associate a client session with an ID token.
     * @throws Logs an error and displays a toast notification if the token exchange fails.
     */
    async oauthExchangeCodeForToken(code, state) {
      // We need to read the url directly from this.serverConfig.address as the callback which is called via the external browser does not pass us that info
      const backendEndpoint = `${this.serverConfig.address}/auth/openid/callback?state=${encodeURIComponent(state)}&code=${encodeURIComponent(code)}&code_verifier=${encodeURIComponent(this.oauth.verifier)}`

      try {
        // We can close the browser at this point (does not work on Android)
        if (this.$platform === 'ios' || this.$platform === 'web') {
          await Browser.close()
        }
      } catch (error) {} // No Error handling needed

      try {
        // Returns the same user response payload as /login
        const response = await CapacitorHttp.get({
          url: backendEndpoint
        })
        // v2.26.0+ returns accessToken and refreshToken on user object
        if (!response.data?.user?.token && !response.data?.user?.accessToken) {
          throw new Error('Token is missing in response.')
        }

        const user = response.data.user
        this.serverConfig.token = user.accessToken || user.token

        // TODO: Is it necessary to authenticate again?
        const payload = await this.authenticateToken()

        if (!payload) {
          throw new Error('Authentication failed with the provided token.')
        }

        const duplicateConfig = this.serverConnectionConfigs.find((scc) => scc.address === this.serverConfig.address && scc.username === payload.user.username)
        if (duplicateConfig) {
          throw new Error('Config already exists for this address and username.')
        }

        // For v2.26.0+ re-attach accessToken and refreshToken to user object because /authorize does not return them
        if (user.accessToken) {
          payload.user.accessToken = user.accessToken
          payload.user.refreshToken = user.refreshToken
        }

        this.setUserAndConnection(payload)
      } catch (error) {
        console.error('[SSO] Error in exchangeCodeForToken: ', error)
        this.$toast.error(`SSO error: ${error.message || error}`)
      } finally {
        // We don't need the oauth verifier any more
        this.oauth.verifier = null
        this.oauth.challenge = null
      }
    },
    addCustomHeaders() {
      this.showAddCustomHeaders = true
    },
    showServerList() {
      this.showForm = false
      this.showAuth = false
      this.error = null
      this.serverConfig = {
        address: null,
        userId: null,
        username: null
      }
    },
    async connectToServer(config) {
      await this.$hapticsImpact()
      console.log('[ServerConnectForm] connectToServer', config.address)
      this.processing = true
      this.serverConfig = {
        ...config
      }
      this.showForm = true
      var success = await this.pingServerAddress(config.address)
      this.processing = false
      console.log(`[ServerConnectForm] pingServer result ${success}`)
      if (!success) {
        this.showForm = false
        this.showAuth = false
        console.log(`[ServerConnectForm] showForm ${this.showForm}`)
        return
      }

      this.error = null
      const payload = await this.authenticateToken()

      if (payload) {
        // Will NOT include access token and refresh token
        this.setUserAndConnection(payload)
      } else {
        this.showAuth = true
      }
    },
    async removeServerConfigClick() {
      if (!this.serverConfig.id) return
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Remove this server config?`
      })
      if (value) {
        this.processing = true
        await this.$db.removeServerConnectionConfig(this.serverConfig.id)
        const updatedDeviceData = { ...this.deviceData }
        updatedDeviceData.serverConnectionConfigs = this.deviceData.serverConnectionConfigs.filter((scc) => scc.id != this.serverConfig.id)
        this.$store.commit('setDeviceData', updatedDeviceData)

        this.serverConfig = {
          address: null,
          userId: null,
          username: null
        }
        this.password = null
        this.processing = false
        this.showAuth = false
        this.showForm = !this.serverConnectionConfigs.length
      }
    },
    editServerConfig(serverConfig) {
      this.serverConfig = {
        ...serverConfig
      }
      this.showForm = true
      this.showAuth = true
    },
    async newServerConfigClick() {
      await this.$hapticsImpact()
      this.serverConfig = {
        address: '',
        userId: '',
        username: ''
      }
      this.showForm = true
      this.showAuth = false
      this.error = null
    },
    editServerAddress() {
      this.error = null
      this.showAuth = false
    },
    /**
     * Validates a URL and reconstructs it with an optional protocol override.
     * If the URL is invalid, null is returned.
     *
     * @param {string} url - The URL to validate.
     * @param {string|null} [protocolOverride=null] - (Optional) Protocol to override the URL's original protocol.
     * @returns {string|null} The validated URL with the original or overridden protocol, or null if invalid.
     */
    validateServerUrl(url, protocolOverride = null) {
      try {
        var urlObject = new URL(url)
        if (protocolOverride) urlObject.protocol = protocolOverride
        return urlObject.href.replace(/\/$/, '') // Remove trailing slash
      } catch (error) {
        console.error('Invalid URL', error)
        return null
      }
    },
    /**
     * Sends a GET request to the specified URL with the provided headers and timeout.
     * If the response is successful (HTTP 200), the response object is returned.
     * Otherwise, throws an error object containing code.
     * code can be either a number, which is then a HTTP status code or
     *  a string, which is then a keyword like NSURLErrorBadURL when the TCP connection could not be established.
     * When code is a string, error.message contains the human readable error by the OS or
     *  the http body of the non-200 answer.
     *
     * @async
     * @param {string} url - The URL to which the GET request will be sent.
     * @param {Object} headers - HTTP headers to be included in the request.
     * @param {number} [connectTimeout=6000] - Timeout for the request in milliseconds.
     * @returns {Promise<HttpResponse>} The HTTP response object if the request is successful.
     * @throws {Error} An error with 'code' property set to the HTTP status code if the response is not successful.
     * @throws {Error} An error with 'code' property set to the error code if the request fails.
     */
    async getRequest(url, headers, connectTimeout = 6000) {
      const options = {
        url,
        headers,
        connectTimeout
      }
      try {
        const response = await CapacitorHttp.get(options)
        console.log('[ServerConnectForm] GET request response', response)
        if (response.status == 200) {
          return response
        } else {
          // Put the HTTP error code inside the cause
          let errorObj = new Error(response.data)
          errorObj.code = response.status
          throw errorObj
        }
      } catch (error) {
        // Put the error name inside the cause (a string)
        let errorObj = new Error(error.message)
        errorObj.code = error.code
        throw errorObj
      }
    },
    async postRequest(url, data, headers, connectTimeout = 6000) {
      if (!headers) headers = {}
      if (!headers['Content-Type'] && data) {
        headers['Content-Type'] = 'application/json'
      }
      const options = {
        url,
        headers,
        data,
        connectTimeout
      }
      const response = await CapacitorHttp.post(options)
      console.log('[ServerConnectForm] POST request response', response)
      if (response.status >= 400) {
        throw new Error(response.data)
      } else {
        return response.data
      }
    },
    /**
     * Get request to server /status api endpoint
     *
     * @param {string} address
     * @returns {Promise<HttpResponse>}
     *    HttpResponse.data is {isInit:boolean, language:string, authMethods:string[]}>
     */
    async getServerAddressStatus(address) {
      return this.getRequest(`${address}/status`)
    },
    pingServerAddress(address, customHeaders) {
      return this.getRequest(`${address}/ping`, customHeaders)
        .then((response) => {
          return response.data.success
        })
        .catch((error) => {
          console.error('Server ping failed', error)
          const errorMsg = error.message || error
          this.error = 'Failed to ping server'
          if (typeof errorMsg === 'string') {
            this.error += ` (${errorMsg})`
          }

          return false
        })
    },
    requestServerLogin() {
      const headers = {
        // Tells the Abs server to return the refresh token
        'x-return-tokens': 'true',
        ...(this.serverConfig.customHeaders || {})
      }
      return this.postRequest(`${this.serverConfig.address}/login`, { username: this.serverConfig.username, password: this.password || '' }, headers, 20000)
        .then((data) => {
          if (!data.user) {
            console.error(data.error)
            this.error = data.error || 'Unknown Error'
            return false
          }
          return data
        })
        .catch((error) => {
          console.error('Server auth failed', error)
          const errorMsg = error.message || error
          this.error = 'Failed to login'
          if (typeof errorMsg === 'string') {
            this.error += ` (${errorMsg})`
          }
          return false
        })
    },
    async submit() {
      if (!this.networkConnected) return
      if (!this.serverConfig.address) return

      const initialAddress = this.serverConfig.address
      // Did the user specify a protocol?
      const protocolProvided = initialAddress.startsWith('http://') || initialAddress.startsWith('https://')
      // Add https:// if not provided
      this.serverConfig.address = this.prependProtocolIfNeeded(initialAddress)

      this.processing = true
      this.error = null
      this.authMethods = []

      try {
        // Try the server URL. If it fails and the protocol was not provided, try with http instead of https
        const statusData = await this.tryServerUrl(this.serverConfig.address, !protocolProvided)
        if (this.validateLoginFormResponse(statusData, this.serverConfig.address, protocolProvided)) {
          this.showAuth = true
          this.authMethods = statusData.data.authMethods || []
          this.oauth.buttonText = statusData.data.authFormData?.authOpenIDButtonText || 'Login with OpenID'
          this.serverConfig.version = statusData.data.serverVersion

          if (statusData.data.authFormData?.authOpenIDAutoLaunch) {
            this.clickLoginWithOpenId()
          }
        }
      } catch (error) {
        this.handleLoginFormError(error)
      } finally {
        this.processing = false
      }
    },
    /** Validates the login form response from the server.
     *
     * Ensure the request has not been redirected to an unexpected hostname and check if it is Audiobookshelf
     *
     * @param {object} statusData - The data received from the server's response, including data and url.
     * @param {string} initialAddressWithProtocol - The initial server address including the protocol used for the request.
     * @param {boolean} protocolProvided - Indicates whether the protocol was explicitly provided in the initial address.
     *
     * @returns {boolean} - Returns `true` if the response is valid, otherwise `false` and sets this.error.
     */
    validateLoginFormResponse(statusData, initialAddressWithProtocol, protocolProvided) {
      // We have a 200 status code at this point

      // Check if we got redirected to a different hostname, we don't allow this
      const initialAddressUrl = new URL(initialAddressWithProtocol)
      const currentAddressUrl = new URL(statusData.url)
      if (initialAddressUrl.hostname !== currentAddressUrl.hostname) {
        this.error = `Server redirected somewhere else (to ${currentAddressUrl.hostname})`
        console.error(`[ServerConnectForm] Server redirected somewhere else (to ${currentAddressUrl.hostname})`)
        return false
      } // We don't allow a redirection back from https to http if the user used https:// explicitly
      else if (protocolProvided && initialAddressWithProtocol.startsWith('https://') && currentAddressUrl.protocol === 'http') {
        this.error = `You specified https:// but the Server redirected back to plain http`
        console.error(`[ServerConnectForm] User specified https:// but server redirected to http`)
        return false
      }

      // Check content of response now
      if (!statusData || !statusData.data || Object.keys(statusData).length === 0) {
        this.error = 'Response from server was empty' // Usually some kind of config error on server side
        console.error('[ServerConnectForm] Received empty response')
        return false
      } else if (!('isInit' in statusData.data) || !('language' in statusData.data)) {
        this.error = 'This does not seem to be a Audiobookshelf server'
        console.error('[ServerConnectForm] Received as response from Server:\n', statusData)
        return false
        //    TODO: delete the if above and comment the ones below out, as soon as the backend is ready to introduce a version check
        //    } else if (!('app' in statusData.data) || statusData.data.app.toLowerCase() !== 'audiobookshelf') {
        //      this.error = 'This does not seem to be a Audiobookshelf server'
        //      console.error('[ServerConnectForm] Received as response from Server:\n', statusData)
        //      return false
        //    } else if (!this.isValidVersion(statusData.data.serverVersion, requiredServerVersion)) {
        //      this.error = `Server version is below minimum required version of ${requiredServerVersion} (${statusData.data.serverVersion})`
        //      console.error('[ServerConnectForm] Server version is too low: ', statusData.data.serverVersion)
        //      return false
      } else if (!statusData.data.isInit) {
        this.error = 'Server is not initialized'
        return false
      }

      // If we got redirected from http to https, we allow this
      // Also there is the possibility that https was tried (with protocolProvided false) but only http was successfull
      // So set the correct protocol for the config
      const configUrl = new URL(this.serverConfig.address)
      configUrl.protocol = currentAddressUrl.protocol
      // Remove trailing slash
      this.serverConfig.address = configUrl.toString().replace(/\/$/, '')

      return true
    },
    /**
     * Handles errors received during the login form process, providing user-friendly error messages.
     *
     * @param {Object} error - The error object received from a failed login attempt.
     */
    handleLoginFormError(error) {
      console.error('[ServerConnectForm] Received invalid status', error)

      if (error.code === 404) {
        this.error = `This does not seem to be an Audiobookshelf server. (Error: 404 querying /status)`
      } else if (typeof error.code === 'number') {
        // Error with HTTP Code
        this.error = `Failed to retrieve status of server: ${error.code}`
      } else {
        // error is usually a meaningful error like "Server timed out"
        this.error = `Failed to contact server. (${error})`
      }
    },
    /**
     * Attempts to retrieve the server address status for the given URL.
     * If the initial attempt fails, it retries with HTTP if allowed.
     *
     * @param {string} address - The URL address to validate and check.
     * @param {boolean} shouldRetryWithHttp - Flag to indicate if the function should retry with HTTP on failure.
     * @returns {Promise<HttpResponse>}
     *    HttpResponse.data is {isInit:boolean, language:string, authMethods:string[]}>
     * @throws Will throw an error if the URL has a wrong format or if both HTTPS and HTTP (if retried) requests fail.
     */
    async tryServerUrl(address, shouldRetryWithHttp) {
      const validatedUrl = this.validateServerUrl(address)
      if (!validatedUrl) {
        throw new Error('URL has wrong format')
      }

      try {
        return await this.getServerAddressStatus(validatedUrl)
      } catch (error) {
        // We only retry when the user did not specify a protocol
        // Also for security reasons, we only retry when the https request did not
        //      return a http status code (so only retry when the TCP connection could not be established)
        if (shouldRetryWithHttp && typeof error.code !== 'number') {
          console.log('[ServerConnectForm] https failed, trying to connect with http...')
          const validatedHttpUrl = this.validateServerUrl(address, 'http:')
          if (validatedHttpUrl) {
            return await this.getServerAddressStatus(validatedHttpUrl)
          }
          // else if validatedHttpUrl is false return the original error below
        }
        // rethrow original error
        throw error
      }
    },
    /**
     * Ensures that a protocol is prepended to the given address if it does not already start with http:// or https://.
     *
     * @param {string} address - The server address that may or may not have a protocol.
     * @returns {string} The address with a protocol prepended if it was missing.
     */
    prependProtocolIfNeeded(address) {
      return address.startsWith('http://') || address.startsWith('https://') ? address : `https://${address}`
    },
    async submitAuth() {
      if (!this.networkConnected) return
      if (!this.serverConfig.username) {
        this.error = 'Invalid username'
        return
      }

      const duplicateConfig = this.serverConnectionConfigs.find((scc) => scc.address === this.serverConfig.address && scc.username === this.serverConfig.username && this.serverConfig.id !== scc.id)
      if (duplicateConfig) {
        this.error = 'Config already exists for this address and username'
        return
      }

      this.error = null
      this.processing = true

      const payload = await this.requestServerLogin()
      this.processing = false
      if (payload) {
        // Will include access token and refresh token
        this.setUserAndConnection(payload)
      }
    },
    async setUserAndConnection({ user, userDefaultLibraryId, serverSettings, ereaderDevices }) {
      if (!user) return

      console.log('Successfully logged in', JSON.stringify(user))

      this.$store.commit('setServerSettings', serverSettings)
      this.$store.commit('libraries/setEReaderDevices', ereaderDevices)
      this.$setServerLanguageCode(serverSettings.language)

      this.serverConfig.userId = user.id
      this.serverConfig.username = user.username

      if (this.$isValidVersion(serverSettings.version, '2.26.0')) {
        // Tokens only returned from /login endpoint
        if (user.accessToken) {
          this.serverConfig.token = user.accessToken
          this.serverConfig.refreshToken = user.refreshToken
        } else {
          // Detect if the connection config is using the old token. If so, force re-login
          if (this.serverConfig.token === user.token || user.isOldToken) {
            this.setForceReloginForNewAuth()
            return
          }

          // If the token was updated during a refresh (in nativeHttp.js) it gets updated in the store, so refetch
          this.serverConfig.token = this.$store.getters['user/getToken'] || this.serverConfig.token
        }
      } else {
        // Server version before new JWT auth, use old user.token
        this.serverConfig.token = user.token
      }

      this.serverConfig.version = serverSettings.version

      var serverConnectionConfig = await this.$db.setServerConnectionConfig(this.serverConfig)

      // Set the device language to match the servers if this is the first server connection
      if (!this.serverConnectionConfigs.length && serverSettings.language !== 'en-us') {
        const deviceSettings = {
          ...this.deviceSettings,
          languageCode: serverSettings.language
        }
        const updatedDeviceData = await this.$db.updateDeviceSettings(deviceSettings)
        if (updatedDeviceData) {
          this.$store.commit('setDeviceData', updatedDeviceData)
          this.$setLanguageCode(updatedDeviceData.deviceSettings?.languageCode || 'en-us')
        }
      }

      // Set library - Use last library if set and available fallback to default user library
      const lastLibraryId = await this.$localStore.getLastLibraryId()
      if (lastLibraryId && (!user.librariesAccessible.length || user.librariesAccessible.includes(lastLibraryId))) {
        this.$store.commit('libraries/setCurrentLibrary', lastLibraryId)
      } else if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }

      this.$store.commit('user/setUser', user)
      this.$store.commit('user/setAccessToken', serverConnectionConfig.token)
      this.$store.commit('user/setServerConnectionConfig', serverConnectionConfig)

      this.$socket.connect(this.serverConfig.address, this.serverConfig.token)
      this.$router.replace('/bookshelf')
    },
    async authenticateToken() {
      if (!this.networkConnected) return
      if (!this.serverConfig.token) {
        this.error = 'No token'
        return
      }

      this.error = null
      this.processing = true

      const nativeHttpOptions = {
        headers: {
          Authorization: `Bearer ${this.serverConfig.token}`
        },
        serverConnectionConfig: this.serverConfig
      }
      const authRes = await this.$nativeHttp.post(`${this.serverConfig.address}/api/authorize`, null, nativeHttpOptions).catch((error) => {
        console.error('[ServerConnectForm] Server auth failed', error)
        const errorMsg = error.message || error
        this.error = 'Failed to authorize'
        if (typeof errorMsg === 'string') {
          this.error += ` (${errorMsg})`
        }
        return false
      })
      console.log('[ServerConnectForm] authRes=', authRes)

      this.processing = false
      return authRes
    },
    setForceReloginForNewAuth() {
      this.error = this.$strings.MessageOldServerAuthReLoginRequired
      this.showAuth = true
      this.showForm = true
    },
    init() {
      // Handle force re-login for servers using new JWT auth but still using an old token in the server config
      if (this.$route.query.error === 'oldAuthToken' && this.$route.query.serverConnectionConfigId) {
        this.serverConfig = this.serverConnectionConfigs.find((scc) => scc.id === this.$route.query.serverConnectionConfigId)
        if (this.serverConfig) {
          this.setForceReloginForNewAuth()
          return
        }
      }

      if (this.lastServerConnectionConfig) {
        console.log('[ServerConnectForm] init with lastServerConnectionConfig', this.lastServerConnectionConfig)
        this.connectToServer(this.lastServerConnectionConfig)
      } else {
        this.showForm = !this.serverConnectionConfigs.length
      }
    }
  },
  mounted() {
    this.$eventBus.$on('url-open', this.appUrlOpen)
    this.init()
  },
  beforeDestroy() {
    this.$eventBus.$off('url-open', this.appUrlOpen)
  }
}
</script>
