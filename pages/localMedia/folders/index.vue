<template>
  <div class="w-full h-full py-6">
    <h1 class="text-2xl px-4 mb-2">Local Folders</h1>

    <div v-if="!isIos" class="w-full max-w-full px-2 py-2">
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
      <div class="flex border-t border-primary my-4">
        <div class="flex-grow pr-1">
          <ui-dropdown v-model="newFolderMediaType" placeholder="Select media type" :items="mediaTypeItems" />
        </div>
        <ui-btn small class="w-28" color="success" @click="selectFolder">New Folder</ui-btn>
      </div>
    </div>
  </div>
</template>

<script>
import StorageManager from '@/plugins/storage-manager'

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
    isSocketConnected() {
      return this.$store.state.socketConnected
    }
  },
  methods: {
    async selectFolder() {
      if (!this.newFolderMediaType) {
        return this.$toast.error('Must select a media type')
      }
      var folderObj = await StorageManager.selectFolder({ mediaType: this.newFolderMediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }

      var indexOfExisting = this.localFolders.findIndex((lf) => lf.id == folderObj.id)
      if (indexOfExisting >= 0) {
        this.localFolders.splice(indexOfExisting, 1, folderObj)
      } else {
        this.localFolders.push(folderObj)
      }

      var permissionsGood = await StorageManager.checkFolderPermissions({ folderUrl: folderObj.contentUrl })

      if (!permissionsGood) {
        this.$toast.error('Folder permissions failed')
        return
      } else {
        this.$toast.success('Folder permission success')
      }

      this.$router.push(`/localMedia/folders/${folderObj.id}?scan=1`)
    },
    async init() {
      this.localFolders = (await this.$db.loadFolders()) || []
    }
  },
  mounted() {
    this.init()
  }
}
</script>