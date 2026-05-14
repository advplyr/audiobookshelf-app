import { Capacitor } from '@capacitor/core'
import { FastAverageColor } from 'fast-average-color'
import { imageHttpDataToBlob } from '@/utils/imageHttpBlob'

/**
 * True when the cover URL is http(s) and not same-origin with the WebView (or browser tab).
 * Same-origin URLs (e.g. Capacitor file bridge on localhost) can use FastAverageColor directly.
 */
export function shouldFetchCoverViaNativeHttp(coverUrl) {
  if (!coverUrl || typeof coverUrl !== 'string') return false
  if (!coverUrl.startsWith('http://') && !coverUrl.startsWith('https://')) return false
  try {
    return new URL(coverUrl).origin !== window.location.origin
  } catch {
    return false
  }
}

/**
 * Average color for a cover image. On native, cross-origin http(s) covers are loaded with
 * CapacitorHttp (no WebView CORS), then sampled via a same-origin blob URL.
 * @param {*} vm - component instance with $nativeHttp (this)
 * @param {string} fullCoverUrl
 * @returns {Promise<{ rgba: string, isLight: boolean }|null>}
 */
export async function getAverageColorFromCoverUrl(vm, fullCoverUrl) {
  if (!fullCoverUrl) return null

  const fac = new FastAverageColor()
  let objectUrl = null
  try {
    let resource = fullCoverUrl

    if (Capacitor.isNativePlatform() && shouldFetchCoverViaNativeHttp(fullCoverUrl)) {
      const raw = await vm.$nativeHttp.get(fullCoverUrl, {
        responseType: 'blob',
        connectTimeout: 15000,
        readTimeout: 30000
      })
      const blob = imageHttpDataToBlob(raw)
      if (!blob) {
        throw new Error('Cover image response could not be converted to a blob')
      }
      objectUrl = URL.createObjectURL(blob)
      resource = objectUrl
    }

    const color = await fac.getColorAsync(resource)
    return { rgba: color.rgba, isLight: color.isLight }
  } catch (e) {
    console.error('[coverAverageColor]', e)
    return null
  } finally {
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
    }
    fac.destroy()
  }
}
