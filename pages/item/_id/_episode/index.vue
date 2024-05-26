<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto overflow-x-hidden relative bg-bg">
    <div class="flex mb-2">
      <div class="w-10 min-w-10">
        <covers-preview-cover :src="coverUrl" :width="40" :book-cover-aspect-ratio="bookCoverAspectRatio" :show-resolution="false" class="md:hidden" />
      </div>
      <div class="flex-grow px-2">
        <div class="-mt-0.5 mb-0.5">
          <nuxt-link :to="`/item/${libraryItemId}`" class="text-sm text-fg underline">{{ podcast.metadata.title }}</nuxt-link>
        </div>
        <p v-if="publishedAt" class="text-xs text-fg-muted">{{ $dateDistanceFromNow(publishedAt) }}</p>
      </div>
    </div>

    <p class="text-lg font-semibold">{{ title }}</p>

    <div v-if="episodeNumber || season || episodeType" class="flex py-2 items-center -mx-0.5">
      <div v-if="episodeNumber" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-60 rounded-full text-xs font-light text-fg">{{ $strings.LabelEpisode }} #{{ episodeNumber }}</div>
      <div v-if="season" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-60 rounded-full text-xs font-light text-fg">{{ $strings.LabelSeason }} #{{ season }}</div>
      <div v-if="episodeType" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-60 rounded-full text-xs font-light text-fg capitalize">{{ episodeType }}</div>
    </div>

    <!-- user progress card -->
    <div v-if="progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-fg mt-4 relative" :class="resettingProgress ? 'opacity-25' : ''">
      <p class="leading-6">{{ $strings.LabelYourProgress }}: {{ Math.round(progressPercent * 100) }}%</p>
      <p v-if="progressPercent < 1" class="text-fg-muted text-xs">{{ $getString('LabelTimeRemaining', [$elapsedPretty(userTimeRemaining)]) }}</p>
      <p v-else class="text-fg-muted text-xs">{{ $strings.LabelFinished }} {{ $formatDate(userProgressFinishedAt) }}</p>
    </div>

    <!-- action buttons -->
    <div class="flex mt-4 -mx-1">
      <ui-btn color="success" class="flex items-center justify-center flex-grow mx-1" :loading="playerIsStartingForThisMedia" :padding-x="4" @click="playClick">
        <span class="material-icons">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
        <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : localEpisodeId ? $strings.ButtonPlay : $strings.ButtonStream }}</span>
      </ui-btn>
      <ui-btn v-if="showDownload" :color="downloadItem ? 'warning' : 'primary'" class="flex items-center justify-center mx-1" :padding-x="2" @click="downloadClick">
        <span class="material-icons" :class="(downloadItem || startingDownload) ? 'animate-pulse' : ''">{{ (downloadItem || startingDownload) ? 'downloading' : 'download' }}</span>
      </ui-btn>
      <ui-btn color="primary" class="flex items-center justify-center mx-1" :padding-x="2" @click="showMoreMenu = true">
        <span class="material-icons">more_vert</span>
      </ui-btn>
    </div>

    <p class="text-sm text-fg mt-1.5 mb-0.5 default-style description-container" v-html="transformedDescription"></p>

    <!-- loading overlay -->
    <div v-if="processing" class="absolute top-0 left-0 w-full h-full bg-black bg-opacity-30 flex items-center justify-center">
      <widgets-loading-spinner size="la-lg" />
    </div>

    <modals-dialog v-model="showMoreMenu" :items="moreMenuItems" @action="moreMenuAction" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

