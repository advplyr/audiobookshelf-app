export const state = () => ({
  downloads: [],
  showModal: false
})

export const getters = {
  getDownload: (state) => id => {
    return state.downloads.find(d => d.id === id)
  },
  getDownloadIfReady: (state) => id => {
    var download = state.downloads.find(d => d.id === id)
    return !!download && !download.isDownloading && !download.isPreparing ? download : null
  },
  getAudiobooks: (state) => {
    return state.downloads.map(dl => dl.audiobook)
  }
}

export const actions = {
  async loadFromStorage({ commit }) {
    var downloads = await this.$sqlStore.getAllDownloads()

    downloads.forEach(ab => {
      if (ab.isDownloading || ab.isPreparing) {
        ab.isIncomplete = true
      }
      ab.isDownloading = false
      ab.isPreparing = false
      commit('setDownload', ab)
    })
  }
}

export const mutations = {
  setShowModal(state, val) {
    state.showModal = val
  },
  setDownload(state, download) {
    var index = state.downloads.findIndex(d => d.id === download.id)
    if (index >= 0) {
      state.downloads.splice(index, 1, download)
    } else {
      state.downloads.push(download)
    }
  },
  addUpdateDownload(state, download) {
    var index = state.downloads.findIndex(d => d.id === download.id)
    if (index >= 0) {
      state.downloads.splice(index, 1, download)
    } else {
      state.downloads.push(download)
    }
    this.$sqlStore.setDownload(download)
  },
  removeDownload(state, download) {
    state.downloads = state.downloads.filter(d => d.id !== download.id)
    this.$sqlStore.removeDownload(download.id)
  }
}