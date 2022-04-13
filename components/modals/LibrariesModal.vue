<template>
  <modals-modal v-model="show" :width="300" :processing="processing" height="100%">
    <template #outer>
      <div class="absolute top-4 left-4 z-40" style="max-width: 80%">
        <p class="text-white text-2xl truncate">Libraries</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="library in libraries">
            <li :key="library.id" class="text-gray-50 select-none relative py-3 cursor-pointer hover:bg-black-400" :class="currentLibraryId === library.id ? 'bg-bg bg-opacity-80' : ''" role="option" @click="clickedOption(library)">
              <div v-show="currentLibraryId === library.id" class="absolute top-0 left-0 w-0.5 bg-warning h-full" />
              <div class="flex items-center px-3">
                <widgets-library-icon :icon="library.icon" />
                <span class="font-normal block truncate text-lg ml-4">{{ library.name }}</span>
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
  data() {
    return {
      processing: false
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.libraries.showModal
      },
      set(val) {
        this.$store.commit('libraries/setShowModal', val)
      }
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    libraries() {
      return this.$store.state.libraries.libraries
    }
  },
  methods: {
    async clickedOption(lib) {
      this.show = false
      await this.$store.dispatch('libraries/fetch', lib.id)
      this.$eventBus.$emit('library-changed', lib.id)
      this.$localStore.setLastLibraryId(lib.id)
    }
  },
  mounted() {}
}
</script>
