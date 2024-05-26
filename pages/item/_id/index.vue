<template>
  <div v-if="!libraryItem" class="w-full h-full relative flex items-center justify-center bg-bg">
    <ui-loading-indicator />
  </div>
  <div v-else id="item-page" class="w-full h-full overflow-y-auto overflow-x-hidden relative bg-bg">
    <!-- cover -->
    <div class="w-full flex justify-center relative">
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

    <div class="relative">
      <!-- background gradient -->
      <div id="item-page-bg-gradient" class="absolute top-0 left-0 w-full pointer-events-none z-0" :style="{ opacity: coverRgb ? 1 : 0 }">
        <div class="w-full h-full" :style="{ backgroundColor: coverRgb }" />
        <div class="w-full h-full absolute top-0 left-0" style="background: var(--gradient-item-page)" />
      </div>

      <div class="relative z-10 px-3 py-4">
        <!-- title -->
        <div class="text-center mb-2">
          <div class="flex items-center justify-center">
            <h1 class="text-xl font-semibold">{{ title }}</h1>
            <widgets-explicit-indicator v-if="isExplicit" />
          </div>
          <p v-if="subtitle" class="text-fg text-base">{{ subtitle }}</p>
        </div>

        <div v-if="hasLocal" class="mx-1">
          <div v-if="isLocalOnly" class="w-full rounded-md bg-warning/10 border border-warning p-4">
            <p class="text-sm">{{ $strings.MessageMediaNotLinkedToServer }}</p>
          </div>
          <div v-else-if="currentServerConnectionConfigId && !isLocalMatchingServerAddress" class="w-full rounded-md bg-warning/10 border border-warning p-4">
            <p class="text-sm">{{ $getString('MessageMediaLinkedToADifferentServer', [localLibraryItem.serverAddress]) }}</p>
          </div>
          <div v-else-if="currentServerConnectionConfigId && !isLocalMatchingUser" class="w-full rounded-md bg-warning/10 border border-warning p-4">
            <p class="text-sm">{{ $strings.MessageMediaLinkedToADifferentUser }}</p>
          </div>
          <div v-else-if="currentServerConnectionConfigId && !isLocalMatchingConnectionConfig" class="w-full rounded-md bg-warning/10 border border-warning p-4">
            <p class="text-sm">Media is linked to a different server connection config. Downloaded User Id: {{ localLibraryItem.serverUserId }}. Downloaded Server Address: {{ localLibraryItem.serverAddress }}. Currently connected User Id: {{ user.id }}. Currently connected server address: {{ currentServerAddress }}.</p>
          </div>
        </div>

        <!-- action buttons -->
        <div class="col-span-full">
          <div v-if="showPlay || showRead" class="flex mt-4 -mx-1">
            <ui-btn v-if="showPlay" color="success" class="flex items-center justify-center flex-grow mx-1" :loading="playerIsStartingForThisMedia" :padding-x="4" @click="playClick">
              <span class="material-icons">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
              <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : isPodcast ? $strings.ButtonNextEpisode : hasLocal ? $strings.ButtonPlay : $strings.ButtonStream }}</span>
            </ui-btn>
            <ui-btn v-if="showRead" color="info" class="flex items-center justify-center mx-1" :class="showPlay ? '' : 'flex-grow'" :padding-x="2" @click="readBook">
              <span class="material-icons">auto_stories</span>
              <span v-if="!showPlay" class="px-2 text-base">{{ $strings.ButtonRead }} {{ ebookFormat }}</span>
            </ui-btn>
            <ui-btn v-if="showDownload" :color="downloadItem ? 'warning' : 'primary'" class="flex items-center justify-center mx-1" :padding-x="2" @click="downloadClick">
              <span class="material-icons" :class="downloadItem || startingDownload ? 'animate-pulse' : ''">{{ downloadItem || startingDownload ? 'downloading' : 'download' }}</span>
            </ui-btn>
            <ui-btn color="primary" class="flex items-center justify-center mx-1" :padding-x="2" @click="moreButtonPress">
              <span class="material-icons">more_vert</span>
            </ui-btn>
          </div>

          <div v-if="!isPodcast && progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-fg mt-4 text-center">
            <p>{{ $strings.LabelYourProgress }}: {{ Math.round(progressPercent * 100) }}%</p>
            <p v-if="!useEBookProgress && !userIsFinished" class="text-fg-muted text-xs">{{ $getString('LabelTimeRemaining', [$elapsedPretty(userTimeRemaining)]) }}</p>
            <p v-else-if="userIsFinished" class="text-fg-muted text-xs">{{ $strings.LabelFinished }} {{ $formatDate(userProgressFinishedAt) }}</p>
          </div>
        </div>

        <div v-if="downloadItem" class="py-3">
          <p v-if="downloadItem.itemProgress == 1" class="text-center text-lg">{{ $strings.MessageDownloadCompleteProcessing }}</p>
          <p v-else class="text-center text-lg">{{ $strings.MessageDownloading }} ({{ Math.round(downloadItem.itemProgress * 100) }}%)</p>
        </div>

        <!-- metadata -->
        <div id="metadata" class="grid gap-2 my-2" style>
          <div v-if="podcastAuthor || bookAuthors?.length" class="text-fg-muted uppercase text-sm">{{ $strings.LabelAuthor }}</div>
          <div v-if="podcastAuthor" class="text-sm">{{ podcastAuthor }}</div>
          <div v-else-if="bookAuthors?.length" class="text-sm">
            <template v-for="(author, index) in bookAuthors">
              <nuxt-link :key="author.id" :to="`/bookshelf/library?filter=authors.${$encode(author.id)}`" class="underline whitespace-nowrap">{{ author.name }}</nuxt-link
              ><span :key="`${author.id}-comma`" v-if="index < bookAuthors.length - 1">, </span>
            </template>
          </div>

          <div v-if="podcastType" class="text-fg-muted uppercase text-sm">{{ $strings.LabelType }}</div>
          <div v-if="podcastType" class="text-sm capitalize">{{ podcastType }}</div>

          <div v-if="series?.length" class="text-fg-muted uppercase text-sm">{{ $strings.LabelSeries }}</div>
          <div v-if="series?.length" class="text-sm">
            <template v-for="(series, index) in seriesList">
              <nuxt-link :key="series.id" :to="`/bookshelf/series/${series.id}`" class="underline whitespace-nowrap">{{ series.text }}</nuxt-link
              ><span :key="`${series.id}-comma`" v-if="index < seriesList.length - 1">, </span>
            </template>
          </div>

          <div v-if="numTracks" class="text-fg-muted uppercase text-sm">{{ $strings.LabelDuration }}</div>
          <div v-if="numTracks" class="text-sm">{{ $elapsedPretty(duration) }}</div>

          <div v-if="narrators?.length" class="text-fg-muted uppercase text-sm">{{ $strings.LabelNarrators }}</div>
          <div v-if="narrators?.length" class="text-sm">
            <template v-for="(narrator, index) in narrators">
              <nuxt-link :key="narrator" :to="`/bookshelf/library?filter=narrators.${$encode(narrator)}`" class="underline whitespace-nowrap">{{ narrator }}</nuxt-link
              ><span :key="index" v-if="index < narrators.length - 1">, </span>
            </template>
          </div>

          <div v-if="genres.length" class="text-fg-muted uppercase text-sm">{{ $strings.LabelGenres }}</div>
          <div v-if="genres.length" class="text-sm">
            <template v-for="(genre, index) in genres">
              <nuxt-link :key="genre" :to="`/bookshelf/library?filter=genres.${$encode(genre)}`" class="underline whitespace-nowrap">{{ genre }}</nuxt-link
              ><span :key="index" v-if="index < genres.length - 1">, </span>
            </template>
          </div>

          <div v-if="tags.length" class="text-fg-muted uppercase text-sm">{{ $strings.LabelTags }}</div>
          <div v-if="tags.length" class="text-sm">
            <template v-for="(tag, index) in tags">
              <nuxt-link :key="tag" :to="`/bookshelf/library?filter=tags.${$encode(tag)}`" class="underline whitespace-nowrap">{{ tag }}</nuxt-link
              ><span :key="index" v-if="index < tags.length - 1">, </span>
            </template>
          </div>

          <div v-if="publishedYear" class="text-fg-muted uppercase text-sm">{{ $strings.LabelPublishYear }}</div>
          <div v-if="publishedYear" class="text-sm">{{ publishedYear }}</div>
        </div>

        <div v-if="description" class="w-full py-2">
          <p ref="description" class="text-sm text-justify whitespace-pre-line font-light" :class="{ 'line-clamp-4': !showFullDescription }" style="hyphens: auto">{{ description }}</p>

          <div v-if="descriptionClamped" class="text-fg text-sm py-2" @click="showFullDescription = !showFullDescription">
            {{ showFullDescription ? 'Read less' : 'Read more' }}
            <span class="material-icons align-middle text-base -mt-px">{{ showFullDescription ? 'expand_less' : 'expand_more' }}</span>
          </div>
        </div>

        <!-- tables -->
        <tables-podcast-episodes-table v-if="isPodcast" :library-item="libraryItem" :local-library-item-id="localLibraryItemId" :episodes="episodes" :local-episodes="localLibraryItemEpisodes" :is-local="isLocal" />

        <tables-chapters-table v-if="numChapters" :library-item="libraryItem" @playAtTimestamp="playAtTimestamp" />

        <tables-tracks-table v-if="numTracks" :tracks="tracks" :library-item-id="libraryItemId" />

        <tables-ebook-files-table v-if="ebookFiles.length" :library-item="libraryItem" />
      </div>
    </div>

    <!-- modals -->
    <modals-item-more-menu-modal v-model="showMoreMenu" :library-item="libraryItem" :rss-feed="rssFeed" :processing.sync="processing" />

    <modals-select-local-folder-modal v-model="showSelectLocalFolder" :media-type="mediaType" @select="selectedLocalFolder" />

    <modals-fullscreen-cover v-model="showFullscreenCover" :library-item="libraryItem" />

    <div v-show="processing" class="fixed top-0 left-0 w-screen h-screen flex items-center justify-center bg-black/50 z-50">
      <ui-loading-indicator />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'