export default {
  async asyncData({ store, params, redirect, app }) {
    const libraryItemId = params.id
    const episodeId = params.episode

    let libraryItem = null
    let episode = null
    let localEpisode = null

    if (libraryItemId.startsWith('local')) {
      libraryItem = await app.$db.getLocalLibraryItem(libraryItemId)
      console.log('Got lli', libraryItemId)
    } else if (store.state.user.serverConnectionConfig) {
      libraryItem = await app.$nativeHttp.get(`/api/items/${libraryItemId}?expanded=1`).catch((error) => {
        console.error('Failed', error)
        return false
      })

      if (libraryItem) {
        const localLibraryItem = await app.$db.getLocalLibraryItemByLId(libraryItemId)
        if (localLibraryItem) {
          console.log('Library item has local library item also', localLibraryItem.id)
          libraryItem.localLibraryItem = localLibraryItem

          localEpisode = localLibraryItem.media.episodes.find((ep) => ep.serverEpisodeId === episodeId)
        }
      }
    }

    if (!libraryItem) {
      console.error('No item...', params.id)
      return redirect('/')
    }

    episode = libraryItem.media.episodes.find((ep) => ep.id === episodeId)
    if (localEpisode) {
      // For server episodes that have a matching local episode
      episode.localEpisode = localEpisode
    }

    if (!episode) {
      console.error('No episode...', params.episode)
      return redirect(`/item/${libraryItemId}`)
    }

    return {
      libraryItem,
      episode
    }
  },
  data() {
    return {
      showMoreMenu: false,
      processing: false,
      resettingProgress: false,
      startingDownload: false
    }
  },
  mixins: [cellularPermissionHelpers],
  computed: {
    transformedDescription() {
      return this.parseDescription(this.description)
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    isAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    isIos() {
      return this.$platform === 'ios'
    },
    mediaType() {
      return 'podcast'
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    isLocal() {
      return this.libraryItem.isLocal
    },
    localLibraryItem() {
      if (this.isLocal) return this.libraryItem
      return this.libraryItem.localLibraryItem
    },
    libraryItemId() {
      return this.libraryItem.id
    },
    isConnectedToServer() {
      if (!this.isLocal) return true
      if (!this.libraryItem.serverAddress) return false
      return this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress
    },
    serverLibraryItemId() {
      if (!this.isLocal) return this.libraryItemId
      // Check if local library item is connected to the current server
      if (!this.libraryItem.libraryItemId) return null
      if (this.isConnectedToServer) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    localLibraryItemId() {
      return this.localLibraryItem?.id || null
    },
    localEpisode() {
      if (this.isLocal) return this.episode
      return this.episode.localEpisode
    },
    localEpisodeId() {
      if (this.localEpisode) return this.localEpisode.id
      return null
    },
    serverEpisodeId() {
      if (!this.isLocal) return this.episode.id
      if (this.isConnectedToServer) {
        return this.episode.serverEpisodeId
      }
      return null
    },
    podcast() {
      return this.libraryItem.media
    },
    audioFile() {
      return this.episode.audioFile
    },
    title() {
      return this.episode.title || ''
    },
    description() {
      return this.episode.description || ''
    },
    episodeNumber() {
      return this.episode.episode
    },
    season() {
      return this.episode.season
    },
    episodeType() {
      if (this.episode.episodeType === 'full') return null // only show Trailer/Bonus
      return this.episode.episodeType
    },
    duration() {
      return this.$secondsToTimestamp(this.episode.duration)
    },
    coverUrl() {
      if (this.isLocal) {
        if (!this.libraryItem.coverContentUrl) return '/book_placeholder.jpg'
        return Capacitor.convertFileSrc(this.libraryItem.coverContentUrl)
      }
      return this.$store.getters['globals/getLibraryItemCoverSrcById'](this.libraryItemId)
    },
    isPlaying() {
      return this.$store.getters['getIsMediaStreaming'](this.libraryItemId, this.episode.id)
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isPlaying
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      if (!mediaId) return false

      return mediaId === this.localEpisodeId || mediaId === this.serverEpisodeId
    },
    userItemProgress() {
      if (this.isLocal) return this.localItemProgress
      return this.serverItemProgress
    },
    localItemProgress() {
      if (!this.localLibraryItemId || !this.localEpisodeId) return null
      return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId, this.localEpisodeId)
    },
    serverItemProgress() {
      if (!this.serverLibraryItemId || !this.serverEpisodeId) return null
      return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId, this.serverEpisodeId)
    },
    progressPercent() {
      return this.userItemProgress?.progress || 0
    },
    userIsFinished() {
      return !!this.userItemProgress?.isFinished
    },
    userProgressFinishedAt() {
      return this.userItemProgress?.finishedAt || 0
    },
    userTimeRemaining() {
      if (!this.userItemProgress) return 0
      const duration = this.userItemProgress.duration || this.episode.duration
      return duration - this.userItemProgress.currentTime
    },
    timeRemaining() {
      if (this.playerIsPlaying) return 'Playing'
      if (!this.progressPercent) return this.$elapsedPretty(this.episode.duration)
      if (this.userIsFinished) return 'Finished'
      const remaining = Math.floor(this.userItemProgress.duration - this.userItemProgress.currentTime)
      return `${this.$elapsedPretty(remaining)} left`
    },
    publishedAt() {
      return this.episode.publishedAt
    },
    downloadItem() {
      return this.$store.getters['globals/getDownloadItem'](this.libraryItemId, this.episode.id)
    },
    showDownload() {
      return this.userCanDownload && !this.localEpisode
    },
    moreMenuItems() {
      const items = []

      if (!this.userIsFinished) {
        items.push({
          text: this.$strings.MessageMarkAsFinished,
          value: 'markFinished',
          icon: 'beenhere'
        })
      }

      if (this.progressPercent > 0) {
        items.push({
          text: this.$strings.MessageDiscardProgress,
          value: 'discardProgress',
          icon: 'backspace'
        })
      }

      if (!this.isLocal) {
        items.push({
          text: this.$strings.LabelAddToPlaylist,
          value: 'playlist',
          icon: 'playlist_add'
        })
      }

      if (this.localEpisodeId) {
        items.push({
          text: this.$strings.ButtonDeleteLocalEpisode,
          value: 'deleteLocal',
          icon: 'delete'
        })
      }

      if (this.isAdminOrUp && this.serverEpisodeId) {
        items.push({
          text: this.$strings.ButtonRemoveFromServer,
          value: 'remove_from_server',
          icon: 'delete_forever'
        })
      }

      return items
    }
  },
  methods: {
    parseDescription(description) {
      const timeMarkerLinkRegex = /<a href="#([^"]*?\b\d{1,2}:\d{1,2}(?::\d{1,2})?)">(.*?)<\/a>/g
      const timeMarkerRegex = /\b\d{1,2}:\d{1,2}(?::\d{1,2})?\b/g

      function convertToSeconds(time) {
        const timeParts = time.split(':').map(Number)
        return timeParts.reduce((acc, part, index) => acc * 60 + part, 0)
      }

      return description.replace(timeMarkerLinkRegex, (match, href, displayTime) => {
        const time = displayTime.match(timeMarkerRegex)[0]
        const seekTimeInSeconds = convertToSeconds(time)
        return `<span class="time-marker cursor-pointer text-blue-400 hover:text-blue-300" data-time="${seekTimeInSeconds}">${displayTime}</span>`
      }).replace(timeMarkerRegex, (match) => {
        const seekTimeInSeconds = convertToSeconds(match)
        return `<span class="time-marker cursor-pointer text-blue-400 hover:text-blue-300" data-time="${seekTimeInSeconds}">${match}</span>`
      })
    },
    async deleteLocalEpisode() {
      await this.$hapticsImpact()

      const localEpisodeAudioTrack = this.localEpisode.audioTrack
      const localFile = this.localLibraryItem.localFiles.find((lf) => lf.id === localEpisodeAudioTrack.localFileId)
      if (!localFile) {
        this.$toast.error('Audio track does not have matching local file..')
        return
      }

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: this.$getString('MessageConfirmDeleteLocalEpisode', [localFile.basePath])
      })
      if (value) {
        const res = await AbsFileSystem.deleteTrackFromItem({ id: this.localLibraryItemId, trackLocalFileId: localFile.id, trackContentUrl: localEpisodeAudioTrack.contentUrl })
        if (res?.id) {
          if (this.isLocal) {
            // If this is local episode then redirect to server episode when available
            if (this.serverEpisodeId) {
              this.$router.replace(`/item/${this.serverLibraryItemId}/${this.serverEpisodeId}`)
            } else {
              this.$router.replace(`/item/${this.localLibraryItemId}`)
            }
          } else {
            // Update local library item and local episode
            this.libraryItem.localLibraryItem = res
            this.$delete(this.episode, 'localEpisode')
          }
        } else this.$toast.error('Failed to delete')
      }
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return

      await this.$hapticsImpact()
      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.$store.commit('setPlayerIsStartingPlayback', this.episode.id)

        const playbackData = this.generatePlaybackData()
        this.emitPlayItemEvent(playbackData)
      }
    },
    async clickPlaybackTime(event) {
      const startTime = event.target.getAttribute('data-time')
      if (this.playerIsStartingPlayback) return

      await this.$hapticsImpact()

      this.$store.commit('setPlayerIsStartingPlayback', this.episode.id)

      const playbackData = this.generatePlaybackData(startTime)
      this.emitPlayItemEvent(playbackData)
    },
    generatePlaybackData(startTime) {
      const playbackData = {
        libraryItemId: this.libraryItemId,
        episodeId: this.episode.id,
        serverLibraryItemId: this.serverLibraryItemId,
        serverEpisodeId: this.serverEpisodeId,
        startTime
      }

      if (this.localEpisodeId && this.localLibraryItemId && !this.isLocal) {
        playbackData.libraryItemId = this.localLibraryItemId
        playbackData.episodeId = this.localEpisodeId
      }

      return playbackData
    },
    emitPlayItemEvent(playbackData) {
      this.$eventBus.$emit('play-item', playbackData)
    },
    bindTimeMarkerEvents() {
      const container = document.querySelector('.description-container')
      if (container) {
        container.addEventListener('click', (event) => {
          if (event.target.classList.contains('time-marker')) {
            this.clickPlaybackTime(event)
          }
        })
      }
    },
    async downloadClick() {
      if (this.downloadItem || this.startingDownload) return

      const hasPermission = await this.checkCellularPermission('download')
      if (!hasPermission) return

      this.startingDownload = true
      setTimeout(() => {
        this.startingDownload = false
      }, 1000)

      await this.$hapticsImpact()
      if (this.isIos) {
        // no local folders on iOS
        this.startDownload()
      } else {
        this.download()
      }
    },
    async download(selectedLocalFolder = null) {
      let localFolder = selectedLocalFolder
      if (!localFolder) {
        const localFolders = (await this.$db.getLocalFolders()) || []
        console.log('Local folders loaded', localFolders.length)
        const foldersWithMediaType = localFolders.filter((lf) => {
          console.log('Checking local folder', lf.mediaType)
          return lf.mediaType == this.mediaType
        })
        console.log('Folders with media type', this.mediaType, foldersWithMediaType.length)
        const internalStorageFolder = foldersWithMediaType.find((f) => f.id === `internal-${this.mediaType}`)
        if (!foldersWithMediaType.length) {
          localFolder = {
            id: `internal-${this.mediaType}`,
            name: this.$strings.LabelInternalAppStorage,
            mediaType: this.mediaType
          }
        } else if (foldersWithMediaType.length === 1 && internalStorageFolder) {
          localFolder = internalStorageFolder
        } else {
          this.$store.commit('globals/showSelectLocalFolderModal', {
            mediaType: this.mediaType,
            callback: (folder) => {
              this.download(folder)
            }
          })
          return
        }
      }

      console.log('Local folder', JSON.stringify(localFolder))

      this.startDownload(localFolder)
    },
    async selectFolder() {
      const folderObj = await AbsFileSystem.selectFolder({ mediaType: this.mediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }
      return folderObj
    },
    async startDownload(localFolder) {
      const payload = {
        libraryItemId: this.libraryItemId,
        episodeId: this.episode.id
      }
      if (localFolder) {
        payload.localFolderId = localFolder.id
      }
      const downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        const errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    },
    moreMenuAction(action) {
      this.showMoreMenu = false
      if (action === 'markFinished') {
        this.toggleFinished()
      } else if (action === 'discardProgress') {
        this.discardProgress()
      } else if (action === 'playlist' && !this.isLocal) {
        this.$store.commit('globals/setSelectedPlaylistItems', [{ libraryItem: this.libraryItem, episode: this.episode }])
        this.$store.commit('globals/setShowPlaylistsAddCreateModal', true)
      } else if (action === 'remove_from_server' && this.serverEpisodeId && this.isAdminOrUp) {
        this.deleteEpisodeFromServerClick()
      } else if (action === 'deleteLocal') {
        this.deleteLocalEpisode()
      }
    },
    async discardProgress() {
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: this.$strings.MessageConfirmDiscardProgress
      })
      if (value) {
        this.resettingProgress = true

        const serverItemProgressId = this.serverItemProgress?.id
        if (this.localItemProgress) {
          await this.$db.removeLocalMediaProgress(this.localItemProgress.id)
          this.$store.commit('globals/removeLocalMediaProgress', this.localItemProgress.id)
        }

        if (serverItemProgressId) {
          await this.$nativeHttp
            .delete(`/api/me/progress/${serverItemProgressId}`)
            .then(() => {
              console.log('Progress reset complete')
              this.$toast.success(`Your progress was reset`)
              this.$store.commit('user/removeMediaProgress', serverItemProgressId)
            })
            .catch((error) => {
              console.error('Progress reset failed', error)
            })
        }

        this.resettingProgress = false
      }
    },
    async toggleFinished() {
      await this.$hapticsImpact()

      if (this.isLocal || this.localEpisode) {
        const isFinished = !this.userIsFinished
        const localLibraryItemId = this.localLibraryItemId
        const localEpisodeId = this.localEpisodeId
        const payload = await this.$db.updateLocalMediaProgressFinished({ localLibraryItemId, localEpisodeId, isFinished })
        console.log('toggleFinished payload', JSON.stringify(payload))

        if (payload?.error) {
          this.$toast.error(payload?.error || 'Unknown error')
        } else {
          const localMediaProgress = payload.localMediaProgress
          console.log('toggleFinished localMediaProgress', JSON.stringify(localMediaProgress))
          if (localMediaProgress) {
            this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
          }
        }
      } else {
        const updatePayload = {
          isFinished: !this.userIsFinished
        }
        this.$nativeHttp.patch(`/api/me/progress/${this.libraryItemId}/${this.episode.id}`, updatePayload).catch((error) => {
          console.error('Failed', error)
          this.$toast.error(updatePayload.isFinished ? this.$strings.ToastItemMarkedAsFinishedFailed : this.$strings.ToastItemMarkedAsNotFinishedFailed)
        })
      }
    },
    async deleteEpisodeFromServerClick() {
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Are you sure you want to delete episode "${this.title}" from the server?\nWarning: This will delete the audio file.`
      })

      if (value) {
        this.processing = true
        this.$nativeHttp
          .delete(`/api/podcasts/${this.serverLibraryItemId}/episode/${this.serverEpisodeId}?hard=1`)
          .then(() => {
            this.$router.replace(`/item/${this.serverLibraryItemId}`)
          })
          .catch((error) => {
            const errorMsg = error.response?.data || 'Failed to delete episode'
            console.error('Failed to delete episode', error)
            this.$toast.error(errorMsg)
          })
          .finally(() => {
            this.processing = false
          })
      }
    },
    newLocalLibraryItem(item) {
      if (item.libraryItemId == this.libraryItemId) {
        console.log('New local library item', item.id)
        this.$set(this.libraryItem, 'localLibraryItem', item)

        const episode = item.media.episodes.find((ep) => ep.serverEpisodeId === this.episode.id)
        if (episode) {
          this.$set(this.episode, 'localEpisode', episode)
        }
      }
    }
  },
  mounted() {
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.bindTimeMarkerEvents()
  },
  beforeDestroy() {
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    document.querySelectorAll('.time-marker').forEach(marker => {
      marker.removeEventListener('click', this.clickPlaybackTime)
    })
  }
}
</script>
