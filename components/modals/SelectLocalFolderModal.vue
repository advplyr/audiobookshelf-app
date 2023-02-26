<template>
  <modals-modal v-model="show" :width="300" height="100%">
    <template #outer>
      <div class="absolute top-8 left-4 z-40" style="max-width: 80%">
        <p class="text-white text-lg truncate">Select Local Folder</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="folder in localFolders">
            <li :key="folder.id" :id="`folder-${folder.id}`" class="text-gray-50 select-none relative py-4" role="option" @click="clickedOption(folder)">
              <div class="relative flex items-center pl-3" style="padding-right: 4.5rem">
                <p class="font-normal block truncate text-sm text-white text-opacity-80">{{ folder.name }}</p>
              </div>
            </li>
          </template>
        </ul>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    mediaType: String
  },
  data() {
    return {
      localFolders: []
    }
  },
  watch: {
    value(newVal) {
      this.$nextTick(this.init)
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
    }
  },
  methods: {
    clickedOption(folder) {
      this.$emit('select', folder)
    },
    async init() {
      var localFolders = (await this.$db.getLocalFolders()) || []
      this.localFolders = localFolders.filter((lf) => lf.mediaType == this.mediaType)
    }
  },
  mounted() {}
}
</script>
