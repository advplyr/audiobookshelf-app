const MS_PER_DAY = 24 * 60 * 60 * 1000

export const AUTO_DELETE_DEFAULT_SETTINGS = {
  delayDays: 0, // 0 = off
  includePodcasts: false
}

export const AUTO_DELETE_DELAY_DAYS = [1, 3, 7, 14, 30]

// Minimum time between sweeps while the app stays open (each app start always sweeps)
export const AUTO_DELETE_SWEEP_INTERVAL = MS_PER_DAY / 2

/**
 * Finds finished, server-linked downloads that have been finished for longer than the configured delay.
 * Pure function: does not touch files or the network.
 *
 * The delay is counted from the later of when the item was finished and when it was first seen finished by a sweep.
 * Turning the setting on, or re-downloading an item that was finished long ago, does not delete anything immediately.
 * The most recently played item (newest local progress update) is never returned.
 *
 * @param {Object} params
 * @param {Object[]} params.localLibraryItems
 * @param {Object[]} params.localMediaProgress
 * @param {{ delayDays: number, includePodcasts: boolean }} params.settings
 * @param {Object<string, number>} params.firstSeen local media progress id -> timestamp first seen finished
 * @param {number} params.now
 * @param {string} params.serverConnectionConfigId currently connected server config
 * @param {string} params.userId currently connected user
 * @param {string[]} params.inUseItemIds local library item id / library item id of the item loaded in the player
 * @returns {{ candidates: Object[], firstSeen: Object<string, number> }} firstSeen is the updated map to persist
 */
export function getAutoDeleteCandidates({ localLibraryItems, localMediaProgress, settings, firstSeen, now, serverConnectionConfigId, userId, inUseItemIds }) {
  const delayMs = (settings?.delayDays || 0) * MS_PER_DAY
  if (delayMs <= 0 || !serverConnectionConfigId || !userId) {
    return { candidates: [], firstSeen: {} }
  }

  const itemsById = new Map((localLibraryItems || []).map((lli) => [lli.id, lli]))
  const nextFirstSeen = {}
  const candidates = []

  // The book (or podcast episode) played last is never removed and its countdown is not recorded.
  // Its delay starts once something else has been played.
  const lastPlayedProgress = (localMediaProgress || []).reduce((latest, progress) => ((progress.lastUpdate || 0) > (latest?.lastUpdate || 0) ? progress : latest), null)

  for (const progress of localMediaProgress || []) {
    if (!progress.isFinished) continue
    if (progress.id === lastPlayedProgress?.id) continue

    const localLibraryItem = itemsById.get(progress.localLibraryItemId)
    const target = localLibraryItem ? getDeletionTarget(localLibraryItem, progress, settings, { serverConnectionConfigId, userId }) : null
    if (!target) continue

    // Started counting from the first time a sweep saw this item finished
    nextFirstSeen[progress.id] = firstSeen?.[progress.id] || now

    const finishedAt = progress.finishedAt || progress.lastUpdate || 0
    const countedFrom = Math.max(finishedAt, nextFirstSeen[progress.id])
    if (now - countedFrom < delayMs) continue

    if (inUseItemIds?.some((id) => id && (id === localLibraryItem.id || id === localLibraryItem.libraryItemId))) continue

    candidates.push({
      progressId: progress.id,
      localLibraryItem,
      localEpisode: target.localEpisode,
      libraryItemId: localLibraryItem.libraryItemId,
      episodeId: target.localEpisode?.serverEpisodeId || null,
      title: target.title,
      size: target.size
    })
  }

  return { candidates, firstSeen: nextFirstSeen }
}

/**
 * Checks the item is one that is safe to remove (re-downloadable from the connected server) and describes what would be removed
 *
 * @returns {{ localEpisode: Object|null, title: string, size: number }|null} null if the item should never be auto deleted
 */
function getDeletionTarget(localLibraryItem, progress, settings, { serverConnectionConfigId, userId }) {
  // Only items downloaded from the connected server can be re-downloaded. Local-only items are skipped.
  if (!localLibraryItem.libraryItemId) return null
  if (localLibraryItem.serverConnectionConfigId !== serverConnectionConfigId || localLibraryItem.serverUserId !== userId) return null
  if (localLibraryItem.isInvalid) return null

  const title = localLibraryItem.media?.metadata?.title || localLibraryItem.id

  if (localLibraryItem.mediaType === 'podcast') {
    if (!settings.includePodcasts || !progress.localEpisodeId) return null
    const localEpisode = localLibraryItem.media?.episodes?.find((ep) => ep.id === progress.localEpisodeId)
    if (!localEpisode?.serverEpisodeId || !localEpisode.audioTrack?.localFileId) return null
    const localFile = localLibraryItem.localFiles?.find((lf) => lf.id === localEpisode.audioTrack.localFileId)
    return { localEpisode, title: `${title} - ${localEpisode.title || localEpisode.id}`, size: localFile?.size || 0 }
  }

  // Books need audio. Ebook-only items are skipped.
  if (progress.localEpisodeId || !localLibraryItem.media?.tracks?.length) return null
  const size = (localLibraryItem.localFiles || []).reduce((total, lf) => total + (lf.size || 0), 0)
  return { localEpisode: null, title, size }
}
