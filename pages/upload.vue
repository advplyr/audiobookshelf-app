<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <div class="max-w-xl mx-auto pb-12">
      <h1 class="text-xl font-semibold mb-1">{{ $strings.HeaderUploadToServer }}</h1>
      <p class="text-sm text-fg-muted mb-6">{{ $strings.MessageResumableUploadHelp }}</p>

      <div class="space-y-4">
        <ui-dropdown v-model="libraryId" :items="libraryOptions" :label="$strings.LabelLibrary" :disabled="uploading" />
        <ui-dropdown v-model="folderId" :items="folderOptions" :label="$strings.LabelFolder" :disabled="uploading || !libraryId" />
        <ui-text-input-with-label v-model.trim="title" :label="$strings.LabelTitle" :disabled="uploading" />
        <ui-text-input-with-label v-if="!isPodcast" v-model.trim="author" :label="$strings.LabelAuthor" :disabled="uploading" />
        <ui-text-input-with-label v-if="!isPodcast" v-model.trim="series" :label="$strings.LabelSeries" :disabled="uploading" />
      </div>

      <div class="mt-6 border border-border rounded-md p-4 bg-primary">
        <input ref="fileInput" class="hidden" type="file" multiple :accept="acceptedFiles" @change="filesSelected" />
        <ui-btn class="w-full" :disabled="uploading" @click="chooseFiles">
          <span class="material-symbols align-middle mr-2">audio_file</span>{{ $strings.ButtonChooseUploadFiles }}
        </ui-btn>

        <div v-if="files.length" class="mt-4">
          <p class="text-sm font-semibold mb-2">{{ $getString('LabelSelectedFilesCount', [files.length]) }}</p>
          <div class="max-h-48 overflow-y-auto divide-y divide-border">
            <div v-for="file in files" :key="`${file.name}-${file.size}-${file.lastModified}`" class="py-2 flex gap-3 text-sm">
              <span class="truncate flex-grow">{{ file.name }}</span>
              <span class="text-fg-muted whitespace-nowrap">{{ bytesPretty(file.size) }}</span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="uploading || uploadedBytes" class="mt-6">
        <div class="flex justify-between text-sm mb-2">
          <span>{{ statusText }}</span>
          <span>{{ progressPercent }}%</span>
        </div>
        <div class="w-full h-3 rounded-full bg-primary overflow-hidden">
          <div class="h-full bg-success transition-all duration-200" :style="{ width: `${progressPercent}%` }" />
        </div>
        <p class="text-xs text-fg-muted mt-2 text-right">{{ bytesPretty(uploadedBytes) }} / {{ bytesPretty(totalBytes) }}</p>
      </div>

      <p v-if="error" class="mt-5 text-sm text-error break-words">{{ error }}</p>
      <p v-if="success" class="mt-5 text-sm text-success">{{ $strings.MessageResumableUploadComplete }}</p>

      <ui-btn color="success" class="w-full mt-6" :loading="uploading" :disabled="!canSubmit" @click="upload">
        {{ uploadedBytes ? $strings.ButtonResumeUpload : $strings.ButtonUploadToServer }}
      </ui-btn>
    </div>
  </div>
</template>

<script>
const CHUNK_SIZE = 8 * 1024 * 1024

