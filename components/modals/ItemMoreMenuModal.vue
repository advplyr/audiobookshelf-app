<template>
  <div>
    <modals-dialog v-model="show" :items="moreMenuItems" @action="moreMenuAction" />
    <modals-item-details-modal v-model="showDetailsModal" :library-item="libraryItem" />
    <modals-dialog v-model="showSendEbookDevicesModal" :title="$strings.LabelSelectADevice" :items="ereaderDeviceItems" @action="sendEbookToDeviceAction" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  props: {
    value: Boolean,
    processing: Boolean,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    episode: {
      type: Object,
      default: () => {}
    },
    rssFeed: {
      type: Object,
      default: () => null
    },
    playlist: {
      type: Object,
      default: () => null
    },
    hideRssFeedOption: Boolean
  },
  data() {
    return {
      showDetailsModal: false,
      showSendEbookDevicesModal: false
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    moreMenuItems() {
      const items = []

      // TODO: Implement on iOS
      if (this.$platform !== 'ios' && !this.isPodcast) {
        items.push({
          text: this.$strings.ButtonHistory,
          value: 'history',
          icon: 'history'
        })
      }

      if (!this.isPodcast || this.episode) {
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
      }

      if ((!this.isPodcast && this.serverLibraryItemId) || (this.episode && this.serverEpisodeId)) {
        items.push({
          text: this.$strings.LabelAddToPlaylist,
          value: 'playlist',
          icon: 'playlist_add'
        })

        if (this.ereaderDeviceItems.length) {
          items.push({
            text: this.$strings.ButtonSendEbookToDevice,
            value: 'sendEbook',
            icon: 'send'
          })
        }
      }

      // If on playlist page show remove from playlist option
      if (this.playlist) {
        items.push({
          text: this.$strings.LabelRemoveFromPlaylist,
          value: 'removeFromPlaylist',
          icon: 'playlist_remove'
        })
      }

      if (this.showRSSFeedOption) {
        items.push({
          text: this.rssFeed ? this.$strings.HeaderRSSFeed : this.$strings.HeaderOpenRSSFeed,
          value: 'rssFeed',
          icon: 'rss_feed'
        })
      }

      if (this.localLibraryItemId) {
        items.push({
          text: this.$strings.ButtonManageLocalFiles,
          value: 'manageLocal',
          icon: 'folder'
        })

        if (!this.isPodcast) {
          items.push({
            text: this.$strings.ButtonDeleteLocalItem,
            value: 'deleteLocal',
            icon: 'delete'
          })
        } else if (this.localEpisodeId) {
          items.push({
            text: this.$strings.ButtonDeleteLocalEpisode,
            value: 'deleteLocalEpisode',
            icon: 'delete'
          })
        }
      }

      if (this.isConnectedToServer) {
        items.push({
          text: this.$strings.ButtonGoToWebClient,
          value: 'openWebClient',
          icon: 'language'
        })
      }

      if (!this.episode) {
        items.push({
          text: this.$strings.LabelMoreInfo,
          value: 'details',
          icon: 'info'
        })
      }

      return items
    },
    ereaderDeviceItems() {
      if (!this.ebookFile || !this.$store.state.libraries.ereaderDevices?.length) return []
      return this.$store.state.libraries.ereaderDevices.map((d) => {
        return {
          text: d.name,
          value: d.name
        }
      })
    },
    isConnectedToServer() {
      if (!this.isLocal) return true
      if (!this.libraryItem?.serverAddress) return false
      return this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress
    },
    isLocal() {
      return !!this.libraryItem?.isLocal
    },
    localLibraryItem() {
      if (this.isLocal) return this.libraryItem
      return this.libraryItem?.localLibraryItem || null
    },
    localLibraryItemId() {
      return this.localLibraryItem?.id || null
    },
    serverLibraryItemId() {
      if (!this.isLocal) return this.libraryItem?.id
      if (this.isConnectedToServer) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    localEpisode() {
      if (this.isLocal) return this.episode
      return this.episode?.localEpisode
    },
    localEpisodeId() {
      return this.localEpisode?.id || null
    },
    serverEpisodeId() {
      if (!this.isLocal) return this.episode?.id
      if (this.isConnectedToServer) {
        return this.episode.serverEpisodeId
      }
      return null
    },
    mediaType() {
      return this.libraryItem?.mediaType
    },
    isPodcast() {
      return this.mediaType == 'podcast'
    },
    media() {
      return this.libraryItem?.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    title() {
      return this.mediaMetadata.title
    },
    tracks() {
      return this.media.tracks || []
    },
    episodes() {
      return this.media.episodes || []
    },
    ebookFile() {
      return this.media.ebookFile
    },
    localItemProgress() {
      if (this.isPodcast) {
        if (!this.localEpisodeId) return null
        return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId, this.localEpisodeId)
      }
      return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId)
    },
    serverItemProgress() {
      if (this.isPodcast) {
        if (!this.serverEpisodeId) return null
        return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId, this.serverEpisodeId)
      }
      return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId)
    },
    userItemProgress() {
      if (this.isLocal) return this.localItemProgress
      return this.serverItemProgress
    },
    userIsFinished() {
      return !!this.userItemProgress?.isFinished
    },
    useEBookProgress() {
      if (!this.userItemProgress || this.userItemProgress.progress) return false
      return this.userItemProgress.ebookProgress > 0
    },
    progressPercent() {
      if (this.useEBookProgress) return Math.max(Math.min(1, this.userItemProgress.ebookProgress), 0)
      return Math.max(Math.min(1, this.userItemProgress?.progress || 0), 0)
    },
    showRSSFeedOption() {
      if (this.hideRssFeedOption) return false
      if (!this.serverLibraryItemId) return false
      if (!this.rssFeed && !this.episodes.length && !this.tracks.length) return false // Cannot open RSS feed with no episodes/tracks

      // If rss feed is open then show feed url to users otherwise just show to admins
      return this.userIsAdminOrUp || this.rssFeed
    },
    mediaId() {
      if (this.isPodcast) return null
      return this.serverLibraryItemId || this.localLibraryItemId
    }
  },
  methods: {
    moreMenuAction(action) {
      this.show = false
      if (action === 'manageLocal') {
        this.$nextTick(() => {
          this.$router.push(`/localMedia/item/${this.localLibraryItemId}`)
        })
      } else if (action === 'details') {
        this.showDetailsModal = true
      } else if (action === 'playlist') {
        this.$store.commit('globals/setSelectedPlaylistItems', [{ libraryItem: this.libraryItem, episode: this.episode }])
        this.$store.commit('globals/setShowPlaylistsAddCreateModal', true)
      } else if (action === 'removeFromPlaylist') {
        this.removeFromPlaylistClick()
      } else if (action === 'markFinished') {
        if (this.episode) this.toggleEpisodeFinished()
        else this.toggleFinished()
      } else if (action === 'history') {
        this.$router.push(`/media/${this.mediaId}/history?title=${this.title}`)
      } else if (action === 'discardProgress') {
        this.clearProgressClick()
      } else if (action === 'deleteLocal') {
        this.deleteLocalItem()
      } else if (action === 'deleteLocalEpisode') {
        this.deleteLocalEpisode()
      } else if (action === 'rssFeed') {
        this.clickRSSFeed()
      } else if (action === 'sendEbook') {
        this.showSendEbookDevicesModal = true
      } else if (action === 'openWebClient') {
        this.$store.dispatch('user/openWebClient', `/item/${this.serverLibraryItemId}`)
      }
    },
    async toggleFinished() {
      await this.$hapticsImpact()

      // Show confirm if item has progress since it will reset
      if (this.userItemProgress && this.userItemProgress.progress > 0 && !this.userIsFinished) {
        const { value } = await Dialog.confirm({
          title: 'Confirm',
          message: this.$strings.MessageConfirmMarkAsFinished
        })
        if (!value) return
      }

      this.$emit('update:processing', true)
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
      } else {
        const updatePayload = {
          isFinished: !this.userIsFinished
        }
        await this.$nativeHttp.patch(`/api/me/progress/${this.serverLibraryItemId}`, updatePayload).catch((error) => {
          console.error('Failed', error)
          this.$toast.error(updatePayload.isFinished ? this.$strings.ToastItemMarkedAsFinishedFailed : this.$strings.ToastItemMarkedAsNotFinishedFailed)
        })
      }
      this.$emit('update:processing', false)
    },
    async toggleEpisodeFinished() {
      await this.$hapticsImpact()

      this.$emit('update:processing', true)
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
        await this.$nativeHttp.patch(`/api/me/progress/${this.serverLibraryItemId}/${this.serverEpisodeId}`, updatePayload).catch((error) => {
          console.error('Failed', error)
          this.$toast.error(updatePayload.isFinished ? this.$strings.ToastItemMarkedAsFinishedFailed : this.$strings.ToastItemMarkedAsNotFinishedFailed)
        })
      }
      this.$emit('update:processing', false)
    },
    async clearProgressClick() {
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: this.$strings.MessageConfirmDiscardProgress
      })
      if (value) {
        this.$emit('update:processing', true)
        const serverMediaProgressId = this.serverItemProgress?.id
        if (this.localItemProgress) {
          await this.$db.removeLocalMediaProgress(this.localItemProgress.id)
          this.$store.commit('globals/removeLocalMediaProgressForItem', this.localItemProgress.id)
        }

        if (serverMediaProgressId) {
          await this.$nativeHttp
            .delete(`/api/me/progress/${serverMediaProgressId}`)
            .then(() => {
              console.log('Progress reset complete')
              this.$toast.success(`Your progress was reset`)
              this.$store.commit('user/removeMediaProgress', serverMediaProgressId)
            })
            .catch((error) => {
              console.error('Progress reset failed', error)
            })
        }

        this.$emit('update:processing', false)
      }
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
    async deleteLocalItem() {
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: this.$strings.MessageConfirmDeleteLocalFiles
      })
      if (value) {
        const res = await AbsFileSystem.deleteItem(this.localLibraryItem)
        if (res?.success) {
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
    clickRSSFeed() {
      this.$store.commit('globals/setRSSFeedOpenCloseModal', {
        id: this.serverLibraryItemId,
        name: this.title,
        type: 'item',
        feed: this.rssFeed,
        hasEpisodesWithoutPubDate: this.episodes.some((ep) => !ep.pubDate)
      })
    },
    sendEbookToDeviceAction(deviceName) {
      this.showSendEbookDevicesModal = false

      const payload = {
        libraryItemId: this.serverLibraryItemId,
        deviceName
      }
      this.$emit('update:processing', true)
      this.$nativeHttp
        .post(`/api/emails/send-ebook-to-device`, payload)
        .then(() => {
          this.$toast.success('Ebook sent successfully')
        })
        .catch((error) => {
          console.error('Failed to send ebook to device', error)
          this.$toast.error('Failed to send ebook to device')
        })
        .finally(() => {
          this.$emit('update:processing', false)
        })
    },
    removeFromPlaylistClick() {
      if (!this.playlist) {
        this.$toast.error('Invalid: No Playlist')
        return
      }

      this.$emit('update:processing', true)
      let url = `/api/playlists/${this.playlist.id}/item/${this.serverLibraryItemId}`
      if (this.serverEpisodeId) url += `/${this.serverEpisodeId}`
      this.$nativeHttp
        .delete(url)
        .then(() => {
          this.$toast.success('Item removed from playlist')
        })
        .catch((error) => {
          const errorMsg = error.response?.data || 'Unknown error'
          console.error('Failed to remove item from playlist', error)
          this.$toast.error('Failed to remove from playlist: ' + errorMsg)
        })
        .finally(() => {
          this.$emit('update:processing', false)
        })
    }
  },
  mounted() {}
}
</script>