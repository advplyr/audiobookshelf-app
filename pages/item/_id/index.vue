<template>
  <div id="item-page" class="w-full h-full px-3 pb-4 overflow-y-auto overflow-x-hidden relative bg-bg">
    <div class="fixed top-0 left-0 w-full h-full pointer-events-none p-px z-10">
      <div class="w-full h-full" :style="{ backgroundColor: coverRgb }" />
      <div class="w-full h-full absolute top-0 left-0" style="background: linear-gradient(169deg, rgba(0, 0, 0, 0.4) 0%, rgba(55, 56, 56, 1) 80%)" />
    </div>

    <div class="z-10 relative">
      <!-- cover -->
      <div class="w-full flex justify-center relative mb-4">
        <div style="width: 0; transform: translateX(-50vw); overflow: visible">
          <div style="width: 150vw; overflow: hidden">
            <div id="coverBg" style="filter: blur(5vw)">
              <covers-book-cover :library-item="libraryItem" :width="coverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" @imageLoaded="coverImageLoaded" />
            </div>
          </div>
        </div>
        <div class="relative" @click="showFullscreenCover = true">
          <covers-book-cover :library-item="libraryItem" :width="coverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" no-bg raw @imageLoaded="coverImageLoaded" />
          <div v-if="!isPodcast" class="absolute bottom-0 left-0 h-1 shadow-sm z-10" :class="userIsFinished ? 'bg-success' : 'bg-yellow-400'" :style="{ width: coverWidth * progressPercent + 'px' }"></div>
        </div>
      </div>

      <!-- title -->
      <div class="text-center mb-2">
        <h1 class="text-xl font-semibold">{{ title }}</h1>
        <p v-if="subtitle" class="text-gray-100 text-base">{{ subtitle }}</p>
      </div>

      <!-- Show an indicator for local library items whether they are linked to a server item and if that server item is connected -->
      <p v-if="isLocal && serverLibraryItemId" style="font-size: 10px" class="text-success text-center py-1 uppercase tracking-widest">connected</p>
      <p v-else-if="isLocal && libraryItem.serverAddress" style="font-size: 10px" class="text-gray-400 text-center py-1">{{ libraryItem.serverAddress }}</p>

      <!-- action buttons -->
      <div class="col-span-full">
        <div v-if="showPlay || showRead" class="flex mt-4 -mx-1">
          <ui-btn v-if="showPlay" color="success" class="flex items-center justify-center flex-grow mx-1" :padding-x="4" @click="playClick">
            <span class="material-icons">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? 'Pause' : isPodcast ? 'Next Episode' : hasLocal ? 'Play' : 'Stream' }}</span>
          </ui-btn>
          <ui-btn v-if="showRead" color="info" class="flex items-center justify-center mx-1" :class="showPlay ? '' : 'flex-grow'" :padding-x="2" @click="readBook">
            <span class="material-icons">auto_stories</span>
            <span v-if="!showPlay" class="px-2 text-base">Read {{ ebookFormat }}</span>
          </ui-btn>
          <ui-btn v-if="showDownload" :color="downloadItem ? 'warning' : 'primary'" class="flex items-center justify-center mx-1" :padding-x="2" @click="downloadClick">
            <span class="material-icons" :class="downloadItem ? 'animate-pulse' : ''">{{ downloadItem ? 'downloading' : 'download' }}</span>
          </ui-btn>
          <ui-btn color="primary" class="flex items-center justify-center mx-1" :padding-x="2" @click="moreButtonPress">
            <span class="material-icons">more_vert</span>
          </ui-btn>
        </div>

        <div v-if="!isPodcast && progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-gray-200 mt-4 text-center" :class="resettingProgress ? 'opacity-25' : ''">
          <p>Your Progress: {{ Math.round(progressPercent * 100) }}%</p>
          <p v-if="!useEBookProgress && !userIsFinished" class="text-gray-400 text-xs">{{ $elapsedPretty(userTimeRemaining) }} remaining</p>
          <p v-else-if="userIsFinished" class="text-gray-400 text-xs">Finished {{ $formatDate(userProgressFinishedAt) }}</p>
        </div>
      </div>

      <div v-if="downloadItem" class="py-3">
        <p v-if="downloadItem.itemProgress == 1" class="text-center text-lg">Download complete. Processing...</p>
        <p v-else class="text-center text-lg">Downloading! ({{ Math.round(downloadItem.itemProgress * 100) }}%)</p>
      </div>

      <!-- metadata -->
      <div id="metadata" class="grid gap-2 my-2" style>
        <div v-if="podcastAuthor || (bookAuthors && bookAuthors.length)" class="text-white text-opacity-60 uppercase text-sm">Author</div>
        <div v-if="podcastAuthor" class="text-sm">{{ podcastAuthor }}</div>
        <div v-else-if="bookAuthors && bookAuthors.length" class="text-sm">
          <template v-for="(author, index) in bookAuthors">
            <nuxt-link :key="author.id" :to="`/bookshelf/library?filter=authors.${$encode(author.id)}`" class="underline">{{ author.name }}</nuxt-link>
            <span :key="`${author.id}-comma`" v-if="index < bookAuthors.length - 1">,</span>
          </template>
        </div>

        <div v-if="podcastType" class="text-white text-opacity-60 uppercase text-sm">Type</div>
        <div v-if="podcastType" class="text-sm capitalize">{{ podcastType }}</div>

        <div v-if="series && series.length" class="text-white text-opacity-60 uppercase text-sm">Series</div>
        <div v-if="series && series.length" class="truncate text-sm">
          <template v-for="(series, index) in seriesList">
            <nuxt-link :key="series.id" :to="`/bookshelf/series/${series.id}`" class="underline">{{ series.text }}</nuxt-link>
            <span :key="`${series.id}-comma`" v-if="index < seriesList.length - 1">,&nbsp;</span>
          </template>
        </div>

        <div v-if="numTracks" class="text-white text-opacity-60 uppercase text-sm">Duration</div>
        <div v-if="numTracks" class="text-sm">{{ $elapsedPretty(duration) }}</div>

        <div v-if="narrators && narrators.length" class="text-white text-opacity-60 uppercase text-sm">{{ narrators.length === 1 ? 'Narrator' : 'Narrators' }}</div>
        <div v-if="narrators && narrators.length" class="truncate text-sm">
          <template v-for="(narrator, index) in narrators">
            <nuxt-link :key="narrator" :to="`/bookshelf/library?filter=narrators.${$encode(narrator)}`" class="underline">{{ narrator }}</nuxt-link>
            <span :key="index" v-if="index < narrators.length - 1">,</span>
          </template>
        </div>

        <div v-if="genres.length" class="text-white text-opacity-60 uppercase text-sm">{{ genres.length === 1 ? 'Genre' : 'Genres' }}</div>
        <div v-if="genres.length" class="truncate text-sm">
          <template v-for="(genre, index) in genres">
            <nuxt-link :key="genre" :to="`/bookshelf/library?filter=genres.${$encode(genre)}`" class="underline">{{ genre }}</nuxt-link>
            <span :key="index" v-if="index < genres.length - 1">,</span>
          </template>
        </div>

        <div v-if="publishedYear" class="text-white text-opacity-60 uppercase text-sm">Published</div>
        <div v-if="publishedYear" class="text-sm">{{ publishedYear }}</div>
      </div>

      <div v-if="description" class="w-full py-2">
        <p ref="description" class="text-sm text-justify whitespace-pre-line font-light" :class="{ 'line-clamp-4': !showFullDescription }" style="hyphens: auto">{{ description }}</p>

        <div v-if="descriptionClamped" class="text-white text-sm py-2" @click="showFullDescription = !showFullDescription">
          {{ showFullDescription ? 'Read less' : 'Read more' }}
          <span class="material-icons align-middle text-base -mt-px">{{ showFullDescription ? 'expand_less' : 'expand_more' }}</span>
        </div>
      </div>

      <!-- tables -->
      <tables-podcast-episodes-table v-if="isPodcast" :library-item="libraryItem" :local-library-item-id="localLibraryItemId" :episodes="episodes" :local-episodes="localLibraryItemEpisodes" :is-local="isLocal" />

      <tables-chapters-table v-if="numChapters" :library-item="libraryItem" @playAtTimestamp="playAtTimestamp" />

      <tables-tracks-table v-if="numTracks" :tracks="tracks" :library-item-id="libraryItemId" />

      <tables-ebook-files-table v-if="ebookFiles.length" :library-item="libraryItem" />

      <!-- modals -->
      <modals-select-local-folder-modal v-model="showSelectLocalFolder" :media-type="mediaType" @select="selectedLocalFolder" />

      <modals-dialog v-model="showMoreMenu" :items="moreMenuItems" @action="moreMenuAction" />

      <modals-item-details-modal v-model="showDetailsModal" :library-item="libraryItem" />

      <modals-fullscreen-cover v-model="showFullscreenCover" :library-item="libraryItem" />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'
