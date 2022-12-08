<template>
  <div class="w-full px-0 py-4 overflow-hidden relative border-b border-white border-opacity-10">
    <div v-if="episode" class="w-full px-1">
      <!-- Help debug for testing -->
      <!-- <template>
        <p class="text-xs mb-1">{{ isLocal ? 'LOCAL' : 'NOT LOCAL' }}</p>
        <p class="text-xs mb-4">Lid:{{ libraryItemId }}<br />Eid:{{ episode.id }}<br />LLid:{{ localLibraryItemId }}<br />LEid:{{ localEpisodeId }}</p>
        <p v-if="itemProgress">Server Media Progress {{ Math.round(itemProgress.progress * 100) }}</p>
        <p v-else>No Server Media Progress</p>
        <p v-if="localMediaProgress">Local Media Progress {{ Math.round(localMediaProgress.progress * 100) }}</p>
        <p v-else>No Local Media Progress</p>
      </template> -->

      <p v-if="publishedAt" class="text-xs text-gray-400 mb-1">Published {{ $formatDate(publishedAt, 'MMM do, yyyy') }}</p>

      <p class="text-sm font-semibold">
        {{ title }}
      </p>
      <p class="text-sm text-gray-200 episode-subtitle mt-1.5 mb-0.5 default-style" v-html="description" />

      <div class="flex items-center pt-2">
        <div class="h-8 px-4 border border-white border-opacity-20 hover:bg-white hover:bg-opacity-10 rounded-full flex items-center justify-center cursor-pointer" :class="userIsFinished ? 'text-white text-opacity-40' : ''" @click="playClick">
          <span class="material-icons" :class="streamIsPlaying ? '' : 'text-success'">{{ streamIsPlaying ? 'pause' : 'play_arrow' }}</span>
          <p class="pl-2 pr-1 text-sm font-semibold">{{ timeRemaining }}</p>
        </div>

        <ui-read-icon-btn :disabled="isProcessingReadUpdate" :is-read="userIsFinished" borderless class="mx-1 mt-0.5" @click="toggleFinished" />

        <button v-if="!isLocal" class="mx-1.5 mt-1.5" @click="addToPlaylist">
          <span class="material-icons text-2xl">playlist_add</span>
        </button>

        <div v-if="userCanDownload">
          <span v-if="isLocal" class="material-icons-outlined px-2 text-success text-lg">audio_file</span>
          <span v-else-if="!localEpisode" class="material-icons mx-1 mt-2" :class="downloadItem ? 'animate-bounce text-warning text-opacity-75 text-xl' : 'text-gray-300 text-xl'" @click="downloadClick">{{ downloadItem ? 'downloading' : 'download' }}</span>
          <span v-else class="material-icons px-2 text-success text-xl">download_done</span>
        </div>
      </div>
    </div>

    <div v-if="!userIsFinished" class="absolute bottom-0 left-0 h-0.5 bg-warning" :style="{ width: itemProgressPercent * 100 + '%' }" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'
import { Haptics, ImpactStyle } from '@capacitor/haptics';

