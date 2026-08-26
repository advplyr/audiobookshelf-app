import { CapacitorHttp } from '@capacitor/core'
import { AbsNetwork } from '@/plugins/capacitor'

const CACHE_TTL_MS = 30000

function normalizeAddress(address) {
  if (!address) return null
  try {
    return new URL(address).href.replace(/\/$/, '')
  } catch (error) {
    console.error('[serverAddress] Invalid address', address, error)
    return null
  }
}

function normalizeSsid(ssid) {
  return (ssid || '').replace(/^"(.*)"$/, '$1').trim()
}

function getLocalSsids(config) {
  if (!config) return []
  if (Array.isArray(config.localSsidWhitelist)) return config.localSsidWhitelist
  if (Array.isArray(config.localSsids)) return config.localSsids
  return []
}

export default function ({ store }, inject) {
  const cache = new Map()

  const serverAddress = {
    normalizeAddress,
    async getCurrentWifiSsid() {
      try {
        const result = await AbsNetwork.getCurrentWifiSsid()
        return normalizeSsid(result?.ssid)
      } catch (error) {
        console.warn('[serverAddress] Failed to get current Wi-Fi SSID', error)
        return null
      }
    },
    isSameServerConfig(configOrId, localServerConnectionConfigId) {
      const configId = typeof configOrId === 'string' ? configOrId : configOrId?.id
      return !!configId && !!localServerConnectionConfigId && configId === localServerConnectionConfigId
    },
    async canReach(address, customHeaders = {}, timeout = 2500) {
      const normalizedAddress = normalizeAddress(address)
      if (!normalizedAddress) return false
      try {
        const response = await CapacitorHttp.get({
          url: `${normalizedAddress}/ping`,
          headers: customHeaders || {},
          connectTimeout: timeout,
          readTimeout: timeout
        })
        return response.status === 200 && response.data?.success !== false
      } catch (error) {
        console.warn('[serverAddress] Ping failed', normalizedAddress, error?.message || error)
        return false
      }
    },
    async resolve(config = store.state.user.serverConnectionConfig, options = {}) {
      const primaryAddress = normalizeAddress(config?.address)
      const localAddress = normalizeAddress(config?.localAddress)
      if (!primaryAddress) return null

      if (!localAddress) {
        store.commit('setActiveServerAddress', primaryAddress)
        return primaryAddress
      }

      const currentSsid = await this.getCurrentWifiSsid()
      const allowedSsids = getLocalSsids(config).map(normalizeSsid).filter(Boolean)
      const ssidAllowed = !!currentSsid && allowedSsids.includes(currentSsid)

      if (!ssidAllowed) {
        store.commit('setActiveServerAddress', primaryAddress)
        return primaryAddress
      }

      const cacheKey = `${config.id || primaryAddress}|${currentSsid}|${localAddress}`
      const cached = cache.get(cacheKey)
      if (!options.forceRefresh && cached && Date.now() - cached.time < CACHE_TTL_MS) {
        store.commit('setActiveServerAddress', cached.address)
        return cached.address
      }

      const canUseLocal = options.skipPing || (await this.canReach(localAddress, config.customHeaders))
      const resolvedAddress = canUseLocal ? localAddress : primaryAddress
      cache.set(cacheKey, { address: resolvedAddress, time: Date.now() })
      store.commit('setActiveServerAddress', resolvedAddress)
      return resolvedAddress
    }
  }

  inject('serverAddress', serverAddress)
}
