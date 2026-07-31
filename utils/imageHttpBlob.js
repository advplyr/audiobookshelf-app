/**
 * Normalize CapacitorHttp binary responses (Blob, base64 string, data URL, ArrayBuffer) to a Blob.
 * @param {unknown} data - CapacitorHttp response `data` when responseType is blob/arraybuffer
 * @param {string} [mimeType='image/jpeg'] - fallback Content-Type
 * @returns {Blob|null}
 */
export function imageHttpDataToBlob(data, mimeType = 'image/jpeg') {
  if (data == null) return null
  if (typeof Blob !== 'undefined' && data instanceof Blob) return data
  if (data instanceof ArrayBuffer) return new Blob([data], { type: mimeType })
  if (ArrayBuffer.isView(data)) return new Blob([data], { type: mimeType })
  if (typeof data === 'object' && typeof data.base64 === 'string') {
    return base64ToBlob(data.base64, mimeType)
  }
  if (typeof data === 'string') {
    if (data.startsWith('data:')) {
      const match = /^data:([^;]+);base64,([\s\S]+)$/.exec(data)
      if (match) {
        const type = match[1] || mimeType
        return base64ToBlob(match[2], type)
      }
    }
    try {
      return base64ToBlob(data, mimeType)
    } catch {
      return null
    }
  }
  return null
}

function base64ToBlob(base64, type) {
  const binaryString = atob(base64)
  const len = binaryString.length
  const bytes = new Uint8Array(len)
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i)
  }
  return new Blob([bytes], { type })
}
