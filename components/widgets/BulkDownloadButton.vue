<template>
  <ui-btn v-if="canDownload" color="primary" :loading="busy" :disabled="busy" :padding-x="compact ? 2 : undefined" :padding-y="compact ? 0 : undefined" :aria-label="label" @click="download">
    <span v-if="compact" class="material-symbols text-2xl align-middle" aria-hidden="true">download</span>
    <template v-else>{{ label }}</template>
  </ui-btn>
</template>

<script>
import { App } from '@capacitor/app'
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

const pageSize = 100

export default {
  mixins: [cellularPermissionHelpers],
  props: {
    libraryId: String,
    podcastId: String,
    compact: Boolean,
    mediaType: { type: String, required: true }
  },
  data() {
    return { busy: false }
  },
  computed: {
    label() {
      return this.libraryId ? this.$strings.ButtonDownloadEntireLibrary : this.$strings.ButtonDownloadAllEpisodes
    },
    connectionId() {
      return this.$store.getters['user/getServerConnectionConfigId']
    },
    userId() {
      return this.$store.state.user.user?.id
    },
    canDownload() {
      return this.$platform === 'android' && !!(this.libraryId || this.podcastId) && !!this.connectionId && !!this.userId && this.$store.state.networkConnected && this.$store.getters['user/getUserCanDownload']
    }
  },
  methods: {
    async chooseFolder(checkContext) {
      const internalId = `internal-${this.mediaType}`
      const folders = (await this.$db.getLocalFolders()) || []
      await checkContext()
      if (!folders.some((folder) => folder.mediaType === this.mediaType && folder.id !== internalId)) {
        return { id: internalId, name: this.$strings.LabelInternalAppStorage }
      }
      if (this.$store.state.globals.showSelectLocalFolderModal) return null

      return new Promise((resolve) => {
        const finish = (folder) => {
          stopWatching()
          this.$off('hook:beforeDestroy', cancel)
          resolve(folder)
        }
        const cancel = () => {
          this.$store.commit('globals/setShowSelectLocalFolderModal', false)
          finish(null)
        }
        const stopWatching = this.$store.watch(
          (state) => state.globals.showSelectLocalFolderModal,
          (show) => {
            if (!show) finish(null)
          }
        )
        this.$once('hook:beforeDestroy', cancel)
        this.$store.commit('globals/showSelectLocalFolderModal', {
          mediaType: this.mediaType,
          callback: finish
        })
      })
    },
    // Finish and validate all unfiltered pages before any downloads are submitted.
    async listLibraryItems(libraryId, checkContext) {
      const ids = new Set()
      let expectedTotal
      for (let page = 0; ; page++) {
        await checkContext()
        const result = await this.$nativeHttp.get(`/api/libraries/${encodeURIComponent(libraryId)}/items?limit=${pageSize}&page=${page}&minified=1`, { connectTimeout: 10000, readTimeout: 30000 })
        if (!Array.isArray(result?.results) || !Number.isSafeInteger(result.total) || result.total < 0 || (expectedTotal !== undefined && result.total !== expectedTotal) || result.results.length !== Math.min(pageSize, Math.max(0, result.total - page * pageSize)) || result.results.some((item) => typeof item?.id !== 'string' || !item.id)) {
          throw new Error('Invalid or changed library page')
        }
        expectedTotal = result.total
        for (const item of result.results) ids.add(item.id)
        if ((page + 1) * pageSize >= expectedTotal) {
          if (ids.size !== expectedTotal) throw new Error('Library membership changed during pagination')
          return [...ids]
        }
      }
    },
    // Keep only server audio and the metadata needed to queue episodes without refetching the podcast.
    async listEpisodes(libraryItemId, checkContext) {
      await checkContext()
      const item = await this.$nativeHttp.get(`/api/items/${encodeURIComponent(libraryItemId)}?expanded=1`, { connectTimeout: 10000, readTimeout: 30000 })
      if (item?.id !== libraryItemId || item.mediaType !== 'podcast' || !Array.isArray(item.media?.episodes)) {
        throw new Error('Invalid podcast details')
      }
      const episodes = new Map()
      for (const episode of item.media.episodes) {
        const ino = episode?.audioFile?.ino
        const track = episode?.audioTrack
        const relPath = track?.metadata?.relPath
        if (typeof episode?.id !== 'string' || !episode.id.trim() || !((typeof ino === 'string' && ino.trim()) || Number.isSafeInteger(ino)) || typeof relPath !== 'string' || !relPath.trim()) continue

        const { id, index, episode: number, episodeType, title, subtitle, description, pubDate, publishedAt, chapters, duration, size } = episode
        const { filename, ext, path, size: fileSize } = track.metadata
        const metadata = { filename, ext, path, relPath, size: fileSize }
        episodes.set(id, {
          id,
          index,
          episode: number,
          episodeType,
          title,
          subtitle,
          description,
          pubDate,
          publishedAt,
          chapters,
          duration,
          size,
          audioFile: { index: episode.audioFile.index, ino, metadata },
          audioTrack: {
            index: track.index,
            startOffset: track.startOffset,
            duration: track.duration,
            title: track.title,
            contentUrl: track.contentUrl,
            mimeType: track.mimeType,
            metadata
          }
        })
      }
      return {
        item: { ...item, libraryFiles: [], media: { ...item.media, episodes: [] } },
        episodes
      }
    },
    // Prepare and confirm the selection, then submit sequentially with foreground/account/cancellation checks.
    async download() {
      if (this.busy || !this.canDownload) return
      this.busy = true
      const { connectionId, userId, libraryId, podcastId, mediaType } = this
      const oldUserId = this.$store.state.user.user?.oldUserId
      let stopped = false
      let networkType = this.$store.state.networkConnectionType
      const checkActiveContext = async () => {
        const { isActive } = await App.getState()
        if (!isActive || this._isDestroyed || !this.canDownload || this.connectionId !== connectionId || this.userId !== userId || this.libraryId !== libraryId || this.podcastId !== podcastId || this.mediaType !== mediaType) {
          stopped = true
          throw new Error('Bulk download context changed')
        }
      }
      const checkContext = async () => {
        await checkActiveContext()
        const currentType = this.$store.state.networkConnectionType
        if (currentType !== networkType && currentType === 'cellular') {
          if (!(await this.checkCellularPermission('download'))) {
            stopped = true
            throw new Error('Bulk download cellular permission denied')
          }
          await checkActiveContext()
        }
        networkType = currentType
      }
      let queued = 0
      let skipped = 0
      let failed = 0
      let submitting = false
      let inventoryId
      const stagedManifests = new Set()

      try {
        if (!(await this.checkCellularPermission('download'))) return
        await checkContext()
        const { generation } = await AbsDownloader.getCancellationGeneration()
        const folder = await this.chooseFolder(checkContext)
        if (!folder) return
        const ids = libraryId ? await this.listLibraryItems(libraryId, checkContext) : [podcastId]
        const groups = []
        let count = 0
        for (const id of ids) {
          const podcast = mediaType === 'podcast' ? await this.listEpisodes(id, checkContext) : null
          const groupCount = podcast ? podcast.episodes.size : 1
          if (podcast && groupCount) {
            const { id: manifestId } = await AbsDownloader.bulkPodcastManifest({
              action: 'stage',
              manifest: { item: podcast.item, episodes: [...podcast.episodes] }
            })
            if (typeof manifestId !== 'string' || !manifestId) throw new Error('Missing staged podcast manifest ID')
            stagedManifests.add(manifestId)
            groups.push({ id, manifestId, groupCount })
          } else if (!podcast) {
            groups.push({ id })
          }
          count += groupCount
        }
        await checkContext()
        if (!count) {
          this.$toast.info(this.$strings.MessageBulkDownloadEmpty)
          return
        }
        const { value } = await Dialog.confirm({
          title: this.$strings.HeaderConfirm,
          message: this.$getString(libraryId ? 'MessageBulkLibraryDownloadConfirm' : 'MessageBulkPodcastDownloadConfirm', [count, folder.name])
        })
        if (!value) return

        await checkContext()
        const inventory = await AbsDownloader.getBulkOfflineItems({ libraryItemIds: ids, mediaType, connectionId, userId, oldUserId })
        inventoryId = inventory.inventoryId
        const localItems = inventory.items
        const offline = new Map()
        const foreignInternal = new Set()
        const sharedPodcastPaths = new Map()
        const localUserIds = new Set([userId, oldUserId].filter(Boolean))
        for (const item of localItems) {
          if (!item.libraryItemId) continue
          if (item.id === `local_${item.libraryItemId}` && (item.serverConnectionConfigId !== connectionId || !localUserIds.has(item.serverUserId))) {
            foreignInternal.add(item.libraryItemId)
          }
          if (item.serverConnectionConfigId !== connectionId || !localUserIds.has(item.serverUserId)) continue
          if (mediaType === 'podcast' && item.folderId === folder.id && localUserIds.has(item.serverUserId) && !sharedPodcastPaths.has(item.libraryItemId)) {
            sharedPodcastPaths.set(item.libraryItemId, item.absolutePath)
          }
          if (mediaType === 'book' && !item.hasOfflineBook) continue
          const episodes = offline.get(item.libraryItemId) || new Set()
          for (const episodeId of item.episodeIds) episodes.add(episodeId)
          offline.set(item.libraryItemId, episodes)
        }

        submitting = true
        const progressByEpisode = new Map((this.$store.state.user.user?.mediaProgress || []).filter((progress) => progress.episodeId).map((progress) => [progress.episodeId, progress]))
        for (const { id, manifestId, groupCount } of groups) {
          await checkContext()
          let podcast = null
          if (manifestId) {
            try {
              podcast = await AbsDownloader.bulkPodcastManifest({ action: 'consume', id: manifestId })
              if (podcast?.item?.id !== id || !Array.isArray(podcast.episodes) || podcast.episodes.length !== groupCount) {
                throw new Error('Invalid staged podcast manifest')
              }
            } catch (error) {
              console.error('Could not read staged podcast manifest', error)
              failed += groupCount
              continue
            }
          }
          for (const [episodeId, episode] of podcast?.episodes || [[null, null]]) {
            await checkContext()
            if (episodeId ? offline.get(id)?.has(episodeId) : offline.has(id)) {
              skipped++
              continue
            }
            if (folder.id.startsWith('internal-') && foreignInternal.has(id)) {
              failed++
              continue
            }
            try {
              const result = await AbsDownloader.downloadLibraryItem({
                libraryItemId: id,
                ...(podcast
                  ? {
                      episodeId,
                      existingPodcastPath: sharedPodcastPaths.get(id),
                      podcastSnapshot: {
                        ...podcast.item,
                        media: { ...podcast.item.media, episodes: [episode] },
                        userMediaProgress: progressByEpisode.get(episodeId)
                      }
                    }
                  : {}),
                localFolderId: folder.id,
                expectedConnectionId: connectionId,
                expectedUserId: userId,
                cancellationGeneration: generation,
                inventoryId
              })
              if (result?.submissionStopped) {
                this.$toast.info(this.$strings.MessageBulkDownloadStopped)
                return
              }
              if (result?.alreadyQueued) skipped++
              else if (result?.error) failed++
              else queued++
            } catch (error) {
              console.error('Failed to submit bulk download item', error)
              failed++
            }
          }
        }
      } catch (error) {
        console.error('Bulk download stopped', error)
        this.$toast.error(this.$strings[stopped || submitting ? 'MessageBulkDownloadStopped' : 'MessageBulkDownloadPrepareFailed'])
      } finally {
        if (inventoryId) {
          try {
            await AbsDownloader.releaseBulkOfflineItems({ inventoryId })
          } catch (error) {
            console.error('Could not release bulk offline inventory', error)
          }
        }
        if (stagedManifests.size) {
          try {
            await AbsDownloader.bulkPodcastManifest({ action: 'discard', ids: [...stagedManifests] })
          } catch (error) {
            console.error('Could not clear bulk podcast manifests', error)
          }
        }
        if (submitting) {
          const summary = this.$getString('MessageBulkDownloadSummary', [queued, skipped, failed])
          if (failed) this.$toast.error(summary)
          else this.$toast.info(summary)
        }
        this.busy = false
      }
    }
  }
}
</script>
