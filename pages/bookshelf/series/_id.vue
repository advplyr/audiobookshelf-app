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
      missingFiles: 0,
      missingFilesSize: 0,
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
    async downloadSeriesClick() {
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
    async fetchSeriesEntities(page) {
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
          if (!(await this.$db.getLocalLibraryItem(`local_${payload.results[i].id}`))) {
            this.missingFiles += payload.results[i].numFiles
            this.missingFilesSize += payload.results[i].size
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

      // Fetch series data from server
      let page = 0
      let fetchFinished = false
      while (fetchFinished === false) {
        fetchFinished = await this.fetchSeriesEntities(page)
        page += 1
      }
      if (fetchFinished !== true) {
        console.error('failed to fetch series books data')
        return null
      }

      // Format message for dialog
      let startDownloadMessage = this.$getString('MessageSeriesDownloadConfirmIos', [this.libraryIds.length, this.missingFiles, this.$bytesPretty(this.missingFilesSize)])
      if (!this.isIos) {
        startDownloadMessage = this.$getString('MessageSeriesDownloadConfirm', [this.libraryIds.length, this.missingFiles, this.$bytesPretty(this.missingFilesSize), localFolder.name])
      }

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
    this.$eventBus.$on('download-series-click', this.downloadSeriesClick)
  },
  beforeDestroy() {
    this.$eventBus.$off('download-series-click', this.downloadSeriesClick)
  }
}
</script>
