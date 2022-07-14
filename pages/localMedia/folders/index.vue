<template>
  <div class="w-full h-full py-6">
    <div v-if="lastLocalMediaSyncResults" class="px-2 mb-4">
      <div class="w-full pl-2 pr-2 py-2 bg-black bg-opacity-25 rounded-lg relative">
        <div class="flex items-center mb-1">
          <span class="material-icons text-success text-xl">sync</span>
          <p class="text-sm text-gray-300 pl-2">Local media progress synced with server</p>
        </div>
        <div class="flex justify-between mb-1.5">
          <p class="text-xs text-gray-400 font-semibold">{{ syncedServerConfigName }}</p>
          <p class="text-xs text-gray-400 italic">{{ $dateDistanceFromNow(syncedAt) }}</p>
        </div>

        <div v-if="!numLocalProgressUpdates && !numServerProgressUpdates">
          <p class="text-sm text-gray-300">Local media progress was up-to-date with server ({{ numLocalMediaSynced }} item{{ numLocalMediaSynced == 1 ? '' : 's' }})</p>
        </div>
        <div v-else>
          <p v-if="numServerProgressUpdates" class="text-sm text-gray-300">- {{ numServerProgressUpdates }} local media item{{ numServerProgressUpdates === 1 ? '' : 's' }} progress was updated on the server (local more recent).</p>
          <p v-else class="text-sm text-gray-300">- No local media progress had to be synced on the server.</p>
          <p v-if="numLocalProgressUpdates" class="text-sm text-gray-300">- {{ numLocalProgressUpdates }} local media item{{ numLocalProgressUpdates === 1 ? '' : 's' }} progress was updated to match the server (server more recent).</p>
          <p v-else class="text-sm text-gray-300">- No server progress had to be synced with local media progress.</p>
        </div>
      </div>
    </div>

    <h1 class="text-base font-semibold px-3 mb-2">Local Folders</h1>

    <div v-if="!isIos" class="w-full max-w-full px-3 py-2">
      <template v-for="folder in localFolders">
        <nuxt-link :to="`/localMedia/folders/${folder.id}`" :key="folder.id" class="flex items-center px-2 py-4 bg-primary rounded-md border-bg mb-1">
          <span class="material-icons text-xl text-yellow-400">folder</span>
          <p class="ml-2">{{ folder.name }}</p>
          <div class="flex-grow" />
          <p class="text-sm italic text-gray-300 px-3 capitalize">{{ folder.mediaType }}s</p>
          <span class="material-icons text-xl text-gray-300">arrow_right</span>
        </nuxt-link>
      </template>
      <div v-if="!localFolders.length" class="flex justify-center">
        <p class="text-center">No Media Folders</p>
      </div>
      <div class="flex border-t border-white border-opacity-10 my-4 py-4">
        <div class="flex-grow pr-1">
          <ui-dropdown v-model="newFolderMediaType" placeholder="Select media type" :items="mediaTypeItems" />
        </div>
        <ui-btn small class="w-28" color="success" @click="selectFolder">New Folder</ui-btn>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  data() {
    return {
      localFolders: [],
      newFolderMediaType: null,
      mediaTypeItems: [
        {
          value: 'book',
          text: 'Books'
        },
        {
          value: 'podcast',
          text: 'Podcasts'
        }
      ]
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    lastLocalMediaSyncResults() {
      return this.$store.state.lastLocalMediaSyncResults
    },
    numLocalMediaSynced() {
      if (!this.lastLocalMediaSyncResults) return 0
      return this.lastLocalMediaSyncResults.numLocalMediaProgressForServer || 0
    },
    syncedAt() {
      if (!this.lastLocalMediaSyncResults) return 0
      return this.lastLocalMediaSyncResults.syncedAt || 0
    },
    syncedServerConfigName() {
      if (!this.lastLocalMediaSyncResults) return ''
      return this.lastLocalMediaSyncResults.serverConfigName
    },
    numLocalProgressUpdates() {
      if (!this.lastLocalMediaSyncResults) return 0
      return this.lastLocalMediaSyncResults.numLocalProgressUpdates || 0
    },
    numServerProgressUpdates() {
      if (!this.lastLocalMediaSyncResults) return 0
      return this.lastLocalMediaSyncResults.numServerProgressUpdates || 0
    }
  },
  methods: {
    async selectFolder() {
      if (!this.newFolderMediaType) {
        return this.$toast.error('Must select a media type')
      }
      var folderObj = await AbsFileSystem.selectFolder({ mediaType: this.newFolderMediaType })
      if (!folderObj) return
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }

      var indexOfExisting = this.localFolders.findIndex((lf) => lf.id == folderObj.id)
      if (indexOfExisting >= 0) {
        this.localFolders.splice(indexOfExisting, 1, folderObj)
      } else {
        this.localFolders.push(folderObj)
      }

      var permissionsGood = await AbsFileSystem.checkFolderPermissions({ folderUrl: folderObj.contentUrl })

      if (!permissionsGood) {
        this.$toast.error('Folder permissions failed')
        return
      } else {
        this.$toast.success('Folder permission success')
      }

      this.$router.push(`/localMedia/folders/${folderObj.id}?scan=1`)
    },
    async init() {
      this.localFolders = (await this.$db.getLocalFolders()) || []
    }
  },
  mounted() {
    this.init()
  }
}
</script>