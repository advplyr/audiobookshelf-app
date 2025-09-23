<template>
  <div class="w-full h-full py-6" :style="contentPaddingStyle">
    <div class="flex items-center mb-2">
      <h1 class="text-base font-semibold px-2">
        {{ $strings.HeaderLocalFolders }}
      </h1>
      <button type="button" class="w-8 h-8 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center hover:bg-secondary-container-hover active:scale-95" @click.stop="showLocalFolderMoreInfo">
        <span class="material-symbols text-lg text-on-surface">info</span>
      </button>
    </div>

    <div v-if="!isIos" class="w-full max-w-full px-2 py-2">
      <template v-for="folder in localFolders">
        <nuxt-link :to="`/localMedia/folders/${folder.id}`" :key="folder.id" class="flex items-center px-4 py-4 bg-surface-container rounded-2xl border border-outline-variant mb-2 hover:bg-surface-container-hover active:scale-95 transition-all">
          <span class="material-symbols fill text-xl text-yellow-400">folder</span>
          <p class="ml-2 text-on-surface">{{ folder.name }}</p>
          <div class="flex-grow" />
          <p class="text-sm italic text-on-surface-variant px-3 capitalize">{{ folder.mediaType }}s</p>
          <span class="material-symbols text-xl text-on-surface-variant">arrow_right</span>
        </nuxt-link>
      </template>
      <div v-if="!localFolders.length" class="flex justify-center">
        <p class="text-center">{{ $strings.MessageNoMediaFolders }}</p>
      </div>
      <div v-if="!isAndroid10OrBelow || overrideFolderRestriction" class="flex border-t border-outline-variant my-4 py-4">
        <div class="flex-grow pr-1">
          <ui-dropdown v-model="newFolderMediaType" placeholder="Select media type" :items="mediaTypeItems" />
        </div>
        <div class="flex space-x-2">
          <ui-btn small class="bg-secondary-container text-on-secondary-container hover:bg-secondary-container-hover active:scale-95" @click="resetToDefault">{{ $strings.ButtonReset || 'Reset' }}</ui-btn>
          <ui-btn small class="w-28 bg-secondary-container text-on-secondary-container hover:bg-secondary-container-hover active:scale-95" @click="selectFolder">{{ $strings.ButtonNewFolder }}</ui-btn>
        </div>
      </div>
      <div v-else class="flex border-t border-outline-variant my-4 py-4">
        <div class="flex-grow pr-1">
          <p class="text-sm">{{ $strings.MessageAndroid10Downloads }}</p>
        </div>
        <ui-btn small class="w-28 bg-secondary-container text-on-secondary-container hover:bg-secondary-container-hover active:scale-95" @click="overrideFolderRestriction = true">{{ $strings.ButtonOverride }}</ui-btn>
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
    },
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
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

      // Save to database
      await this.$db.saveLocalFolder(folderObj)

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
    },
    async resetToDefault() {
      if (!this.newFolderMediaType) {
        return this.$toast.error('Must select a media type')
      }
      
      try {
        // Use internal app storage as the default
        const appFolder = {
          id: `internal-${this.newFolderMediaType}`,
          name: this.$strings.LabelInternalAppStorage,
          mediaType: this.newFolderMediaType
        }

        // Remove all existing folders for this media type from database
        const foldersToRemove = this.localFolders.filter(folder => folder.mediaType === this.newFolderMediaType && folder.id !== appFolder.id)
        for (const folder of foldersToRemove) {
          try {
            await this.$db.removeLocalFolder(folder.id)
          } catch (error) {
            console.warn('Failed to remove folder', folder.id, error)
            // Continue with other folders - don't fail the entire operation
          }
        }

        // Update in-memory array
        this.localFolders = this.localFolders.filter(folder => folder.mediaType !== this.newFolderMediaType || folder.id === appFolder.id)
        
        // Ensure the default folder is in the array
        if (!this.localFolders.find(f => f.id === appFolder.id)) {
          this.localFolders.push(appFolder)
        }

        // Save the default folder to database (in case it wasn't already there)
        try {
          await this.$db.saveLocalFolder(appFolder)
        } catch (error) {
          console.warn('Failed to save default folder', error)
          // This is not critical - the folder might already exist
        }
        
        this.$toast.success('Reset to default download location')
        
        // Clear the dropdown selection
        this.newFolderMediaType = null
      } catch (error) {
        console.error('Failed to reset to default', error)
        this.$toast.error('Failed to reset to default download location')
      }
    }
  },
  mounted() {
    this.init()
  }
}
</script>