export default {
  props: {
    libraryItemId: String,
    episode: {
      type: Object,
      default: () => {}
    },
    localLibraryItemId: String,
    localEpisode: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean
  },
  data() {
    return {
      isProcessingReadUpdate: false
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    mediaType() {
      return 'podcast'
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    audioFile() {
      return this.episode.audioFile
    },
    title() {
      return this.episode.title || ''
    },
    description() {
      if (this.episode.subtitle) return this.episode.subtitle
      var desc = this.episode.description || ''
      return desc
    },
    duration() {
      return this.$secondsToTimestamp(this.episode.duration)
    },
    isStreaming() {
      if (this.playerIsLocal && this.localLibraryItemId && this.localEpisode) {
        // Check is streaming local version of this episode
        return this.$store.getters['getIsEpisodeStreaming'](this.localLibraryItemId, this.localEpisode.id)
      }
      return this.$store.getters['getIsEpisodeStreaming'](this.libraryItemId, this.episode.id)
    },
    playerIsLocal() {
      return !!this.$store.state.playerIsLocal
    },
    streamIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isStreaming
    },
    itemProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, this.episode.id)
    },
    localMediaProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
      else if (this.localLibraryItemId && this.localEpisode) {
        return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId, this.localEpisode.id)
      } else {
        return null
      }
    },
    itemProgressPercent() {
      return this.itemProgress ? this.itemProgress.progress : 0
    },
    userIsFinished() {
      return this.itemProgress ? !!this.itemProgress.isFinished : false
    },
    timeRemaining() {
      if (this.streamIsPlaying) return 'Playing'
      if (!this.itemProgressPercent) return this.$elapsedPretty(this.episode.duration)
      if (this.userIsFinished) return 'Finished'
      var remaining = Math.floor(this.itemProgress.duration - this.itemProgress.currentTime)
      return `${this.$elapsedPretty(remaining)} left`
    },
    publishedAt() {
      return this.episode.publishedAt
    },
    downloadItem() {
      return this.$store.getters['globals/getDownloadItem'](this.libraryItemId, this.episode.id)
    },
    localEpisodeId() {
      return this.localEpisode ? this.localEpisode.id : null
    }
  },
  methods: {
    addToPlaylist() {
      this.$emit('addToPlaylist', this.episode)
    },
    async selectFolder() {
      var folderObj = await AbsFileSystem.selectFolder({ mediaType: this.mediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }
      return folderObj
    },
    async downloadClick() {
      if (this.downloadItem) return
      await Haptics.impact({ style: ImpactStyle.Medium });
      if (this.isIos) {
        // no local folders on iOS
        this.startDownload()
      } else {
        this.download()
      }
    },
    async download(selectedLocalFolder = null) {
      var localFolder = selectedLocalFolder
      if (!localFolder) {
        var localFolders = (await this.$db.getLocalFolders()) || []
        console.log('Local folders loaded', localFolders.length)
        var foldersWithMediaType = localFolders.filter((lf) => {
          console.log('Checking local folder', lf.mediaType)
          return lf.mediaType == this.mediaType
        })
        console.log('Folders with media type', this.mediaType, foldersWithMediaType.length)
        if (!foldersWithMediaType.length) {
          // No local folders or no local folders with this media type
          localFolder = await this.selectFolder()
        } else if (foldersWithMediaType.length == 1) {
          console.log('Only 1 local folder with this media type - auto select it')
          localFolder = foldersWithMediaType[0]
        } else {
          console.log('Multiple folders with media type')
          // this.showSelectLocalFolder = true
          return
        }
        if (!localFolder) {
          return this.$toast.error('Invalid download folder')
        }
      }

      console.log('Local folder', JSON.stringify(localFolder))

      var startDownloadMessage = `Start download for "${this.title}" to folder ${localFolder.name}?`
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: startDownloadMessage
      })
      if (value) {
        this.startDownload(localFolder)
      }
    },
    async startDownload(localFolder) {
      var payload = {
        libraryItemId: this.libraryItemId,
        episodeId: this.episode.id
      }
      if (localFolder) {
        payload.localFolderId = localFolder.id
      }
      var downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        var errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    },
    async playClick() {
      await Haptics.impact({ style: ImpactStyle.Medium });
      if (this.streamIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        if (this.localEpisode && this.localLibraryItemId) {
          console.log('Play local episode', this.localEpisode.id, this.localLibraryItemId)

          this.$eventBus.$emit('play-item', {
            libraryItemId: this.localLibraryItemId,
            episodeId: this.localEpisode.id,
            serverLibraryItemId: this.libraryItemId,
            serverEpisodeId: this.episode.id
          })
        } else {
          this.$eventBus.$emit('play-item', {
            libraryItemId: this.libraryItemId,
            episodeId: this.episode.id
          })
        }
      }
    },
    async toggleFinished() {
      await Haptics.impact({ style: ImpactStyle.Medium });
      this.isProcessingReadUpdate = true
      if (this.isLocal || this.localEpisode) {
        var isFinished = !this.userIsFinished
        var localLibraryItemId = this.isLocal ? this.libraryItemId : this.localLibraryItemId
        var localEpisodeId = this.isLocal ? this.episode.id : this.localEpisode.id
        var payload = await this.$db.updateLocalMediaProgressFinished({ localLibraryItemId, localEpisodeId, isFinished })
        console.log('toggleFinished payload', JSON.stringify(payload))
        if (!payload || payload.error) {
          var errorMsg = payload ? payload.error : 'Unknown error'
          this.$toast.error(errorMsg)
        } else {
          var localMediaProgress = payload.localMediaProgress
          console.log('toggleFinished localMediaProgress', JSON.stringify(localMediaProgress))
          if (localMediaProgress) {
            this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
          }

          var lmp = this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
          console.log('toggleFinished Check LMP', this.libraryItemId, this.episode.id, JSON.stringify(lmp))

          var serverUpdated = payload.server
          if (serverUpdated) {
            this.$toast.success(`Local & Server Item marked as ${isFinished ? 'Finished' : 'Not Finished'}`)
          } else {
            this.$toast.success(`Local Item marked as ${isFinished ? 'Finished' : 'Not Finished'}`)
          }
        }
        this.isProcessingReadUpdate = false
      } else {
        var updatePayload = {
          isFinished: !this.userIsFinished
        }
        this.$axios
          .$patch(`/api/me/progress/${this.libraryItemId}/${this.episode.id}`, updatePayload)
          .then(() => {
            this.isProcessingReadUpdate = false
            this.$toast.success(`Item marked as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
          })
          .catch((error) => {
            console.error('Failed', error)
            this.isProcessingReadUpdate = false
            this.$toast.error(`Failed to mark as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
          })
      }
    }
  }
}
</script>
