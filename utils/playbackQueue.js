// Keep server identity for UI/progress, and translate to device identity only at the bridge.
export function nativeQueueItems(items) {
  return items.map((item) => ({
    libraryItemId: item.localLibraryItem?.id || item.libraryItemId,
    episodeId: item.localLibraryItem ? item.localEpisode?.id || null : item.episodeId || null
  }))
}

export function queueItemPayload(item) {
  const nativeItem = nativeQueueItems([item])[0]
  return {
    ...nativeItem,
    serverLibraryItemId: item.libraryItemId,
    serverEpisodeId: item.episodeId || null
  }
}

export function downloadedBookItems(books, localItems, serverConnectionConfigId) {
  return books.flatMap((book) => {
    const localLibraryItem = localItems.find((local) => local.libraryItemId === book.id && local.serverConnectionConfigId === serverConnectionConfigId && local.id?.startsWith('local') && local.mediaType === 'book' && !local.isInvalid && local.media?.tracks?.length)
    const expectedTracks = book.media?.numTracks || book.media?.tracks?.length || 0
    if (!localLibraryItem || book.isMissing || book.isInvalid || localLibraryItem.media.tracks.length < expectedTracks) return []
    return [{ libraryItemId: book.id, episodeId: null, localLibraryItem }]
  })
}

export async function fetchSeriesBooks(http, libraryId, seriesId, encode) {
  const books = []
  const ids = new Set()
  const limit = 100
  for (let page = 0; ; page++) {
    const query = new URLSearchParams({ filter: `series.${encode(seriesId)}`, sort: 'sequence', desc: '0', limit: String(limit), page: String(page), minified: '1' })
    const payload = await http.get(`/api/libraries/${libraryId}/items?${query}`)
    if (!Array.isArray(payload?.results) || !Number.isFinite(payload.total)) throw new Error('Failed to load the complete series')
    for (const book of payload.results) {
      if (ids.has(book.id)) throw new Error('Series changed while loading. Please try again.')
      ids.add(book.id)
      books.push(book)
    }
    if (books.length >= payload.total) return books
    if (!payload.results.length) throw new Error('Failed to load the complete series')
  }
}

export async function resolvePlaybackQueue(context, payload) {
  const source = payload.queueSource
  if (!source || context.$platform !== 'android') return { payload, queue: null }
  let items
  if (source.sourceType === 'playlist') {
    items = source.items
  } else if (source.sourceType === 'series' || source.sourceType === 'collection') {
    const books = source.sourceType === 'series' ? await fetchSeriesBooks(context.$nativeHttp, source.libraryId, source.sourceId, context.$encode) : source.books
    const localItems = (await context.$db.getLocalLibraryItems('book')) || []
    items = downloadedBookItems(books, localItems, context.$store.getters['user/getServerConnectionConfigId'])
  } else {
    throw new Error('Unknown playback queue source')
  }
  let currentIndex
  if (payload.libraryItemId) {
    currentIndex = items.findIndex((item) => {
      const nativeItem = nativeQueueItems([item])[0]
      const serverMatch = item.libraryItemId === (payload.serverLibraryItemId || payload.libraryItemId) && (item.episodeId || null) === (payload.serverEpisodeId || payload.episodeId || null)
      const localMatch = nativeItem.libraryItemId === payload.libraryItemId && nativeItem.episodeId === (payload.episodeId || null)
      return serverMatch || localMatch
    })
    // A manually selected undownloaded book may play normally, but never retains a queue.
    if (currentIndex < 0) return { payload, queue: null }
  } else {
    currentIndex = items.findIndex((item) => {
      const localProgress = item.localLibraryItem && context.$store.getters['globals/getLocalMediaProgressById'](item.localLibraryItem.id, item.localEpisode?.id)
      const progress = localProgress || context.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)
      return !progress?.isFinished
    })
    if (currentIndex < 0) throw new Error(items.length ? 'All downloaded books are finished' : 'No downloaded audiobooks available')
  }
  return {
    payload: { ...payload, ...queueItemPayload(items[currentIndex]) },
    queue: { sourceType: source.sourceType, sourceId: source.sourceId, items, currentIndex }
  }
}
