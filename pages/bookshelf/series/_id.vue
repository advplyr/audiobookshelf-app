<template>
  <bookshelf-lazy-bookshelf page="series-books" :series-id="seriesId" v-on:downloadSeriesClick="downloadSeriesClick" />
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

export default {
  async asyncData({ params, app, store, redirect }) {
    var series = await app.$nativeHttp.get(`/api/series/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })
    if (!series) {
      return redirect('/oops?message=Series not found')
    }
    store.commit('globals/setSeries', series)
    return {
      series,
      seriesId: params.id
    }
  },
  data() {
    return {
      startingDownload: false,
      mediaType: 'book',
      booksPerFetch: 20,
      books: 0,
      numFiles: 0,
      numAudioFiles: 0,
      libraryIds: []
    }
  },
  mixins: [cellularPermissionHelpers],
  computed: {
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    async downloadSerieClick() {
      console.log('Download Series clicked')
      if (this.startingDownload) return

      const hasPermission = await this.checkCellularPermission('download')
      if (!hasPermission) return

      this.startingDownload = true
      setTimeout(() => {
        this.startingDownload = false
      }, 1000)

      await this.$hapticsImpact()
      this.download()
    },
    buildSearchParams() {
      let searchParams = new URLSearchParams()
      searchParams.set('filter', `series.${this.$encode(this.seriesId)}`)
      return searchParams.toString()
    },
    async fetchSerieEntities(page) {
      const startIndex = page * this.booksPerFetch

      this.currentSFQueryString = this.buildSearchParams()

      const entityPath = `items`
      const sfQueryString = this.currentSFQueryString ? this.currentSFQueryString + '&' : ''
      const fullQueryString = `?${sfQueryString}limit=${this.booksPerFetch}&page=${page}&minified=1&include=rssfeed,numEpisodesIncomplete`

      const payload = await this.$nativeHttp.get(`/api/libraries/${this.series.libraryId}/${entityPath}${fullQueryString}`).catch((error) => {
        console.error('failed to fetch books', error)
        return null
      })

      if (payload && payload.results) {
        console.log('Received payload', payload)
        this.books = payload.total

        for (let i = 0; i < payload.results.length; i++) {
          console.log(payload.results[i].numFiles)
          if (!(await this.$db.getLocalLibraryItem(`local_${payload.results[i].id}`))) {
            this.numFiles += payload.results[i].numFiles
            this.numAudioFiles += payload.results[i].media.numAudioFiles
            this.libraryIds.push(payload.results[i].id)
          }
        }
      }
      let totalPages = Math.ceil(this.books / this.booksPerFetch)
      if (totalPages > page + 1) {
        return false
      }
      return true
    },
    async download(selectedLocalFolder = null) {
      // Get the local folder to download to
      let localFolder = selectedLocalFolder
      if (!this.isIos && !localFolder) {
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

      // Fetch serie data from server
      let page = 0
      let fetchFinished = false
      while (fetchFinished === false) {
        fetchFinished = await this.fetchSerieEntities(page)
        page += 1
      }
      if (fetchFinished !== true) {
        console.error('failed to fetch serie books data')
        return null
      }

      // Format message for dialog
      let startDownloadMessage = `Serie "${this.series.name}" has ${this.books} book${this.books == 1 ? '' : 's'}. Download missing ${this.libraryIds.length} book${this.libraryIds.length == 1 ? '' : 's'} with ${this.numFiles} file${this.numFiles == 1 ? '' : 's'}`
      let numEbookFiles = this.numFiles - this.numAudioFiles
      if (!this.isIos && numEbookFiles > 0) {
        if (this.numAudioFiles > 0) {
          startDownloadMessage = `Serie "${this.series.name}" has ${this.books} book${this.books == 1 ? '' : 's'}. Download missing ${this.libraryIds.length} book${this.libraryIds.length == 1 ? '' : 's'} with ${this.numAudioFiles} audio file${this.numAudioFiles == 1 ? '' : 's'} and ${numEbookFiles} ebook file${this.numEbookFiles == 1 ? '' : 's'}`
        } else {
          startDownloadMessage = `Serie "${this.series.name}" has ${this.books} book${this.books == 1 ? '' : 's'}. Download missing ${this.libraryIds.length} book${this.libraryIds.length == 1 ? '' : 's'} with ${this.numFiles} ebook file${this.numFiles == 1 ? '' : 's'}`
        }
      }
      if (!this.isIos) {
        startDownloadMessage += ` to folder ${localFolder.name}`
      }
      startDownloadMessage += `?`

      // Show confirmation dialog and start downloading if user chooses so
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: startDownloadMessage
      })
      if (value) {
        for (let i = 0; i < this.libraryIds.length; i++) {
          this.startDownload(localFolder, this.libraryIds[i])
        }
      }
      this.libraryIds = []
    },
    async startDownload(localFolder = null, libraryItemId) {
      const payload = {
        libraryItemId: libraryItemId
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
    }
  },
  mounted() {
    this.$eventBus.$on('download-serie-click', this.downloadSerieClick)
  },
  beforeDestroy() {
    this.$eventBus.$off('download-serie-click', this.downloadSerieClick)
  }
}
</script>
