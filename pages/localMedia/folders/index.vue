<template>
  <div class="w-full h-full py-6">
    <div class="flex items-center mb-2">
      <h1 class="text-base font-semibold px-2">
        {{ $strings.HeaderLocalFolders }}
      </h1>
      <button type="button" class="material-symbols" @click.stop="showLocalFolderMoreInfo">info</button>
    </div>

    <div v-if="!isIos" class="w-full max-w-full px-2 py-2">
      <template v-for="folder in localFolders">
        <nuxt-link :to="`/localMedia/folders/${folder.id}`" :key="folder.id" class="flex items-center px-2 py-4 bg-primary rounded-md border-bg mb-1">
          <span class="material-symbols fill text-xl text-yellow-400">folder</span>
          <p class="ml-2">{{ folder.name }}</p>
          <div class="flex-grow" />
          <p class="text-sm italic text-fg-muted px-3 capitalize">{{ folder.mediaType }}s</p>
          <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
        </nuxt-link>
      </template>
      <div v-if="!localFolders.length" class="flex justify-center">
        <p class="text-center">{{ $strings.MessageNoMediaFolders }}</p>
      </div>
      <div v-if="!isAndroid10OrBelow || overrideFolderRestriction" class="flex border-t border-fg/10 my-4 py-4">
        <div class="flex-grow pr-1">
          <ui-dropdown v-model="newFolderMediaType" placeholder="Select media type" :items="mediaTypeItems" />
        </div>
        <ui-btn small class="w-28" color="success" @click="selectFolder">{{ $strings.ButtonNewFolder }}</ui-btn>
      </div>
      <div v-else class="flex border-t border-fg/10 my-4 py-4">
        <div class="flex-grow pr-1">
          <p class="text-sm">{{ $strings.MessageAndroid10Downloads }}</p>
        </div>
        <ui-btn small class="w-28" color="primary" @click="overrideFolderRestriction = true">{{ $strings.ButtonOverride }}</ui-btn>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsFileSystem } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      localFolders: [],
      localLibraryItems: [],
      newFolderMediaType: null,
      mediaTypeItems: [
        {
          value: 'book',
          text: this.$strings.LabelBooks
        },
        {
          value: 'podcast',
          text: this.$strings.LabelPodcasts
        }
      ],
      syncing: false,
      isAndroid10OrBelow: false,
      overrideFolderRestriction: false
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
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

      const indexOfExisting = this.localFolders.findIndex((lf) => lf.id == folderObj.id)
      if (indexOfExisting >= 0) {
        this.localFolders.splice(indexOfExisting, 1, folderObj)
      } else {
        this.localFolders.push(folderObj)
      }

      const permissionsGood = await AbsFileSystem.checkFolderPermissions({ folderUrl: folderObj.contentUrl })

      if (!permissionsGood) {
        this.$toast.error('Folder permissions failed')
        return
      } else {
        this.$toast.success('Folder permission success')
      }
    },
    async showLocalFolderMoreInfo() {
      const confirmResult = await Dialog.confirm({
        title: this.$strings.HeaderLocalFolders,
        message: this.$strings.MessageLocalFolderDescription,
        cancelButtonTitle: 'View More'
      })
      if (!confirmResult.value) {
        window.open('https://www.audiobookshelf.org/guides/android_app_shared_storage', '_blank')
      }
    },
    async init() {
      const androidSdkVersion = await this.$getAndroidSDKVersion()
      this.isAndroid10OrBelow = !!androidSdkVersion && androidSdkVersion <= 29
      console.log(`androidSdkVersion=${androidSdkVersion}, isAndroid10OrBelow=${this.isAndroid10OrBelow}`)

      this.localFolders = (await this.$db.getLocalFolders()) || []
      this.localLibraryItems = await this.$db.getLocalLibraryItems()
    }
  },
  mounted() {
    this.init()
  }
}
</script>
