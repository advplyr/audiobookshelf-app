<template>
  <div class="w-full h-full py-6 px-2">
    <div v-if="localLibraryItem" class="w-full h-full">
      <div class="px-2 flex items-center mb-2">
        <p class="text-base font-semibold truncate">{{ mediaMetadata.title }}</p>
        <div class="flex-grow" />

        <button v-if="audioTracks.length && !isPodcast" class="shadow-sm text-success flex items-center justify-center rounded-full mx-2" @click.stop="play">
          <span class="material-icons" style="font-size: 2rem">play_arrow</span>
        </button>
        <span class="material-icons" @click="showItemDialog">more_vert</span>
      </div>

      <p v-if="!isIos" class="px-2 text-sm mb-0.5 text-fg-muted">{{ $strings.LabelFolder }}: {{ folderName }}</p>

      <p class="px-2 mb-4 text-xs text-fg-muted">{{ libraryItemId ? 'Linked to item on server ' + liServerAddress : 'Not linked to server item' }}</p>

      <div class="w-full max-w-full media-item-container overflow-y-auto overflow-x-hidden relative pb-4" :class="{ 'media-order-changed': orderChanged }">
        <div v-if="!isPodcast && audioTracksCopy.length" class="w-full py-2">
          <div class="flex justify-between items-center mb-2">
            <p class="text-base">Audio Tracks ({{ audioTracks.length }})</p>
            <p class="text-xs text-fg-muted px-2">{{ $strings.LabelTotalSize }}: {{ $bytesPretty(totalAudioSize) }}</p>
          </div>

          <draggable v-model="audioTracksCopy" v-bind="dragOptions" handle=".drag-handle" draggable=".item" tag="div" @start="drag = true" @end="drag = false" @update="draggableUpdate" :disabled="isIos">
            <transition-group type="transition" :name="!drag ? 'dragtrack' : null">
              <template v-for="track in audioTracksCopy">
                <div :key="track.localFileId" class="flex items-center my-1 item">
                  <div v-if="!isIos" class="w-8 h-12 flex items-center justify-center" style="min-width: 32px">
                    <span class="material-icons drag-handle text-lg text-fg-muted">menu</span>
                  </div>
                  <div class="w-8 h-12 flex items-center justify-center" style="min-width: 32px">
                    <p class="font-mono font-bold text-xl">{{ track.index }}</p>
                  </div>
                  <div class="flex-grow px-2">
                    <p class="text-xs">{{ track.title }}</p>
                  </div>
                  <div class="w-20 text-center text-fg-muted" style="min-width: 80px">
                    <p class="text-xs">{{ track.mimeType }}</p>
                    <p class="text-sm">{{ $elapsedPretty(track.duration) }}</p>
                  </div>
                  <div v-if="!isIos" class="w-12 h-12 flex items-center justify-center" style="min-width: 48px">
                    <span class="material-icons" @click="showTrackDialog(track)">more_vert</span>
                  </div>
                </div>
              </template>
            </transition-group>
          </draggable>
        </div>

        <div v-if="isPodcast" class="w-full py-2">
          <div class="flex justify-between items-center mb-2">
            <p class="text-base">Episodes ({{ episodes.length }})</p>
            <p class="text-xs text-fg-muted px-2">{{ $strings.LabelTotalSize }}: {{ $bytesPretty(totalEpisodesSize) }}</p>
          </div>
          <template v-for="episode in episodes">
            <div :key="episode.id" class="flex items-center my-1">
              <div class="w-10 h-12 flex items-center justify-center" style="min-width: 48px">
                <p class="font-mono font-bold text-xl">{{ episode.index }}</p>
              </div>
              <div class="flex-grow px-2">
                <p class="text-xs">{{ episode.title }}</p>
              </div>
              <div class="w-20 text-center text-fg-muted" style="min-width: 80px">
                <p class="text-xs">{{ episode.audioTrack.mimeType }}</p>
                <p class="text-sm">{{ $elapsedPretty(episode.audioTrack.duration) }}</p>
              </div>
              <div class="w-12 h-12 flex items-center justify-center" style="min-width: 48px">
                <span class="material-icons" @click="showTrackDialog(episode)">more_vert</span>
              </div>
            </div>
          </template>
        </div>

        <div v-if="localFileForEbook" class="w-full py-2">
          <p class="text-base mb-2">EBook File</p>

          <div class="flex items-center my-1">
            <div class="w-10 h-12 flex items-center justify-center" style="min-width: 40px">
              <p class="font-mono font-bold text-sm">{{ ebookFile.ebookFormat }}</p>
            </div>
            <div class="flex-grow px-2">
              <p class="text-xs">{{ localFileForEbook.filename }}</p>
            </div>
            <div class="w-24 text-center text-fg-muted" style="min-width: 96px">
              <p class="text-xs">{{ localFileForEbook.mimeType }}</p>
              <p class="text-sm">{{ $bytesPretty(localFileForEbook.size) }}</p>
            </div>
          </div>
        </div>

        <div v-if="otherFiles.length">
          <div class="flex justify-between items-center py-2">
            <p class="text-lg">Other Files</p>
            <p class="text-xs text-fg-muted px-2">{{ $strings.LabelTotalSize }}: {{ $bytesPretty(totalOtherFilesSize) }}</p>
          </div>
          <template v-for="file in otherFiles">
            <div :key="file.id" class="flex items-center my-1">
              <div class="w-12 h-12 flex items-center justify-center">
                <img v-if="(file.mimeType || '').startsWith('image')" :src="getCapImageSrc(file.contentUrl)" class="w-full h-full object-contain" />
                <span v-else class="material-icons">music_note</span>
              </div>
              <div class="flex-grow px-2">
                <p class="text-sm">{{ file.filename }}</p>
              </div>
              <div class="w-24 text-center text-fg-muted" style="min-width: 96px">
                <p class="text-xs">{{ file.mimeType }}</p>
                <p class="text-sm">{{ $bytesPretty(file.size) }}</p>
              </div>
            </div>
          </template>
        </div>

        <div class="mt-4 text-sm text-fg-muted">
          {{ $strings.LabelTotalSize }}: {{ $bytesPretty(totalLibraryItemSize) }}
        </div>
      </div>
    </div>
    <div v-else class="px-2 w-full h-full">
      <p class="text-lg text-center px-8">{{ failed ? 'Failed to get local library item ' + localLibraryItemId : 'Loading..' }}</p>
    </div>

    <div v-if="orderChanged" class="fixed left-0 w-full py-4 px-4 bg-bg box-shadow-book flex items-center" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <div class="flex-grow" />
      <ui-btn small color="success" @click="saveTrackOrder">{{ $strings.ButtonSaveOrder }}</ui-btn>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
  </div>
