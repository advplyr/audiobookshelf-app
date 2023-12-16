<template>
  <modals-fullscreen-modal v-model="show" :processing="processing">
    <div class="flex items-end justify-end h-24 pr-4 pb-2">
      <!-- <h1 class="text-lg">RSS Feed</h1> -->
      <button class="flex" @click="show = false">
        <span class="material-icons">close</span>
      </button>
    </div>

    <div class="w-full px-2 h-[calc(100%-176px)] overflow-y-auto">
      <div v-if="currentFeed" class="w-full">
        <div class="w-full relative">
          <h1 class="text-lg mb-4">{{ $strings.HeaderRSSFeedIsOpen }}</h1>

          <ui-text-input v-model="currentFeed.feedUrl" class="text-sm" readonly />

          <span class="material-icons absolute right-2 bottom-2 p-0.5 text-base" :class="linkCopied ? 'text-success' : 'text-fg-muted'" @click="copyToClipboard(currentFeed.feedUrl)">{{ linkCopied ? 'done' : 'content_copy' }}</span>
        </div>

        <div v-if="currentFeed.meta" class="mt-5">
          <div class="flex py-0.5">
            <div class="w-48">
              <span class="text-fg-muted uppercase text-sm">{{ $strings.LabelRSSFeedPreventIndexing }}</span>
            </div>
            <div>{{ currentFeed.meta.preventIndexing ? $strings.ButtonYes : $strings.LabelNo }}</div>
          </div>
          <div v-if="currentFeed.meta.ownerName" class="flex py-0.5">
            <div class="w-48">
              <span class="text-fg-muted uppercase text-sm">{{ $strings.LabelRSSFeedCustomOwnerName }}</span>
            </div>
            <div>{{ currentFeed.meta.ownerName }}</div>
          </div>
          <div v-if="currentFeed.meta.ownerEmail" class="flex py-0.5">
            <div class="w-48">
              <span class="text-fg-muted uppercase text-sm">{{ $strings.LabelRSSFeedCustomOwnerEmail }}</span>
            </div>
            <div>{{ currentFeed.meta.ownerEmail }}</div>
          </div>
        </div>
      </div>
      <div v-else class="w-full">
        <div class="w-full relative mb-2">
          <ui-text-input-with-label v-model="newFeedSlug" :label="$strings.LabelRSSFeedSlug" />
          <p class="text-xs text-fg-muted py-0.5 px-1">{{ $getString('MessageFeedURLWillBe', [demoFeedUrl]) }}</p>
        </div>
        <modals-rssfeeds-rss-feed-metadata-builder v-model="metadataDetails" />

        <p v-if="isHttp" class="w-full pt-2 text-warning text-xs">{{ $strings.NoteRSSFeedPodcastAppsHttps }}</p>
        <p v-if="hasEpisodesWithoutPubDate" class="w-full pt-2 text-warning text-xs">{{ $strings.NoteRSSFeedPodcastAppsPubDate }}</p>
      </div>
    </div>

    <div v-show="userIsAdminOrUp" class="flex items-start pt-2 h-20">
      <ui-btn v-if="currentFeed" color="error" class="w-full h-14" @click="closeFeed">{{ $strings.ButtonCloseFeed }}</ui-btn>
      <ui-btn v-else color="success" class="w-full h-14" @click="openFeed">{{ $strings.ButtonOpenFeed }}</ui-btn>
    </div>
  </modals-fullscreen-modal>
</template>

<script>
export default {
  data() {
    return {
      processing: false,
      newFeedSlug: null,
      currentFeed: null,
      metadataDetails: {
        preventIndexing: true,
        ownerName: '',
        ownerEmail: ''
      },
      linkCopied: false
    }
  },
  watch: {
    show: {
      immediate: true,
      handler(newVal) {
        if (newVal) {
          this.linkCopied = false
          this.init()
        }
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.globals.showRSSFeedOpenCloseModal
      },
      set(val) {
        this.$store.commit('globals/setShowRSSFeedOpenCloseModal', val)
      }
    },
    serverAddress() {
      return this.$store.getters['user/getServerAddress']
    },
    rssFeedEntity() {
      return this.$store.state.globals.rssFeedEntity || {}
    },
    entityId() {
      return this.rssFeedEntity.id
    },
    entityType() {
      return this.rssFeedEntity.type
    },
    entityFeed() {
      return this.rssFeedEntity.feed
    },
    hasEpisodesWithoutPubDate() {
      return !!this.rssFeedEntity.hasEpisodesWithoutPubDate
    },
    title() {
      return this.rssFeedEntity.name
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    demoFeedUrl() {
      return `${this.serverAddress}/feed/${this.newFeedSlug}`
    },
    isHttp() {
      return !!this.serverAddress?.startsWith('http://')
    }
  },
  methods: {
    openFeed() {
      if (!this.newFeedSlug) {
        this.$toast.error('Must set a feed slug')
        return
      }

      const sanitized = this.$sanitizeSlug(this.newFeedSlug)
      if (this.newFeedSlug !== sanitized) {
        this.newFeedSlug = sanitized
        this.$toast.warning('Slug had to be modified - Run again')
        return
      }

      const payload = {
        serverAddress: this.serverAddress,
        slug: this.newFeedSlug,
        metadataDetails: this.metadataDetails
      }

      console.log('Payload', payload)
      this.$nativeHttp
        .post(`/api/feeds/${this.entityType}/${this.entityId}/open`, payload)
        .then((data) => {
          console.log('Opened RSS Feed', data)
          this.currentFeed = data.feed
        })
        .catch((error) => {
          console.error('Failed to open RSS Feed', error)
          const errorMsg = error.response ? error.response.data : null
          this.$toast.error(errorMsg || 'Failed to open RSS Feed')
        })
    },
    async copyToClipboard(str) {
      await this.$copyToClipboard(str)
      this.linkCopied = true
    },
    closeFeed() {
      this.processing = true
      this.$nativeHttp
        .post(`/api/feeds/${this.currentFeed.id}/close`)
        .then(() => {
          this.$toast.success(this.$strings.ToastRSSFeedCloseSuccess)
          this.show = false
        })
        .catch((error) => {
          console.error('Failed to close RSS feed', error)
          this.$toast.error(this.$strings.ToastRSSFeedCloseFailed)
        })
        .finally(() => {
          this.processing = false
        })
    },
    init() {
      if (!this.entityId) return
      this.newFeedSlug = this.entityId
      this.currentFeed = this.entityFeed
    }
  },
  mounted() {}
}
</script>
