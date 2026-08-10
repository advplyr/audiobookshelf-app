import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock CapacitorHttp so refreshAccessToken can be exercised against controlled
// responses without a device. post is re-stubbed in each test below.
vi.mock('@capacitor/core', () => ({
  // nativeHttp.js only uses CapacitorHttp.post; CapacitorHttp.request is used
  // elsewhere in the plugin and is not exercised here, but providing a stub
  // keeps the module load safe.
  CapacitorHttp: {
    post: vi.fn(),
    request: vi.fn()
  }
}))

const { CapacitorHttp } = await import('@capacitor/core')
// Load the plugin factory and invoke it to get the nativeHttp object whose
// refreshAccessToken method we test directly. The factory registers the object
// via the Nuxt `inject(name, obj)` callback; we capture it there.
const nativeHttpFactory = (await import('@/plugins/nativeHttp.js')).default
let nativeHttp
nativeHttpFactory(
  // Nuxt context — minimal stubs; refreshAccessToken touches none of these.
  { store: { state: { user: {} }, getters: {}, commit: () => {} }, $db: {}, $socket: {} },
  (name, obj) => { nativeHttp = obj }
)

describe('plugins/nativeHttp.js — refreshAccessToken', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('permanent failure (returns {_permanentFailure})', () => {
    it('returns _permanentFailure on HTTP 401', async () => {
      CapacitorHttp.post.mockResolvedValue({ status: 401, data: {} })
      const result = await nativeHttp.refreshAccessToken('rt', 'https://abs.example')
      expect(result).toEqual({ _permanentFailure: true })
      expect(CapacitorHttp.post).toHaveBeenCalledWith(
        expect.objectContaining({
          url: 'https://abs.example/auth/refresh',
          headers: expect.objectContaining({ 'x-refresh-token': 'rt' })
        })
      )
    })
  })

  describe('transient failure (returns {_transientFailure})', () => {
    it.each([403, 404, 500, 503])('returns _transientFailure for HTTP %i', async (status) => {
      CapacitorHttp.post.mockResolvedValue({ status, data: {} })
      const result = await nativeHttp.refreshAccessToken('rt', 'https://abs.example')
      expect(result).toEqual({ _transientFailure: true, status })
    })

    it('returns _transientFailure with status 0 when CapacitorHttp.post throws', async () => {
      const transportError = new Error('network down')
      CapacitorHttp.post.mockRejectedValue(transportError)
      const result = await nativeHttp.refreshAccessToken('rt', 'https://abs.example')
      expect(result).toEqual({ _transientFailure: true, status: 0, error: 'network down' })
    })
  })

  describe('success', () => {
    it('returns {accessToken, refreshToken} on HTTP 200 with a valid body', async () => {
      CapacitorHttp.post.mockResolvedValue({
        status: 200,
        data: { user: { accessToken: 'a1', refreshToken: 'r1' } }
      })
      const result = await nativeHttp.refreshAccessToken('rt', 'https://abs.example')
      expect(result).toEqual({ accessToken: 'a1', refreshToken: 'r1' })
    })

    it('returns null on HTTP 200 when accessToken is missing', async () => {
      CapacitorHttp.post.mockResolvedValue({ status: 200, data: { user: {} } })
      const result = await nativeHttp.refreshAccessToken('rt', 'https://abs.example')
      expect(result).toBeNull()
    })
  })

  describe('input validation', () => {
    it('throws (caught → transient) when serverAddress is empty', async () => {
      // refreshAccessToken throws "No server address available" synchronously;
      // its own try/catch converts that into a _transientFailure object.
      const result = await nativeHttp.refreshAccessToken('rt', '')
      expect(result).toEqual({ _transientFailure: true, status: 0, error: 'No server address available' })
    })
  })
})
