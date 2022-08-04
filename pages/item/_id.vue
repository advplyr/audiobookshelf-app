<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto">
    <div class="flex">
      <div class="w-16">
        <div class="relative" @click="showFullscreenCover = true">
          <covers-book-cover :library-item="libraryItem" :width="64" :book-cover-aspect-ratio="bookCoverAspectRatio" />
          <div v-if="!isPodcast" class="absolute bottom-0 left-0 h-1 shadow-sm z-10" :class="userIsFinished ? 'bg-success' : 'bg-yellow-400'" :style="{ width: 64 * progressPercent + 'px' }"></div>
        </div>
      </div>
      <div class="title-container flex-grow pl-2">
        <div class="flex relative pr-6">
          <h1 class="text-base">{{ title }}</h1>

          <button class="absolute top-0 right-0 h-full px-1 outline-none" @click="moreButtonPress">
            <span class="material-icons text-xl">more_vert</span>
          </button>
        </div>
        <p v-if="seriesList && seriesList.length" class="text-sm text-gray-300 py-0.5">
          <template v-for="(series, index) in seriesList"
            ><nuxt-link :key="series.id" :to="`/bookshelf/series/${series.id}`">{{ series.text }}</nuxt-link
            ><span :key="`${series.id}-comma`" v-if="index < seriesList.length - 1">,&nbsp;</span></template
          >
        </p>
        <p v-if="podcastAuthor" class="text-sm text-gray-400 py-0.5">By {{ podcastAuthor }}</p>
        <p v-else-if="bookAuthors && bookAuthors.length" class="text-sm text-gray-400 py-0.5">
          By
          <template v-for="(author, index) in bookAuthors"
            ><nuxt-link :key="author.id" :to="`/bookshelf/library?filter=authors.${$encode(author.id)}`">{{ author.name }}</nuxt-link
            ><span :key="`${author.id}-comma`" v-if="index < bookAuthors.length - 1">,&nbsp;</span></template
          >
        </p>
      </div>
    </div>

    <p v-if="narrators && narrators.length" class="text-sm text-gray-400 py-0.5">
      Narrated By
      <template v-for="(narrator, index) in narrators"
        ><nuxt-link :key="narrator" :to="`/bookshelf/library?filter=narrators.${$encode(narrator)}`">{{ narrator }}</nuxt-link
        ><span :key="`${narrator}-comma`" v-if="index < narrators.length - 1">,&nbsp;</span></template
      >
    </p>

    <!-- Show an indicator for local library items whether they are linked to a server item and if that server item is connected -->
    <p v-if="isLocal && serverLibraryItemId" style="font-size: 10px" class="text-success py-1 uppercase tracking-widest">connected</p>
    <p v-else-if="isLocal && libraryItem.serverAddress" style="font-size: 10px" class="text-gray-400 py-1">{{ libraryItem.serverAddress }}</p>

    <div v-if="numTracks" class="flex text-gray-100 text-xs my-2 -mx-0.5">
      <div class="bg-primary bg-opacity-80 px-3 py-0.5 rounded-full mx-0.5">
        <p>{{ $elapsedPretty(duration) }}</p>
      </div>
      <!-- TODO: Local books dont save the size -->
      <div v-if="size" class="bg-primary bg-opacity-80 px-3 py-0.5 rounded-full mx-0.5">
        <p>{{ $bytesPretty(size) }}</p>
      </div>
      <div class="bg-primary bg-opacity-80 px-3 py-0.5 rounded-full mx-0.5">
        <p>{{ numTracks }} Track{{ numTracks > 1 ? 's' : '' }}</p>
      </div>
      <div v-if="numChapters" class="bg-primary bg-opacity-80 px-3 py-0.5 rounded-full mx-0.5">
        <p>{{ numChapters }} Chapter{{ numChapters > 1 ? 's' : '' }}</p>
      </div>
    </div>

    <div>
      <div v-if="!isPodcast && progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-gray-200 mt-4 relative" :class="resettingProgress ? 'opacity-25' : ''">
        <p class="leading-6">Your Progress: {{ Math.round(progressPercent * 100) }}%</p>
        <p v-if="progressPercent < 1" class="text-gray-400 text-xs">{{ $elapsedPretty(userTimeRemaining) }} remaining</p>
        <p v-else class="text-gray-400 text-xs">Finished {{ $formatDate(userProgressFinishedAt) }}</p>
        <div v-if="!resettingProgress" class="absolute -top-1.5 -right-1.5 p-1 w-5 h-5 rounded-full bg-bg hover:bg-error border border-primary flex items-center justify-center cursor-pointer" @click.stop="clearProgressClick">
          <span class="material-icons text-sm">close</span>
        </div>
      </div>

      <div v-if="isLocal" class="flex mt-4">
        <ui-btn v-if="showPlay" color="success" :disabled="isPlaying" class="flex items-center justify-center flex-grow mr-2" :padding-x="4" @click="playClick">
          <span v-show="!isPlaying" class="material-icons">play_arrow</span>
          <span class="px-1 text-sm">{{ isPlaying ? 'Playing' : 'Play' }}</span>
        </ui-btn>
        <ui-btn v-if="showRead" color="info" class="flex items-center justify-center mr-2" :class="showPlay ? '' : 'flex-grow'" :padding-x="2" @click="readBook">
          <span class="material-icons">auto_stories</span>
          <span v-if="!showPlay" class="px-2 text-base">Read {{ ebookFormat }}</span>
        </ui-btn>
        <ui-read-icon-btn v-if="!isPodcast" :disabled="isProcessingReadUpdate" :is-read="userIsFinished" class="flex items-center justify-center" @click="toggleFinished" />
      </div>
      <div v-else-if="(user && (showPlay || showRead)) || hasLocal" class="flex mt-4">
        <ui-btn v-if="showPlay" color="success" :disabled="isPlaying" class="flex items-center justify-center flex-grow mr-2" :padding-x="4" @click="playClick">
          <span v-show="!isPlaying" class="material-icons">play_arrow</span>
          <span class="px-1 text-sm">{{ isPlaying ? (isStreaming ? 'Streaming' : 'Playing') : hasLocal ? 'Play' : 'Stream' }}</span>
        </ui-btn>
        <ui-btn v-if="showRead && user" color="info" class="flex items-center justify-center mr-2" :class="showPlay ? '' : 'flex-grow'" :padding-x="2" @click="readBook">
          <span class="material-icons">auto_stories</span>
          <span v-if="!showPlay" class="px-2 text-base">Read {{ ebookFormat }}</span>
        </ui-btn>
        <ui-btn v-if="showDownload" :color="downloadItem ? 'warning' : 'primary'" class="flex items-center justify-center mr-2" :padding-x="2" @click="downloadClick">
          <span class="material-icons" :class="downloadItem ? 'animate-pulse' : ''">{{ downloadItem ? 'downloading' : 'download' }}</span>
        </ui-btn>
        <ui-read-icon-btn v-if="!isPodcast" :disabled="isProcessingReadUpdate" :is-read="userIsFinished" class="flex items-center justify-center" @click="toggleFinished" />
      </div>
    </div>

    <div v-if="downloadItem" class="py-3">
      <p class="text-center text-lg">Downloading! ({{ Math.round(downloadItem.itemProgress * 100) }}%)</p>
    </div>

    <div class="w-full py-4">
      <p class="text-sm">{{ description }}</p>
    </div>

    <tables-podcast-episodes-table v-if="isPodcast" :library-item-id="libraryItemId" :local-library-item-id="localLibraryItemId" :episodes="episodes" :local-episodes="localLibraryItemEpisodes" :is-local="isLocal" />

    <modals-select-local-folder-modal v-model="showSelectLocalFolder" :media-type="mediaType" @select="selectedLocalFolder" />

    <modals-dialog v-model="showMoreMenu" title="" :items="moreMenuItems" @action="moreMenuAction" />

    <modals-item-details-modal v-model="showDetailsModal" :library-item="libraryItem" />

    <modals-fullscreen-cover v-model="showFullscreenCover" :library-item="libraryItem" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'