export default {
  asyncData({ redirect, store }) {
    if (!store.state.user.user) return redirect('/connect')
    if (!store.getters['user/getUserCanUpload']) return redirect('/bookshelf')
    return {}
  },
  data() {
    return {
      libraryId: '',
      folderId: '',
      title: '',
      author: '',
      series: '',
      files: [],
      uploading: false,
      uploadedBytes: 0,
      totalBytes: 0,
      statusText: '',
      error: '',
      success: false
    }
  },
  computed: {
    libraries() {
      return this.$store.state.libraries.libraries || []
    },
    libraryOptions() {
      return this.libraries.map((library) => ({ value: library.id, text: library.name }))
    },
    selectedLibrary() {
      return this.libraries.find((library) => library.id === this.libraryId)
    },
    folderOptions() {
      return (this.selectedLibrary?.folders || []).map((folder) => ({ value: folder.id, text: folder.fullPath || folder.path }))
    },
    isPodcast() {
      return this.selectedLibrary?.mediaType === 'podcast'
    },
    acceptedFiles() {
      return 'audio/*,.m4b,.m4a,.mp3,.ogg,.opus,.flac,.wav,.aac,.webm,.jpg,.jpeg,.png,.webp,.epub,.pdf,.cbz,.cbr,.txt,.cue,.nfo'
    },
    canSubmit() {
      return !this.uploading && !!this.libraryId && !!this.folderId && !!this.title && this.files.length > 0
    },
    progressPercent() {
      if (!this.totalBytes) return 0
      return Math.min(100, Math.round((this.uploadedBytes / this.totalBytes) * 100))
    },
    draftKey() {
      const serverId = this.$store.getters['user/getServerConnectionConfigId'] || 'server'
      return `resumable-upload-draft-${serverId}`
    }
  },
  watch: {
    libraryId() {
      if (!this.folderOptions.some((folder) => folder.value === this.folderId)) {
        this.folderId = this.folderOptions[0]?.value || ''
      }
      this.saveDraft()
    },
    folderId() {
      this.saveDraft()
    },
    title() {
      this.saveDraft()
    },
    author() {
      this.saveDraft()
    },
    series() {
      this.saveDraft()
    }
  },
  methods: {
    chooseFiles() {
      this.$refs.fileInput?.click()
    },
    filesSelected(event) {
      this.files = Array.from(event.target.files || [])
      this.totalBytes = this.files.reduce((sum, file) => sum + file.size, 0)
      this.uploadedBytes = 0
      this.success = false
      this.error = ''
      if (!this.title && this.files.length === 1) this.title = this.files[0].name.replace(/\.[^.]+$/, '')
    },
    bytesPretty(bytes) {
      if (!bytes) return '0 B'
      const units = ['B', 'KB', 'MB', 'GB', 'TB']
      const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
      return `${(bytes / Math.pow(1024, unit)).toFixed(unit ? 1 : 0)} ${units[unit]}`
    },
    hashFingerprint(value, seed) {
      let hash = seed
      for (let index = 0; index < value.length; index++) {
        hash ^= value.charCodeAt(index)
        hash = Math.imul(hash, 16777619)
      }
      return (hash >>> 0).toString(16).padStart(8, '0')
    },
    getUploadId() {
      const fingerprint = [this.libraryId, this.folderId, this.title, this.author, this.series]
        .concat(this.files.map((file) => `${file.name}:${file.size}:${file.lastModified}`))
        .join('|')
      return [2166136261, 2166136261 ^ 0x9e3779b9, 2166136261 ^ 0x85ebca6b, 2166136261 ^ 0xc2b2ae35]
        .map((seed) => this.hashFingerprint(fingerprint, seed))
        .join('')
    },
    saveDraft() {
      if (!process.client || !this.draftKey) return
      localStorage.setItem(this.draftKey, JSON.stringify({
        libraryId: this.libraryId,
        folderId: this.folderId,
        title: this.title,
        author: this.author,
        series: this.series
      }))
    },
    loadDraft() {
      try {
        const draft = JSON.parse(localStorage.getItem(this.draftKey))
        if (!draft) return
        this.libraryId = draft.libraryId || this.libraryId
        this.folderId = draft.folderId || this.folderId
        this.title = draft.title || ''
        this.author = draft.author || ''
        this.series = draft.series || ''
      } catch (error) {
        console.warn('Failed to load upload draft', error)
      }
    },
    async upload() {
      if (!this.canSubmit) return
      this.uploading = true
      this.error = ''
      this.success = false
      const uploadId = this.getUploadId()

      try {
        const upload = await this.$axios.$post('/api/upload/resumable', {
          uploadId,
          title: this.title,
          author: this.isPodcast ? null : this.author,
          series: this.isPodcast ? null : this.series,
          library: this.libraryId,
          folder: this.folderId,
          files: this.files.map((file) => ({ name: file.name, size: file.size }))
        })
        const offsets = upload.offsets
        this.uploadedBytes = offsets.reduce((sum, offset) => sum + offset, 0)

        for (let fileIndex = 0; fileIndex < this.files.length; fileIndex++) {
          const file = this.files[fileIndex]
          let offset = offsets[fileIndex]
          while (offset < file.size) {
            const end = Math.min(offset + CHUNK_SIZE, file.size)
            const chunk = file.slice(offset, end, 'application/octet-stream')
            const baseProgress = this.uploadedBytes
            this.statusText = `${fileIndex + 1}/${this.files.length}: ${file.name}`
            const response = await this.$axios.patch(`/api/upload/resumable/${uploadId}/${fileIndex}`, chunk, {
              headers: { 'Content-Type': 'application/octet-stream', 'Upload-Offset': String(offset) },
              onUploadProgress: (event) => {
                this.uploadedBytes = Math.min(this.totalBytes, baseProgress + event.loaded)
              }
            })
            const reportedOffset = Number(response.headers?.['upload-offset'])
            const newOffset = Number.isSafeInteger(reportedOffset) ? reportedOffset : end
            if (newOffset <= offset || newOffset > end) throw new Error('Server returned an invalid upload offset')
            this.uploadedBytes = Math.min(this.totalBytes, baseProgress + newOffset - offset)
            offset = newOffset
            offsets[fileIndex] = offset
          }
        }

        await this.$axios.$post(`/api/upload/resumable/${uploadId}/complete`)
        this.uploadedBytes = this.totalBytes
        this.statusText = this.$strings.MessageResumableUploadComplete
        this.success = true
        localStorage.removeItem(this.draftKey)
      } catch (error) {
        console.error('Resumable upload failed', error)
        this.error = error.response?.data?.message || error.response?.data || error.message || this.$strings.MessageResumableUploadFailed
        this.statusText = this.$strings.MessageResumableUploadPaused
      } finally {
        this.uploading = false
      }
    }
  },
  async mounted() {
    if (!this.libraries.length) await this.$store.dispatch('libraries/load')
    this.libraryId = this.$store.state.libraries.currentLibraryId || this.libraries[0]?.id || ''
    this.folderId = this.folderOptions[0]?.value || ''
    this.loadDraft()
  }
}
</script>
