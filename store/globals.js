export const state = () => ({
  itemDownloads: [],
  bookshelfListView: false,
  series: null,
  localMediaProgress: []
})

export const getters = {
  getDownloadItem: state => libraryItemId => {
    return state.itemDownloads.find(i => i.id == libraryItemId)
  },
  getLibraryItemCoverSrc: (state, getters, rootState, rootGetters) => (libraryItem, placeholder = '/book_placeholder.jpg') => {
    if (!libraryItem) return placeholder
    var media = libraryItem.media
    if (!media || !media.coverPath || media.coverPath === placeholder) return placeholder

    // Absolute URL covers (should no longer be used)
    if (media.coverPath.startsWith('http:') || media.coverPath.startsWith('https:')) return media.coverPath

    var userToken = rootGetters['user/getToken']
    var lastUpdate = libraryItem.updatedAt || Date.now()

    if (process.env.NODE_ENV !== 'production') { // Testing
      // return `http://localhost:3333/api/items/${libraryItem.id}/cover?token=${userToken}&ts=${lastUpdate}`
    }

    var url = new URL(`/api/items/${libraryItem.id}/cover`, rootGetters['user/getServerAddress'])
    return `${url}?token=${userToken}&ts=${lastUpdate}`
  },
  getLocalMediaProgressById: (state) => (localLibraryItemId, episodeId = null) => {
    return state.localMediaProgress.find(lmp => {
      if (episodeId != null && lmp.episodeId != episodeId) return false
      return lmp.localLibraryItemId == localLibraryItemId
    })
  }
}

export const actions = {
  async loadLocalMediaProgress({ state, commit }) {
    var mediaProgress = await this.$db.getAllLocalMediaProgress()
    console.log('Got all local media progress', JSON.stringify(mediaProgress))
    commit('setLocalMediaProgress', mediaProgress)
  }
}

export const mutations = {
  addUpdateItemDownload(state, downloadItem) {
    var index = state.itemDownloads.findIndex(i => i.id == downloadItem.id)
    if (index >= 0) {
      state.itemDownloads.splice(index, 1, downloadItem)
    } else {
      state.itemDownloads.push(downloadItem)
    }
  },
  removeItemDownload(state, id) {
    state.itemDownloads = state.itemDownloads.filter(i => i.id != id)
  },
  setBookshelfListView(state, val) {
    state.bookshelfListView = val
  },
  setSeries(state, val) {
    state.series = val
  },
  setLocalMediaProgress(state, val) {
    state.localMediaProgress = val
  },
  updateLocalMediaProgress(state, prog) {
    if (!prog || !prog.id) {
      return
    }
    var index = state.localMediaProgress.findIndex(lmp => lmp.id == prog.id)
    if (index >= 0) {
      state.localMediaProgress.splice(index, 1, prog)
    } else {
      state.localMediaProgress.push(prog)
    }
  },
  removeLocalMediaProgress(state, id) {
    state.localMediaProgress = state.localMediaProgress.filter(lmp => lmp.id != id)
  }
}