import { FastAverageColor } from 'fast-average-color'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

export default {
  async asyncData({ store, params, redirect, app, query }) {
    const libraryItemId = params.id
    let libraryItem = null

    if (libraryItemId.startsWith('local')) {
      libraryItem = await app.$db.getLocalLibraryItem(libraryItemId)
      if (!libraryItem) {
        return redirect('/?error=Failed to get downloaded library item')
      }

      // If library item is linked to the currently connected server then redirect to the page using the server library item id
      if (libraryItem?.libraryItemId?.startsWith('li_')) {
        // Detect old library item id
        console.error('Local library item has old server library item id', libraryItem.libraryItemId)
      } else if (query.noredirect !== '1' && libraryItem?.libraryItemId && libraryItem?.serverAddress === store.getters['user/getServerAddress'] && store.state.networkConnected) {
        const queryParams = new URLSearchParams()
        queryParams.set('localLibraryItemId', libraryItemId)
        if (libraryItem.mediaType === 'podcast') {
          // Filter by downloaded when redirecting from the local copy
          queryParams.set('episodefilter', 'downloaded')
        }
        return redirect(`/item/${libraryItem.libraryItemId}?${queryParams.toString()}`)
      }
    } else if (!store.state.user.serverConnectionConfig) {
      // Not connected to server
      return redirect('/?error=No server connection to get library item')
    }

    return {
      libraryItem,
      libraryItemId
    }
  },
  data() {
    return {
      processing: false,
      showSelectLocalFolder: false,
      showMoreMenu: false,
      showFullscreenCover: false,
      coverRgb: null,
      coverBgIsLight: false,
      windowWidth: 0,
      descriptionClamped: false,
      showFullDescription: false,
      episodeStartingPlayback: null,
      startingDownload: false
    }
  },
  mixins: [cellularPermissionHelpers],
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    isLocal() {
      return this.libraryItem.isLocal
    },
    isLocalOnly() {
      // TODO: Remove the possibility to have local only on android
      return this.isLocal && !this.libraryItem.libraryItemId
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
      if (this.currentServerAddress === this.libraryItem.serverAddress) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    localLibraryItemServerConnectionConfigId() {
      return this.localLibraryItem?.serverConnectionConfigId
    },
    currentServerAddress() {
      return this.$store.getters['user/getServerAddress']
    },
    currentServerConnectionConfigId() {
      return this.$store.getters['user/getServerConnectionConfigId']
    },
    /**
     * User is currently connected to a server and this local library item has the same server address
     */
    isLocalMatchingServerAddress() {
      if (this.isLocalOnly || !this.localLibraryItem || !this.currentServerAddress) return false
      return this.localLibraryItem.serverAddress === this.currentServerAddress
    },
    /**
     * User is currently connected to a server and this local library item has the same user id
     */
    isLocalMatchingUser() {
      if (this.isLocalOnly || !this.localLibraryItem || !this.user) return false
      return this.localLibraryItem.serverUserId === this.user.id || this.localLibraryItem.serverUserId === this.user.oldUserId
    },
    /**
     * User is currently connected to a server and this local library item has the same connection config id
     */
    isLocalMatchingConnectionConfig() {
      if (this.isLocalOnly || !this.localLibraryItemServerConnectionConfigId || !this.currentServerConnectionConfigId) return false
      return this.localLibraryItemServerConnectionConfigId === this.currentServerConnectionConfigId
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    rssFeed() {
      return this.libraryItem?.rssFeed
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
    tags() {
      return this.media.tags || []
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
      return !!this.userItemProgress?.isFinished
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
      return Math.max(Math.min(1, this.userItemProgress?.progress || 0), 0)
    },
    userProgressFinishedAt() {
      return this.userItemProgress?.finishedAt || 0
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
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      if (!mediaId) return false

      if (this.isPodcast) {
        return mediaId === this.episodeStartingPlayback
      } else {
        return mediaId === this.serverLibraryItemId || mediaId === this.localLibraryItemId
      }
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
    isExplicit() {
      return !!this.mediaMetadata.explicit
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
    coverWidth() {
      let width = this.windowWidth - 94
      if (width > 325) return 325
      else if (width < 0) return 175

      if (width * this.bookCoverAspectRatio > 325) width = 325 / this.bookCoverAspectRatio
      return width
    },
    coverHeight() {
      return this.coverWidth * this.bookCoverAspectRatio
    }
  },
  methods: {
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
      if (this.playerIsStartingPlayback) return

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
          return !podcastProgress?.isFinished
        })

        if (!episode) episode = this.episodes[0]

        const episodeId = episode.id

        let localEpisode = null
        if (this.hasLocal && !this.isLocal) {
          localEpisode = this.localLibraryItem.media.episodes.find((ep) => ep.serverEpisodeId == episodeId)
        } else if (this.isLocal) {
          localEpisode = episode
        }
        const serverEpisodeId = !this.isLocal ? episodeId : localEpisode?.serverEpisodeId || null

        this.episodeStartingPlayback = serverEpisodeId
        this.$store.commit('setPlayerIsStartingPlayback', serverEpisodeId)
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

        this.$store.commit('setPlayerIsStartingPlayback', libraryItemId)
        this.$eventBus.$emit('play-item', { libraryItemId, serverLibraryItemId: this.serverLibraryItemId, startTime })
      }
    },
    itemUpdated(libraryItem) {
      if (libraryItem.id === this.serverLibraryItemId) {
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
      if (this.downloadItem || this.startingDownload)  return

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
    },
    rssFeedOpen(data) {
      if (data.entityId === this.serverLibraryItemId) {
        console.log('RSS Feed Opened', data)
        this.rssFeed = data
      }
    },
    rssFeedClosed(data) {
      if (data.entityId === this.serverLibraryItemId) {
        console.log('RSS Feed Closed', data)
        this.rssFeed = null
      }
    },
    async setLibrary() {
      if (!this.libraryItem.libraryId) return
      await this.$store.dispatch('libraries/fetch', this.libraryItem.libraryId)
      this.$localStore.setLastLibraryId(this.libraryItem.libraryId)
    },
    init() {
      // If library of this item is different from current library then switch libraries
      if (this.$store.state.libraries.currentLibraryId !== this.libraryItem.libraryId) {
        this.setLibrary()
      }

      this.windowWidth = window.innerWidth
      window.addEventListener('resize', this.windowResized)
      this.$eventBus.$on('library-changed', this.libraryChanged)
      this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
      this.$socket.$on('item_updated', this.itemUpdated)
      this.$socket.$on('rss_feed_open', this.rssFeedOpen)
      this.$socket.$on('rss_feed_closed', this.rssFeedClosed)
      this.checkDescriptionClamped()

      // Set height of page below cover image
      const itemPageBgGradientHeight = window.outerHeight - 64 - this.coverHeight
      document.documentElement.style.setProperty('--item-page-bg-gradient-height', itemPageBgGradientHeight + 'px')

      // Set last scroll position if was set for this item
      if (this.$store.state.lastItemScrollData.id === this.libraryItemId && window['item-page']) {
        window['item-page'].scrollTop = this.$store.state.lastItemScrollData.scrollTop || 0
      }
    },
    async loadServerLibraryItem() {
      console.log(`Fetching library item "${this.libraryItemId}" from server`)
      const libraryItem = await this.$nativeHttp.get(`/api/items/${this.libraryItemId}?expanded=1&include=rssfeed`, { connectTimeout: 5000 }).catch((error) => {
        console.error('Failed', error)
        return null
      })

      if (libraryItem) {
        const localLibraryItem = await this.$db.getLocalLibraryItemByLId(this.libraryItemId)
        if (localLibraryItem) {
          console.log('Library item has local library item also', localLibraryItem.id)
          libraryItem.localLibraryItem = localLibraryItem
        }
        this.libraryItem = libraryItem
      } else if (this.$route.query.localLibraryItemId) {
        // Failed to get server library item but is local library item so redirect
        return this.$router.replace(`/item/${this.$route.query.localLibraryItemId}?noredirect=1`)
      } else {
        this.$toast.error('Failed to get library item from server')
        return this.$router.replace('/bookshelf')
      }
    }
  },
  async mounted() {
    if (!this.libraryItem) {
      await this.loadServerLibraryItem()
    }
    this.init()
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.windowResized)
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.$off('item_updated', this.itemUpdated)
    this.$socket.$off('rss_feed_open', this.rssFeedOpen)
    this.$socket.$off('rss_feed_closed', this.rssFeedClosed)

    // Set scroll position
    if (window['item-page']) {
      this.$store.commit('setLastItemScrollData', { scrollTop: window['item-page'].scrollTop || 0, id: this.libraryItemId })
    }
  }
}
</script>

<style>
:root {
  --item-page-bg-gradient-height: 100%;
}

#item-page-bg-gradient {
  transition: opacity 0.5s ease-in-out;
  height: var(--item-page-bg-gradient-height);
}

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