</template>

<script>
import draggable from 'vuedraggable'

import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  components: {
    draggable
  },
  asyncData({ params }) {
    return {
      localLibraryItemId: params.id
    }
  },
  data() {
    return {
      drag: false,
      dragOptions: {
        animation: 200,
        group: 'description',
        delay: 40,
        delayOnTouchOnly: true
      },
      failed: false,
      localLibraryItem: null,
      audioTracksCopy: [],
      removingItem: false,
      folderId: null,
      folder: null,
      showDialog: false,
      selectedAudioTrack: null,
      selectedEpisode: null,
      orderChanged: false
    }
  },
  computed: {
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    isIos() {
      return this.$platform === 'ios'
    },
    basePath() {
      return this.localLibraryItem?.basePath
    },
    localFiles() {
      return this.localLibraryItem?.localFiles || []
    },
    otherFiles() {
      if (!this.localFiles.filter) {
        console.error('Invalid local files', this.localFiles)
        return []
      }
      return this.localFiles.filter((lf) => {
        if (this.localFileForEbook?.id === lf.id) return false
        return !this.audioTracks.find((at) => at.localFileId == lf.id)
      })
    },
    folderName() {
      return this.folder?.name
    },
    isInternalStorage() {
      return this.folderId?.startsWith('internal-')
    },
    mediaType() {
      return this.localLibraryItem?.mediaType
    },
    isPodcast() {
      return this.mediaType == 'podcast'
    },
    libraryItemId() {
      return this.localLibraryItem?.libraryItemId
    },
    liServerAddress() {
      return this.localLibraryItem?.serverAddress
    },
    media() {
      return this.localLibraryItem?.media
    },
    mediaMetadata() {
      return this.media?.metadata || {}
    },
    ebookFile() {
      return this.media?.ebookFile
    },
    localFileForEbook() {
      if (!this.ebookFile) return null
      return this.localFiles.find((lf) => lf.id == this.ebookFile.localFileId)
    },
    episodes() {
      return this.media.episodes || []
    },
    audioTracks() {
      if (!this.media) return []
      if (this.mediaType == 'book') {
        return this.media.tracks || []
      } else {
        return (this.media.episodes || []).map((ep) => ep.audioTrack)
      }
    },
    dialogItems() {
      if (this.selectedAudioTrack || this.selectedEpisode) {
        const items = [
          {
            text: this.$strings.ButtonDeleteLocalFile,
            value: 'track-delete',
            icon: 'delete'
          }
        ]
        if (this.isPodcast && this.selectedEpisode) {
          items.unshift({
            text: this.$strings.ButtonPlayEpisode,
            value: 'play-episode',
            icon: 'play_arrow'
          })
        }
        return items
      } else {
        return [
          {
            text: this.$strings.ButtonDeleteLocalItem,
            value: 'delete',
            icon: 'delete'
          }
        ]
      }
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    totalAudioSize() {
      return this.audioTracks.reduce((acc, item) => item.metadata ? acc + item.metadata.size : acc, 0)
    },
    totalEpisodesSize() {
      return this.episodes.reduce((acc, item) => acc + item.size, 0)
    },
    totalOtherFilesSize() {
      return this.otherFiles.reduce((acc, item) => acc + item.size, 0)
    },
    totalLibraryItemSize() {
      return this.localFiles.reduce((acc, item) => acc + item.size, 0)
    }
  },
  methods: {
    draggableUpdate() {
      for (let i = 0; i < this.audioTracksCopy.length; i++) {
        var trackCopy = this.audioTracksCopy[i]
        var track = this.audioTracks[i]
        if (track.localFileId !== trackCopy.localFileId) {
          this.orderChanged = true
          return
        }
      }
      this.orderChanged = false
    },
    async saveTrackOrder() {
      var copyOfCopy = this.audioTracksCopy.map((at) => ({ ...at }))
      const payload = {
        localLibraryItemId: this.localLibraryItemId,
        tracks: copyOfCopy
      }
      var response = await this.$db.updateLocalTrackOrder(payload)
      if (response) {
        this.$toast.success('Library item updated')
        console.log('updateLocal track order response', JSON.stringify(response))
        this.localLibraryItem = response
        this.audioTracksCopy = this.audioTracks.map((at) => ({ ...at }))
      } else {
        this.$toast.info(this.$strings.MessageNoUpdatesWereNecessary)
      }
      this.orderChanged = false
    },
    showItemDialog() {
      this.selectedAudioTrack = null
      this.selectedEpisode = null
      this.showDialog = true
    },
    showTrackDialog(track) {
      if (this.isPodcast) {
        this.selectedAudioTrack = null
        this.selectedEpisode = track
      } else {
        this.selectedEpisode = null
        this.selectedAudioTrack = track
      }
      this.showDialog = true
    },
    async play() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()
      this.$store.commit('setPlayerIsStartingPlayback', this.localLibraryItemId)
      this.$eventBus.$emit('play-item', { libraryItemId: this.localLibraryItemId })
    },
    getCapImageSrc(contentUrl) {
      return Capacitor.convertFileSrc(contentUrl)
    },
    async playEpisode() {
      if (!this.selectedEpisode) return
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()
      this.$store.commit('setPlayerIsStartingPlayback', this.selectedEpisode.serverEpisodeId)

      this.$eventBus.$emit('play-item', {
        libraryItemId: this.localLibraryItemId,
        episodeId: this.selectedEpisode.id,
        serverLibraryItemId: this.libraryItemId,
        serverEpisodeId: this.selectedEpisode.serverEpisodeId
      })
    },
    async dialogAction(action) {
      console.log('Dialog action', action)
      await this.$hapticsImpact()

      if (action == 'delete') {
        this.deleteItem()
      } else if (action == 'track-delete') {
        if (this.isPodcast) this.deleteEpisode()
        else this.deleteTrack()
      } else if (action == 'play-episode') {
        this.playEpisode()
      }
      this.showDialog = false;
    },
    getLocalFileForTrack(localFileId) {
      return this.localFiles.find((lf) => lf.id == localFileId)
    },
    async deleteEpisode() {
      if (!this.selectedEpisode) return
      var localFile = this.getLocalFileForTrack(this.selectedEpisode.audioTrack.localFileId)
      if (!localFile) {
        this.$toast.error('Audio track does not have matching local file..')
        return
      }

      let confirmMessage = `Remove local audio file "${localFile.basePath}" from your device?`
      if (this.libraryItemId) {
        confirmMessage += ' The file on the server will be unaffected.'
      }
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: confirmMessage
      })
      if (value) {
        var res = await AbsFileSystem.deleteTrackFromItem({ id: this.localLibraryItem.id, trackLocalFileId: localFile.id, trackContentUrl: this.selectedEpisode.audioTrack.contentUrl })
        if (res && res.id) {
          this.$toast.success('Deleted track successfully')
          this.localLibraryItem = res
        } else this.$toast.error('Failed to delete')
      }
    },
    async deleteTrack() {
      if (!this.selectedAudioTrack) {
        return
      }
      var localFile = this.getLocalFileForTrack(this.selectedAudioTrack.localFileId)
      if (!localFile) {
        this.$toast.error('Audio track does not have matching local file..')
        return
      }

      let confirmMessage = `Remove local audio file "${localFile.basePath}" from your device?`
      if (this.libraryItemId) {
        confirmMessage += ' The file on the server will be unaffected.'
      }
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: confirmMessage
      })
      if (value) {
        var res = await AbsFileSystem.deleteTrackFromItem({ id: this.localLibraryItem.id, trackLocalFileId: this.selectedAudioTrack.localFileId, trackContentUrl: this.selectedAudioTrack.contentUrl })
        if (res && res.id) {
          this.$toast.success('Deleted track successfully')
          this.localLibraryItem = res
        } else this.$toast.error('Failed to delete')
      }
    },
    async deleteItem() {
      let confirmMessage = 'Remove local files of this item from your device?'
      if (this.libraryItemId) {
        confirmMessage += ' The files on the server and your progress will be unaffected.'
      }
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: confirmMessage
      })
      if (value) {
        var res = await AbsFileSystem.deleteItem(this.localLibraryItem)
        if (res && res.success) {
          this.$toast.success('Deleted Successfully')
          this.$router.replace(this.isIos ? '/downloads' : `/localMedia/folders/${this.folderId}`)
        } else this.$toast.error('Failed to delete')
      }
    },
    async init() {
      this.localLibraryItem = await this.$db.getLocalLibraryItem(this.localLibraryItemId)

      if (!this.localLibraryItem) {
        console.error('Failed to get local library item', this.localLibraryItemId)
        this.failed = true
        return
      }

      this.audioTracksCopy = this.audioTracks.map((at) => ({ ...at }))

      this.folderId = this.localLibraryItem.folderId
      this.folder = await this.$db.getLocalFolder(this.folderId)
    }
  },
  mounted() {
    this.init()
  }
}
</script>

<style scoped>
.media-item-container {
  height: calc(100vh - 200px);
  max-height: calc(100vh - 200px);
}
.media-item-container.media-order-changed {
  height: calc(100vh - 280px);
  max-height: calc(100vh - 280px);
}
.playerOpen .media-item-container {
  height: calc(100vh - 300px);
  max-height: calc(100vh - 300px);
}
.playerOpen .media-item-container.media-order-changed {
  height: calc(100vh - 380px);
  max-height: calc(100vh - 380px);
}
.sortable-ghost {
  opacity: 0.5;
}
.dragtrack-enter-from,
.dragtrack-leave-to {
  opacity: 0;
  transform: translateX(30px);
}

.dragtrack-leave-active {
  position: absolute;
}
</style>
