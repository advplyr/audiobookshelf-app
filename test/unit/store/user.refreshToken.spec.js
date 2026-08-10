import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the Capacitor native bridges so the module loads without a device.
// CapacitorHttp.post is re-implemented per-test below by reassigning mockReturnValue/mockImplementation.
vi.mock('@capacitor/core', () => ({
  CapacitorHttp: {
    post: vi.fn()
  }
}))
vi.mock('@capacitor/browser', () => ({
  Browser: { open: vi.fn() }
}))
vi.mock('@/plugins/capacitor', () => ({
  AbsLogger: { info: vi.fn(), error: vi.fn() }
}))

const { CapacitorHttp } = await import('@capacitor/core')
// store/user.js is a Nuxt Vuex module with named exports (no default export).
const { actions } = await import('@/store/user.js')

// ---------------------------------------------------------------------------
// The refreshToken action is a Vuex action. Its shape is:
//   async refreshToken({ getters, commit, state }) { ... uses this.$db, this.$socket }
// We invoke it through a `context` object that gives it a `this` binding with
// $db / $socket, mirroring how Nuxt injects them.
// ---------------------------------------------------------------------------
function buildContext(overrides = {}) {
  const state = {
    serverConnectionConfig: { id: 'srv-1', address: 'https://abs.example', name: 'Main', token: 'old-access' },
    accessToken: 'old-access',
    ...overrides.state
  }
  const getRefreshToken = vi.fn(async (id) => 'stored-refresh-token')
  const setServerConnectionConfig = vi.fn(async (cfg) => cfg)
  const sendAuthenticate = vi.fn()
  // `this` binding for the action (Nuxt injects $db / $socket onto it)
  const ctx = {
    $db: { getRefreshToken, setServerConnectionConfig },
    $socket: { connected: true, isAuthenticated: false, sendAuthenticate },
    ...overrides.$this
  }
  const context = {
    state,
    // getters are accessed as functions on the getters object
    getters: {
      getServerConnectionConfigId: state.serverConnectionConfig?.id ?? null,
      getServerAddress: state.serverConnectionConfig?.address ?? null,
      ...overrides.getters
    },
    commit: vi.fn(),
    dispatch: vi.fn(),
    ...overrides.context
  }
  return { context, ctx, getRefreshToken, setServerConnectionConfig, sendAuthenticate }
}

// refreshToken is declared as a normal (non-arrow) method on actions, so `this`
// is whatever the caller binds it to. We bind to `ctx` ($db/$socket carrier).
async function callRefreshToken(overrides = {}) {
  const { context, ctx, ...spies } = buildContext(overrides)
  const fn = actions.refreshToken.bind(ctx)
  const result = await fn(context)
  return { result, context, ctx, ...spies }
}

describe('store/user.js — refreshToken action', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('permanent failure (returns null → axios.js logs out)', () => {
    it('returns null when there is no stored refresh token', async () => {
      const { result, ctx } = await callRefreshToken({ $this: { $db: { getRefreshToken: vi.fn(async () => null) }, $socket: {} } })
      expect(result).toBeNull()
      expect(ctx.$db.getRefreshToken).toHaveBeenCalledTimes(1)
      // Should not even attempt a network call
      expect(CapacitorHttp.post).not.toHaveBeenCalled()
    })

    it('returns null on HTTP 401 (genuine token rejection)', async () => {
      CapacitorHttp.post.mockResolvedValue({ status: 401, data: {} })
      const { result } = await callRefreshToken()
      expect(result).toBeNull()
      // Request must be made to /auth/refresh with the refresh token header
      expect(CapacitorHttp.post).toHaveBeenCalledWith(
        expect.objectContaining({
          url: 'https://abs.example/auth/refresh',
          headers: expect.objectContaining({ 'x-refresh-token': 'stored-refresh-token' })
        })
      )
    })

    it('returns null when 200 response is missing the accessToken (malformed)', async () => {
      CapacitorHttp.post.mockResolvedValue({ status: 200, data: { user: {} } })
      const { result } = await callRefreshToken()
      expect(result).toBeNull()
    })
  })

  describe('transient failure (returns {_transientFailure} → axios.js keeps credentials)', () => {
    it.each([403, 404, 500, 502, 503])(
      'returns transient failure for HTTP %i and does NOT clear the session',
      async (status) => {
        CapacitorHttp.post.mockResolvedValue({ status, data: {} })
        const { result, context } = await callRefreshToken()
        expect(result).toEqual({ _transientFailure: true, status })
        // No mutations should be committed (credentials are preserved)
        expect(context.commit).not.toHaveBeenCalled()
      }
    )
  })

  describe('success', () => {
    it('commits new access token + server config and returns the new access token', async () => {
      CapacitorHttp.post.mockResolvedValue({
        status: 200,
        data: { user: { accessToken: 'new-access', refreshToken: 'new-refresh' } }
      })
      const { result, context, setServerConnectionConfig, sendAuthenticate } = await callRefreshToken()

      expect(result).toBe('new-access')
      // Persists updated config to secure storage
      expect(setServerConnectionConfig).toHaveBeenCalledTimes(1)
      const persisted = setServerConnectionConfig.mock.calls[0][0]
      expect(persisted.token).toBe('new-access')
      expect(persisted.refreshToken).toBe('new-refresh')
      // Commits to the Vuex store
      expect(context.commit).toHaveBeenCalledWith('setAccessToken', 'new-access')
      expect(context.commit).toHaveBeenCalledWith('setServerConnectionConfig', persisted)
      // Socket is connected but not authenticated -> re-authenticates
      expect(sendAuthenticate).toHaveBeenCalledTimes(1)
    })

    it('does not call sendAuthenticate when the socket is already authenticated', async () => {
      CapacitorHttp.post.mockResolvedValue({
        status: 200,
        data: { user: { accessToken: 'new-access' } }
      })
      const { sendAuthenticate } = await callRefreshToken({
        $this: { $db: { getRefreshToken: vi.fn(async () => 'rt'), setServerConnectionConfig: vi.fn(async (c) => c) }, $socket: { connected: true, isAuthenticated: true, sendAuthenticate: vi.fn() } }
      })
      expect(sendAuthenticate).not.toHaveBeenCalled()
    })
  })
})
