<template>
  <div class="w-full px-2">
    <img v-if="podcast.imageUrl" :src="podcast.imageUrl" class="h-36 w-36 object-contain mx-auto mb-2" />

    <ui-text-input-with-label v-model="podcast.title" :label="'Title'" class="mb-2 text-sm" @input="titleUpdated" />

    <ui-text-input-with-label v-model="podcast.author" :label="'Author'" class="mb-2 text-sm" />

    <ui-text-input-with-label v-model="podcast.feedUrl" :label="'Feed URL'" readonly class="mb-2 text-sm" />

    <ui-multi-select v-model="podcast.genres" :items="podcast.genres" :label="'Genres'" class="mb-2 text-sm" />

    <ui-textarea-with-label v-model="podcast.description" :label="'Description'" :rows="3" class="mb-2 text-sm" />

    <ui-dropdown v-model="selectedFolderId" :items="folderItems" :disabled="processing" :label="'Folder'" class="mb-2 text-sm" @input="folderUpdated" />

    <ui-text-input-with-label v-model="fullPath" :label="'Podcast Path'" input-class="h-10" readonly class="mb-2 text-sm" />

    <div class="flex items-center py-4 px-2">
      <ui-checkbox v-model="podcast.autoDownloadEpisodes" :label="'Auto Download Episodes'" checkbox-bg="primary" border-color="gray-600" label-class="pl-2 text-sm font-semibold" />
      <div class="flex-grow" />
      <ui-btn color="success" @click="submit">Submit</ui-btn>
    </div>
  </div>
</template>

<script>
import Path from 'path'

export default {
  props: {
    processing: Boolean,
    podcastData: {
      type: Object,
      default: () => null
    },
    podcastFeedData: {
      type: Object,
      default: () => null
    }
  },
  data() {
    return {
      selectedFolderId: null,
      fullPath: null,
      podcast: {
        title: '',
        author: '',
        description: '',
        releaseDate: '',
        genres: [],
        feedUrl: '',
        feedImageUrl: '',
        itunesPageUrl: '',
        itunesId: '',
        itunesArtistId: '',
        autoDownloadEpisodes: false
      }
    }
  },
  computed: {
    _processing: {
      get() {
        return this.processing
      },
      set(val) {
        this.$emit('update:processing', val)
      }
    },
    title() {
      return this._podcastData.title
    },
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    folders() {
      if (!this.currentLibrary) return []
      return this.currentLibrary.folders || []
    },
    folderItems() {
      return this.folders.map((fold) => {
        return {
          value: fold.id,
          text: fold.fullPath
        }
      })
    },
    _podcastData() {
      return this.podcastData || {}
    },
    feedMetadata() {
      if (!this.podcastFeedData) return {}
      return this.podcastFeedData.metadata || {}
    },
    episodes() {
      if (!this.podcastFeedData) return []
      return this.podcastFeedData.episodes || []
    },
    selectedFolder() {
      return this.folders.find((f) => f.id === this.selectedFolderId)
    },
    selectedFolderPath() {
      if (!this.selectedFolder) return ''
      return this.selectedFolder.fullPath
    }
  },
  methods: {
    titleUpdated() {
      this.folderUpdated()
    },
    folderUpdated() {
      if (!this.selectedFolderPath || !this.podcast.title) {
        this.fullPath = ''
        return
      }
      this.fullPath = Path.join(this.selectedFolderPath, this.$sanitizeFilename(this.podcast.title))
    },
    submit() {
      const podcastPayload = {
        path: this.fullPath,
        folderId: this.selectedFolderId,
        libraryId: this.currentLibrary.id,
        media: {
          metadata: {
            title: this.podcast.title,
            author: this.podcast.author,
            description: this.podcast.description,
            releaseDate: this.podcast.releaseDate,
            genres: [...this.podcast.genres],
            feedUrl: this.podcast.feedUrl,
            imageUrl: this.podcast.imageUrl,
            itunesPageUrl: this.podcast.itunesPageUrl,
            itunesId: this.podcast.itunesId,
            itunesArtistId: this.podcast.itunesArtistId,
            language: this.podcast.language
          },
          autoDownloadEpisodes: this.podcast.autoDownloadEpisodes
        }
      }
      console.log('Podcast payload', podcastPayload)

      this._processing = true
      this.$nativeHttp
        .post('/api/podcasts', podcastPayload)
        .then((libraryItem) => {
          this._processing = false
          this.$toast.success('Podcast added')
          this.$router.push(`/item/${libraryItem.id}`)
        })
        .catch((error) => {
          var errorMsg = error.response && error.response.data ? error.response.data : 'Failed to add podcast'
          console.error('Failed to create podcast', error)
          this._processing = false
          this.$toast.error(errorMsg)
        })
    },
    init() {
      // Prefer using itunes podcast data but not always passed in if manually entering rss feed
      this.podcast.title = this._podcastData.title || this.feedMetadata.title || ''
      this.podcast.author = this._podcastData.artistName || this.feedMetadata.author || ''
      this.podcast.description = this._podcastData.description || this.feedMetadata.descriptionPlain || ''
      this.podcast.releaseDate = this._podcastData.releaseDate || ''
      this.podcast.genres = this._podcastData.genres || this.feedMetadata.categories || []
      this.podcast.feedUrl = this._podcastData.feedUrl || this.feedMetadata.feedUrl || ''
      this.podcast.imageUrl = this._podcastData.cover || this.feedMetadata.image || ''
      this.podcast.itunesPageUrl = this._podcastData.pageUrl || ''
      this.podcast.itunesId = this._podcastData.id || ''
      this.podcast.itunesArtistId = this._podcastData.artistId || ''
      this.podcast.language = this._podcastData.language || ''
      this.podcast.autoDownloadEpisodes = false

      if (this.folderItems[0]) {
        this.selectedFolderId = this.folderItems[0].value
        this.folderUpdated()
      }
    }
  },
  mounted() {
    this.init()
  }
}
</script>