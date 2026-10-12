import Vue from 'vue'
import { AbsLogger, AbsFileSystem } from '@/plugins/capacitor'
import { AUTO_DELETE_DEFAULT_SETTINGS, AUTO_DELETE_SWEEP_INTERVAL, getAutoDeleteCandidates } from '@/utils/autoDelete'

/**
 * Removes finished downloads after a configurable delay.
 * Settings are stored in Capacitor Preferences.
 */
class AutoDelete {
  constructor(app, store) {
    this.app = app
    this.store = store
    this.running = false
    this.lastSweepAt = 0
  }

  async getSettings() {
    const saved = await this.app.$localStore.getAutoDeleteSettings()
    return { ...AUTO_DELETE_DEFAULT_SETTINGS, ...(saved || {}) }
  }

  async saveSettings(settings) {
    await this.app.$localStore.setAutoDeleteSettings(settings)
    // Reset the sweep timer so a settings change is applied on the next sweep
    this.lastSweepAt = 0
  }

  /**
   * While the app stays open sweeps are spaced out, an app start always sweeps
   */
  async isDue() {
    const settings = await this.getSettings()
    if (!settings.delayDays) return false
    return Date.now() - this.lastSweepAt >= AUTO_DELETE_SWEEP_INTERVAL
  }

  /**
   * Requires a connected server and that local progress is already synced, callers should sweep after a successful sync.
   */
  async sweep() {
    if (this.running) return
    this.running = true
    try {
      const settings = await this.getSettings()
      if (!settings.delayDays) {
        // Turning the setting back on starts a fresh delay
        if (Object.keys(await this.app.$localStore.getAutoDeleteFirstSeen()).length) {
          await this.app.$localStore.setAutoDeleteFirstSeen({})
        }
        return
      }

      const serverConnectionConfigId = this.store.getters['user/getServerConnectionConfigId']
      const userId = this.store.state.user.user?.id
      if (!serverConnectionConfigId || !userId || !this.store.state.networkConnected) {
        AbsLogger.info({ tag: 'AutoDelete', message: 'sweep: skipped, not connected to server' })
        return
      }
      this.lastSweepAt = Date.now()

      const [localLibraryItems, localMediaProgress, firstSeen] = await Promise.all([this.app.$db.getLocalLibraryItems(), this.app.$db.getAllLocalMediaProgress(), this.app.$localStore.getAutoDeleteFirstSeen()])

      const result = getAutoDeleteCandidates({
        localLibraryItems,
        localMediaProgress,
        settings,
        firstSeen,
        now: Date.now(),
        serverConnectionConfigId,
        userId,
        inUseItemIds: this.getInUseItemIds()
      })
      await this.app.$localStore.setAutoDeleteFirstSeen(result.firstSeen)
      if (!result.candidates.length) return

      AbsLogger.info({ tag: 'AutoDelete', message: `sweep: ${result.candidates.length} finished download(s) past delay` })

      let deletedCount = 0
      let deletedBytes = 0
      for (const candidate of result.candidates) {
        // Playback may have started during the sweep
        if (this.getInUseItemIds().includes(candidate.libraryItemId) || this.getInUseItemIds().includes(candidate.localLibraryItem.id)) continue
        if (!(await this.isSafeOnServer(candidate))) continue

        if (await this.deleteCandidate(candidate)) {
          deletedCount++
          deletedBytes += candidate.size
          delete result.firstSeen[candidate.progressId]
          AbsLogger.info({ tag: 'AutoDelete', message: `sweep: removed "${candidate.title}"` })
        }
      }
      await this.app.$localStore.setAutoDeleteFirstSeen(result.firstSeen)

      if (deletedCount) {
        // $toast, $getString and $bytesPretty are defined on Vue.prototype
        Vue.prototype.$toast.info(Vue.prototype.$getString('MessageAutoDeleteRemoved', [deletedCount, Vue.prototype.$bytesPretty(deletedBytes)]))
      }
    } catch (error) {
      AbsLogger.error({ tag: 'AutoDelete', message: `sweep failed: ${error?.message || error}` })
    } finally {
      this.running = false
    }
  }

  /**
   * Ids of the library item currently loaded in the player (server id and local id)
   */
  getInUseItemIds() {
    const session = this.store.state.currentPlaybackSession
    if (!session) return []
    return [session.libraryItemId, session.localLibraryItem?.id].filter(Boolean)
  }

  /**
   * Only delete if the server still has the item (or episode) and also has it as finished,
   * this confirms progress was synced and the item can be downloaded again.
   * Any failure, including being offline, means the item is kept.
   */
  async isSafeOnServer(candidate) {
    try {
      const progressUrl = candidate.episodeId ? `/api/me/progress/${candidate.libraryItemId}/${candidate.episodeId}` : `/api/me/progress/${candidate.libraryItemId}`
      const serverProgress = await this.app.$nativeHttp.get(progressUrl)
      if (!serverProgress?.isFinished) return false

      const libraryItem = await this.app.$nativeHttp.get(`/api/items/${candidate.libraryItemId}${candidate.episodeId ? '?expanded=1' : ''}`)
      if (!libraryItem?.id) return false
      if (candidate.episodeId && !libraryItem.media?.episodes?.some((ep) => ep.id === candidate.episodeId)) return false
      return true
    } catch (error) {
      AbsLogger.info({ tag: 'AutoDelete', message: `sweep: keeping "${candidate.title}", could not confirm on server (${error?.message || error})` })
      return false
    }
  }

  async deleteCandidate(candidate) {
    if (!candidate.localEpisode) {
      const res = await AbsFileSystem.deleteItem(candidate.localLibraryItem)
      return !!res?.success
    }

    const localFileId = candidate.localEpisode.audioTrack.localFileId
    const res = await AbsFileSystem.deleteTrackFromItem({
      id: candidate.localLibraryItem.id,
      trackLocalFileId: localFileId,
      trackContentUrl: candidate.localEpisode.audioTrack.contentUrl
    })
    if (!res?.id) return false

    // Last episode removed so remove the podcast item too
    if (!res.media?.episodes?.length) {
      await AbsFileSystem.deleteItem(res)
    }
    return true
  }
}

export default ({ app, store }, inject) => {
  inject('autoDelete', new AutoDelete(app, store))
}