import { FastAverageColor } from 'fast-average-color'

export default {
  async asyncData({ store, params, redirect, app }) {
    const libraryItemId = params.id
    let libraryItem = null
    if (libraryItemId.startsWith('local')) {
      libraryItem = await app.$db.getLocalLibraryItem(libraryItemId)
      console.log('Got lli', libraryItemId)
    } else if (store.state.user.serverConnectionConfig) {
      libraryItem = await app.$axios.$get(`/api/items/${libraryItemId}?expanded=1`).catch((error) => {
        console.error('Failed', error)
        return false
      })

      if (libraryItem) {
        const localLibraryItem = await app.$db.getLocalLibraryItemByLId(libraryItemId)
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
      showFullscreenCover: false,
      coverRgb: 'rgb(55, 56, 56)',
      coverBgIsLight: false,
      windowWidth: 0,
      descriptionClamped: false,
      showFullDescription: false
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
      return this.localLibraryItem?.id || null
    },
    localLibraryItemEpisodes() {
      if (!this.isPodcast || !this.localLibraryItem) return []
      var podcastMedia = this.localLibraryItem.media
      return podcastMedia?.episodes || []
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
      return this.$store.getters['libraries/getBookCoverAspectRatio']
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
    subtitle() {
      return this.mediaMetadata.subtitle
    },
    genres() {
      return this.mediaMetadata.genres || []
    },
    publishedYear() {
      return this.mediaMetadata.publishedYear
    },
    podcastType() {
      return this.mediaMetadata.type
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
    user() {
      return this.$store.state.user.user
    },
    userToken() {
      return this.$store.getters['user/getToken']
    },
    userItemProgress() {
      if (this.isPodcast) return null
      if (this.isLocal) return this.localItemProgress
      return this.serverItemProgress
    },
    localItemProgress() {
      if (this.isPodcast) return null
      return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId)
    },
    serverItemProgress() {
      if (this.isPodcast) return null
      return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId)
    },
    userIsFinished() {
      return this.userItemProgress ? !!this.userItemProgress.isFinished : false
    },
    userTimeRemaining() {
      if (!this.userItemProgress) return 0
      const duration = this.userItemProgress.duration || this.duration
      return duration - this.userItemProgress.currentTime
    },
    useEBookProgress() {
      if (!this.userItemProgress || this.userItemProgress.progress) return false
      return this.userItemProgress.ebookProgress > 0
    },
    progressPercent() {
      if (this.useEBookProgress) return Math.max(Math.min(1, this.userItemProgress.ebookProgress), 0)
      return this.userItemProgress ? Math.max(Math.min(1, this.userItemProgress.progress), 0) : 0
    },
    userProgressFinishedAt() {
      return this.userItemProgress ? this.userItemProgress.finishedAt : 0
    },
    isStreaming() {
      return this.isPlaying && !this.$store.getters['getIsCurrentSessionLocal']
    },
    isPlaying() {
      if (this.localLibraryItemId && this.$store.getters['getIsMediaStreaming'](this.localLibraryItemId)) return true
      return this.$store.getters['getIsMediaStreaming'](this.libraryItemId)
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && (this.isStreaming || this.isPlaying)
    },
    tracks() {
      return this.media.tracks || []
    },
    numTracks() {
      return this.tracks.length || 0
    },
    numChapters() {
      if (!this.media.chapters) return 0
      return this.media.chapters.length || 0
    },
    isMissing() {
      return this.libraryItem.isMissing
    },
    isInvalid() {
      return this.libraryItem.isInvalid
    },
    showPlay() {
      return !this.isMissing && !this.isInvalid && (this.numTracks || this.episodes.length)
    },
    showRead() {
      return this.ebookFile
    },
    showDownload() {
      if (this.isPodcast || this.hasLocal) return false
      return this.user && this.userCanDownload && (this.showPlay || this.showRead)
    },
    libraryFiles() {
      return this.libraryItem.libraryFiles || []
    },
    ebookFiles() {
      return this.libraryFiles.filter((lf) => lf.fileType === 'ebook')
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
      const items = []

      if (!this.isPodcast) {
        // TODO: Implement on iOS
        if (!this.isIos) {
          items.push({
            text: 'History',
            value: 'history',
            icon: 'history'
          })
        }

        if (!this.userIsFinished) {
          items.push({
            text: 'Mark as Finished',
            value: 'markFinished',
            icon: 'beenhere'
          })
        }

        if (this.progressPercent > 0) {
          items.push({
            text: 'Discard Progress',
            value: 'discardProgress',
            icon: 'backspace'
          })
        }
      }

      if (!this.isPodcast && this.serverLibraryItemId) {
        items.push({
          text: 'Add to Playlist',
          value: 'playlist',
          icon: 'playlist_add'
        })
      }

      if (this.localLibraryItemId) {
        items.push({
          text: 'Manage Local Files',
          value: 'manageLocal',
          icon: 'folder'
        })

        if (!this.isPodcast) {
          items.push({
            text: 'Delete Local Item',
            value: 'deleteLocal',
            icon: 'delete'
          })
        }
      }

      items.push({
        text: 'More Info',
        value: 'details',
        icon: 'info'
      })

      return items
    },
    coverWidth() {
      let width = this.windowWidth - 94
      if (width > 325) return 325
      else if (width < 0) return 175

      if (width * this.bookCoverAspectRatio > 325) width = 325 / this.bookCoverAspectRatio
      return width
    },
    mediaId() {
      if (this.isPodcast) return null
      return this.serverLibraryItemId || this.localLibraryItemId
    }
  },
  methods: {
    async deleteLocalItem() {
      await this.$hapticsImpact()

      let confirmMessage = 'Remove local files of this item from your device?'
      if (this.serverLibraryItemId) {
        confirmMessage += ' The files on the server and your progress will be unaffected.'
      }
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: confirmMessage
      })
      if (value) {
        const res = await AbsFileSystem.deleteItem(this.localLibraryItem)
        if (res?.success) {
          this.$toast.success('Deleted successfully')
          if (this.isLocal) {
            // If local then redirect to server version when available
            if (this.serverLibraryItemId) {
              this.$router.replace(`/item/${this.serverLibraryItemId}`)
            } else {
              this.$router.replace('/bookshelf')
            }
          } else {
            // Remove localLibraryItem
            this.$delete(this.libraryItem, 'localLibraryItem')
          }
        } else this.$toast.error('Failed to delete')
      }
    },
    async coverImageLoaded(fullCoverUrl) {
      if (!fullCoverUrl) return

      const fac = new FastAverageColor()
      fac
        .getColorAsync(fullCoverUrl)
        .then((color) => {
          this.coverRgb = color.rgba
          this.coverBgIsLight = color.isLight
        })
        .catch((e) => {
          console.log(e)
        })
    },
    moreMenuAction(action) {
      this.showMoreMenu = false
      if (action === 'manageLocal') {
        this.$nextTick(() => {
          this.$router.push(`/localMedia/item/${this.localLibraryItemId}`)
        })
      } else if (action === 'details') {
        this.showDetailsModal = true
      } else if (action === 'playlist') {
        this.$store.commit('globals/setSelectedPlaylistItems', [{ libraryItem: this.libraryItem, episode: null }])
        this.$store.commit('globals/setShowPlaylistsAddCreateModal', true)
      } else if (action === 'markFinished') {
        if (this.isProcessingReadUpdate) return
        this.toggleFinished()
      } else if (action === 'history') {
        this.$router.push(`/media/${this.mediaId}/history?title=${this.title}`)
      } else if (action === 'discardProgress') {
        this.clearProgressClick()
      } else if (action === 'deleteLocal') {
        this.deleteLocalItem()
      }
    },
    moreButtonPress() {
      this.showMoreMenu = true
    },
    readBook() {
      if (this.localLibraryItem?.media?.ebookFile) {
        // Has local ebook file
        this.$store.commit('showReader', { libraryItem: this.localLibraryItem, keepProgress: true })
      } else {
        this.$store.commit('showReader', { libraryItem: this.libraryItem, keepProgress: true })
      }
    },
    playAtTimestamp(seconds) {
      this.play(seconds)
    },
    async playClick() {
      await this.$hapticsImpact()
      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.play()
      }
    },
    async play(startTime = null) {
      if (this.isPodcast) {
        this.episodes.sort((a, b) => {
          return String(b.publishedAt).localeCompare(String(a.publishedAt), undefined, { numeric: true, sensitivity: 'base' })
        })

        let episode = this.episodes.find((ep) => {
          var podcastProgress = null
          if (!this.isLocal) {
            podcastProgress = this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, ep.id)
          } else {
            podcastProgress = this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, ep.id)
          }
          return !podcastProgress || !podcastProgress.isFinished
        })

        if (!episode) episode = this.episodes[0]

        const episodeId = episode.id

        let localEpisode = null
        if (this.hasLocal && !this.isLocal) {
          localEpisode = this.localLibraryItem.media.episodes.find((ep) => ep.serverEpisodeId == episodeId)
        } else if (this.isLocal) {
          localEpisode = episode
        }
        const serverEpisodeId = !this.isLocal ? episodeId : localEpisode ? localEpisode.serverEpisodeId : null

        if (serverEpisodeId && this.serverLibraryItemId && this.isCasting) {
          // If casting and connected to server for local library item then send server library item id
          this.$eventBus.$emit('play-item', { libraryItemId: this.serverLibraryItemId, episodeId: serverEpisodeId })
        } else if (localEpisode) {
          this.$eventBus.$emit('play-item', { libraryItemId: this.localLibraryItem.id, episodeId: localEpisode.id, serverLibraryItemId: this.serverLibraryItemId, serverEpisodeId })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: this.libraryItemId, episodeId })
        }
      } else {
        // Audiobook
        let libraryItemId = this.libraryItemId

        // When casting use server library item
        if (this.hasLocal && this.serverLibraryItemId && this.isCasting) {
          libraryItemId = this.serverLibraryItemId
        } else if (this.hasLocal) {
          libraryItemId = this.localLibraryItem.id
        }

        // If start time and is not already streaming then ask for confirmation
        if (startTime !== null && startTime !== undefined && !this.$store.getters['getIsMediaStreaming'](libraryItemId, null)) {
          const { value } = await Dialog.confirm({
            title: 'Confirm',
            message: `Start playback for "${this.title}" at ${this.$secondsToTimestamp(startTime)}?`
          })
          if (!value) return
        }

        this.$eventBus.$emit('play-item', { libraryItemId, serverLibraryItemId: this.serverLibraryItemId, startTime })
      }
    },
    async clearProgressClick() {
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Are you sure you want to reset your progress?'
      })
      if (value) {
        this.resettingProgress = true
        const serverMediaProgressId = this.serverItemProgress?.id
        if (this.localLibraryItemId) {
          await this.$db.removeLocalMediaProgress(this.localLibraryItemId)
          this.$store.commit('globals/removeLocalMediaProgressForItem', this.localLibraryItemId)
        }

        if (this.serverLibraryItemId && serverMediaProgressId) {
          await this.$axios
            .$delete(`/api/me/progress/${serverMediaProgressId}`)
            .then(() => {
              console.log('Progress reset complete')
              this.$toast.success(`Your progress was reset`)
              this.$store.commit('user/removeMediaProgress', serverMediaProgressId)
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
        this.checkDescriptionClamped()
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
    async downloadClick() {
      if (this.downloadItem) {
        return
      }
      await this.$hapticsImpact()
      if (this.isIos) {
        // no local folders on iOS
        this.startDownload()
      } else {
        this.download()
      }
    },
    async download(selectedLocalFolder = null) {
      // Get the local folder to download to
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
            name: 'Internal App Storage',
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

      let startDownloadMessage = `Start download for "${this.title}" with ${this.numTracks} audio track${this.numTracks == 1 ? '' : 's'} to folder ${localFolder.name}?`
      if (!this.isIos && this.showRead) {
        if (this.numTracks > 0) {
          startDownloadMessage = `Start download for "${this.title}" with ${this.numTracks} audio track${this.numTracks == 1 ? '' : 's'} and ebook file to folder ${localFolder.name}?`
        } else {
          startDownloadMessage = `Start download for "${this.title}" with ebook file to folder ${localFolder.name}?`
        }
      }
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
      await this.$hapticsImpact()

      // Show confirm if item has progress since it will reset
      if (this.userItemProgress && this.userItemProgress.progress > 0 && !this.userIsFinished) {
        const { value } = await Dialog.confirm({
          title: 'Confirm',
          message: 'Are you sure you want to mark this item as Finished?'
        })
        if (!value) return
      }

      this.isProcessingReadUpdate = true
      if (this.isLocal) {
        const isFinished = !this.userIsFinished
        const payload = await this.$db.updateLocalMediaProgressFinished({ localLibraryItemId: this.localLibraryItemId, isFinished })
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
        this.isProcessingReadUpdate = false
      } else {
        const updatePayload = {
          isFinished: !this.userIsFinished
        }
        this.$axios
          .$patch(`/api/me/progress/${this.libraryItemId}`, updatePayload)
          .catch((error) => {
            console.error('Failed', error)
            this.$toast.error(`Failed to mark as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
          })
          .finally(() => {
            this.isProcessingReadUpdate = false
          })
      }
    },
    libraryChanged(libraryId) {
      if (this.libraryItem.libraryId !== libraryId) {
        this.$router.replace('/bookshelf')
      }
    },
    checkDescriptionClamped() {
      if (this.showFullDescription) return
      if (!this.$refs.description) {
        this.descriptionClamped = false
      } else {
        this.descriptionClamped = this.$refs.description.scrollHeight > this.$refs.description.clientHeight
      }
    },
    windowResized() {
      this.windowWidth = window.innerWidth
      this.checkDescriptionClamped()
    }
  },
  mounted() {
    this.windowWidth = window.innerWidth
    window.addEventListener('resize', this.windowResized)
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.$on('item_updated', this.itemUpdated)
    this.checkDescriptionClamped()

    // Set last scroll position if was set for this item
    if (this.$store.state.lastItemScrollData.id === this.libraryItemId && window['item-page']) {
      window['item-page'].scrollTop = this.$store.state.lastItemScrollData.scrollTop || 0
    }
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.windowResized)
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.$off('item_updated', this.itemUpdated)

    // Set scroll position
    if (window['item-page']) {
      this.$store.commit('setLastItemScrollData', { scrollTop: window['item-page'].scrollTop || 0, id: this.libraryItemId })
    }
  }
}
</script>

<style>
.title-container {
  width: calc(100% - 64px);
  max-width: calc(100% - 64px);
}
#coverBg > div {
  width: 150vw !important;
  max-width: 150vw !important;
}

@media only screen and (max-width: 500px) {
  #metadata {
    grid-template-columns: auto 1fr;
  }
}
@media only screen and (min-width: 500px) {
  #metadata {
    grid-template-columns: auto 1fr auto 1fr;
  }
}
</style>
