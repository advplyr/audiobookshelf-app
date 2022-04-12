<template>
  <div class="w-full px-0 py-4 overflow-hidden relative border-b border-white border-opacity-10">
    <div v-if="episode" class="flex items-center">
      <!-- <div class="w-12 min-w-12 max-w-16 h-full">
        <div class="flex h-full items-center justify-center">
          <span class="material-icons drag-handle text-lg text-white text-opacity-50 hover:text-opacity-100">menu</span>
        </div>
      </div> -->
      <div class="flex-grow px-1">
        <p v-if="publishedAt" class="text-xs text-gray-400 mb-1">Published {{ $formatDate(publishedAt, 'MMM do, yyyy') }}</p>

        <p class="text-sm font-semibold">
          {{ title }}
        </p>
        <p class="text-sm text-gray-200 episode-subtitle mt-1.5 mb-0.5">
          {{ description }}
        </p>
        <div class="flex items-center pt-2">
          <div class="h-8 px-4 border border-white border-opacity-20 hover:bg-white hover:bg-opacity-10 rounded-full flex items-center justify-center cursor-pointer" :class="userIsFinished ? 'text-white text-opacity-40' : ''" @click="playClick">
            <span class="material-icons" :class="streamIsPlaying ? '' : 'text-success'">{{ streamIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <p class="pl-2 pr-1 text-sm font-semibold">{{ timeRemaining }}</p>
          </div>

          <ui-read-icon-btn :disabled="isProcessingReadUpdate" :is-read="userIsFinished" borderless class="mx-1 mt-0.5" @click="toggleFinished" />
          <span class="material-icons px-2" :class="downloadItem ? 'animate-bounce text-warning text-opacity-75 text-xl' : 'text-gray-300 text-xl'" @click="downloadClick">{{ downloadItem ? 'downloading' : 'download' }}</span>
        </div>
      </div>
    </div>

    <div v-if="!userIsFinished" class="absolute bottom-0 left-0 h-0.5 bg-warning" :style="{ width: itemProgressPercent * 100 + '%' }" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  props: {
    libraryItemId: String,
    isLocal: Boolean,
    episode: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {
      isProcessingReadUpdate: false
    }
  },
  computed: {
    mediaType() {
      return 'podcast'
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
      return this.$store.getters['getIsEpisodeStreaming'](this.libraryItemId, this.episode.id)
    },
    streamIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isStreaming
    },
    itemProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, this.episode.id)
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
    }
  },
  methods: {
    selectFolder() {
      this.$toast.error('Folder selector not implemented for podcasts yet')
    },
    downloadClick() {
      if (this.downloadItem) return
      this.download()
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
      var downloadRes = await AbsDownloader.downloadLibraryItem({ libraryItemId: this.libraryItemId, localFolderId: localFolder.id, episodeId: this.episode.id })
      if (downloadRes && downloadRes.error) {
        var errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    },
    playClick() {
      if (this.streamIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.$eventBus.$emit('play-item', {
          libraryItemId: this.libraryItemId,
          episodeId: this.episode.id
        })
      }
    },
    toggleFinished() {
      var updatePayload = {
        isFinished: !this.userIsFinished
      }
      this.isProcessingReadUpdate = true
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
  },
  mounted() {}
}
</script>