export default {
  async asyncData({ store, params, redirect, app }) {
    var libraryItemId = params.id
    var libraryItem = null
    console.log(libraryItemId)
    if (libraryItemId.startsWith('local')) {
      libraryItem = await app.$db.getLocalLibraryItem(libraryItemId)
      console.log('Got lli', libraryItemId)
    } else if (store.state.user.serverConnectionConfig) {
      libraryItem = await app.$axios.$get(`/api/items/${libraryItemId}?expanded=1`).catch((error) => {
        console.error('Failed', error)
        return false
      })
      // Check if
      if (libraryItem) {
        var localLibraryItem = await app.$db.getLocalLibraryItemByLId(libraryItemId)
        if (localLibraryItem) {
          console.log('Library item has local library item also', localLibraryItem.id)
          libraryItem.localLibraryItem = localLibraryItem
        }
      }
    }

    if (!libraryItem) {
      console.error('No item...', params.id)
      return redirect('/')
    }
    return {
      libraryItem
    }
  },
  data() {
    return {
      resettingProgress: false,
      isProcessingReadUpdate: false,
      showSelectLocalFolder: false,
      showMoreMenu: false,
      showDetailsModal: false,
      showFullscreenCover: false
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    isLocal() {
      return this.libraryItem.isLocal
    },
    hasLocal() {
      // Server library item has matching local library item
      return this.isLocal || this.libraryItem.localLibraryItem
    },
    localLibraryItem() {
      if (this.isLocal) return this.libraryItem
      return this.libraryItem.localLibraryItem || null
    },
    localLibraryItemId() {
      return this.localLibraryItem ? this.localLibraryItem.id : null
    },
    localLibraryItemEpisodes() {
      if (!this.isPodcast || !this.localLibraryItem) return []
      var podcastMedia = this.localLibraryItem.media
      return podcastMedia ? podcastMedia.episodes || [] : []
    },
    serverLibraryItemId() {
      if (!this.isLocal) return this.libraryItem.id
      // Check if local library item is connected to the current server
      if (!this.libraryItem.serverAddress || !this.libraryItem.libraryItemId) return null
      if (this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    libraryItemId() {
      return this.libraryItem.id
    },
    mediaType() {
      return this.libraryItem.mediaType
    },
    isPodcast() {
      return this.mediaType == 'podcast'
    },
    media() {
      return this.libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    title() {
      return this.mediaMetadata.title
    },
    podcastAuthor() {
      if (!this.isPodcast) return null
      return this.mediaMetadata.author || ''
    },
    bookAuthors() {
      if (this.isPodcast) return null
      return this.mediaMetadata.authors || []
    },
    narrators() {
      if (this.isPodcast) return null
      return this.mediaMetadata.narrators || []
    },
    description() {
      return this.mediaMetadata.description || ''
    },
    series() {
      return this.mediaMetadata.series || []
    },
    seriesList() {
      if (this.isPodcast) return null
      return this.series.map((se) => {
        var text = se.name
        if (se.sequence) text += ` #${se.sequence}`
        return {
          ...se,
          text
        }
      })
    },
    duration() {
      return this.media.duration
    },
    size() {
      return this.media.size
    },
    user() {
      return this.$store.state.user.user
    },
    userToken() {
      return this.$store.getters['user/getToken']
    },
    userItemProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId)
    },
    userIsFinished() {
      return this.userItemProgress ? !!this.userItemProgress.isFinished : false
    },
    userTimeRemaining() {
      if (!this.userItemProgress) return 0
      var duration = this.userItemProgress.duration || this.duration
      return duration - this.userItemProgress.currentTime
    },
    progressPercent() {
      return this.userItemProgress ? Math.max(Math.min(1, this.userItemProgress.progress), 0) : 0
    },
    userProgressStartedAt() {
      return this.userItemProgress ? this.userItemProgress.startedAt : 0
    },
    userProgressFinishedAt() {
      return this.userItemProgress ? this.userItemProgress.finishedAt : 0
    },
    isStreaming() {
      return this.isPlaying && !this.$store.state.playerIsLocal
    },
    isPlaying() {
      return this.$store.getters['getIsItemStreaming'](this.libraryItemId)
    },
    numTracks() {
      if (!this.media.tracks) return 0
      return this.media.tracks.length || 0
    },
    numChapters() {
      if (!this.media.chapters) return 0
      return this.media.chapters.length || 0
    },
    isMissing() {
      return this.libraryItem.isMissing
    },
    isIncomplete() {
      return this.libraryItem.isIncomplete
    },
    showPlay() {
      return !this.isMissing && !this.isIncomplete && this.numTracks
    },
    showRead() {
      return this.ebookFile && this.ebookFormat !== 'pdf'
    },
    showDownload() {
      if (this.isIos) return false
      return this.user && this.userCanDownload && this.showPlay && !this.hasLocal
    },
    ebookFile() {
      return this.media.ebookFile
    },
    ebookFormat() {
      if (!this.ebookFile) return null
      return this.ebookFile.ebookFormat
    },
    downloadItem() {
      return this.$store.getters['globals/getDownloadItem'](this.libraryItemId)
    },
    episodes() {
      return this.media.episodes || []
    },
    isCasting() {
      return this.$store.state.isCasting
    },
    moreMenuItems() {
      if (this.isLocal) {
        return [
          {
            text: 'Manage Local Files',
            value: 'manageLocal'
          },
          {
            text: 'View Details',
            value: 'details'
          }
        ]
      } else {
        return [
          {
            text: 'View Details',
            value: 'details'
          }
        ]
      }
    }
  },
  methods: {
    moreMenuAction(action) {
      this.showMoreMenu = false
      if (action === 'manageLocal') {
        this.$router.push(`/localMedia/item/${this.libraryItemId}`)
      } else if (action === 'details') {
        this.showDetailsModal = true
      }
    },
    moreButtonPress() {
      this.showMoreMenu = true
    },
    readBook() {
      this.$store.commit('openReader', this.libraryItem)
    },
    playClick() {
      // Todo: Allow playing local or streaming
      if (this.hasLocal && this.serverLibraryItemId && this.isCasting) {
        // If casting and connected to server for local library item then send server library item id
        this.$eventBus.$emit('play-item', { libraryItemId: this.serverLibraryItemId })
        return
      }
      if (this.hasLocal) return this.$eventBus.$emit('play-item', { libraryItemId: this.localLibraryItem.id, serverLibraryItemId: this.serverLibraryItemId })
      this.$eventBus.$emit('play-item', { libraryItemId: this.libraryItemId })
    },
    async clearProgressClick() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Are you sure you want to reset your progress?'
      })
      if (value) {
        this.resettingProgress = true
        if (this.isLocal) {
          // TODO: If connected to server also sync with server
          await this.$db.removeLocalMediaProgress(this.libraryItemId)
          this.$store.commit('globals/removeLocalMediaProgressForItem', this.libraryItemId)
        } else {
          var progressId = this.userItemProgress.id
          await this.$axios
            .$delete(`/api/me/progress/${this.libraryItemId}`)
            .then(() => {
              console.log('Progress reset complete')
              this.$toast.success(`Your progress was reset`)
              this.$store.commit('user/removeMediaProgress', progressId)
            })
            .catch((error) => {
              console.error('Progress reset failed', error)
            })
        }

        this.resettingProgress = false
      }
    },
    itemUpdated(libraryItem) {
      if (libraryItem.id === this.libraryItemId) {
        console.log('Item Updated')
        this.libraryItem = libraryItem
      }
    },
    async selectFolder() {
      // Select and save the local folder for media type
      var folderObj = await AbsFileSystem.selectFolder({ mediaType: this.mediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }
      return folderObj
    },
    selectedLocalFolder(localFolder) {
      this.showSelectLocalFolder = false
      this.download(localFolder)
    },
    downloadClick() {
      if (this.downloadItem) {
        return
      }
      if (!this.numTracks) {
        return
      }
      if (this.isIos) {
        // no local folders on iOS
        this.startDownload()
      } else {
        this.download()
      }
    },
    async download(selectedLocalFolder = null) {
      // Get the local folder to download to
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
          this.showSelectLocalFolder = true
          return
        }
        if (!localFolder) {
          return this.$toast.error('Invalid download folder')
        }
      }

      console.log('Local folder', JSON.stringify(localFolder))

      var startDownloadMessage = `Start download for "${this.title}" with ${this.numTracks} audio track${this.numTracks == 1 ? '' : 's'} to folder ${localFolder.name}?`
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: startDownloadMessage
      })
      if (value) {
        this.startDownload(localFolder)
      }
    },
    async startDownload(localFolder = null) {
      const payload = {
        libraryItemId: this.libraryItemId
      }
      if (localFolder) {
        console.log('Starting download to local folder', localFolder.name)
        payload.localFolderId = localFolder.id
      }
      var downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        var errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    },
    newLocalLibraryItem(item) {
      if (item.libraryItemId == this.libraryItemId) {
        console.log('New local library item', item.id)
        this.$set(this.libraryItem, 'localLibraryItem', item)
      }
    },
    async toggleFinished() {
      this.isProcessingReadUpdate = true
      if (this.isLocal) {
        var isFinished = !this.userIsFinished
        var payload = await this.$db.updateLocalMediaProgressFinished({ localLibraryItemId: this.localLibraryItemId, isFinished })
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

          var lmp = this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId)
          console.log('toggleFinished Check LMP', this.libraryItemId, JSON.stringify(lmp))

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
          .$patch(`/api/me/progress/${this.libraryItemId}`, updatePayload)
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
  },
  mounted() {
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.on('item_updated', this.itemUpdated)
  },
  beforeDestroy() {
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.off('item_updated', this.itemUpdated)
  }
}
</script>

<style>
.title-container {
  width: calc(100% - 64px);
  max-width: calc(100% - 64px);
}
</style>