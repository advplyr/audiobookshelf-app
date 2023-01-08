export const state = () => ({
  isModalOpen: false,
  itemDownloads: [],
  bookshelfListView: false,
  series: null,
  localMediaProgress: [],
  lastSearch: null,
  jumpForwardItems: [
    {
      icon: 'forward_5',
      value: 5
    },
    {
      icon: 'forward_10',
      value: 10
    },
    {
      icon: 'forward_30',
      value: 30
    }
  ],
  jumpBackwardsItems: [
    {
      icon: 'replay_5',
      value: 5
    },
    {
      icon: 'replay_10',
      value: 10
    },
    {
      icon: 'replay_30',
      value: 30
    }
  ],
  libraryIcons: ['database', 'audiobookshelf', 'books-1', 'books-2', 'book-1', 'microphone-1', 'microphone-3', 'radio', 'podcast', 'rss', 'headphones', 'music', 'file-picture', 'rocket', 'power', 'star', 'heart'],
  selectedPlaylistItems: [],
  showPlaylistsAddCreateModal: false,
  hapticFeedback: 'LIGHT'
})

export const getters = {
  getDownloadItem: state => (libraryItemId, episodeId = null) => {
    return state.itemDownloads.find(i => {
      if (episodeId && !i.episodes.some(e => e.id == episodeId)) return false
      return i.libraryItemId == libraryItemId
    })
  },
  getLibraryItemCoverSrc: (state, getters, rootState, rootGetters) => (libraryItem, placeholder, raw = false) => {
    if (!libraryItem) return placeholder
    const media = libraryItem.media
    if (!media || !media.coverPath || media.coverPath === placeholder) return placeholder

    // Absolute URL covers (should no longer be used)
    if (media.coverPath.startsWith('http:') || media.coverPath.startsWith('https:')) return media.coverPath

    const userToken = rootGetters['user/getToken']
    const serverAddress = rootGetters['user/getServerAddress']
    if (!userToken || !serverAddress) return placeholder

    const lastUpdate = libraryItem.updatedAt || Date.now()

    if (process.env.NODE_ENV !== 'production') { // Testing
      // return `http://localhost:3333/api/items/${libraryItem.id}/cover?token=${userToken}&ts=${lastUpdate}`
    }

    const url = new URL(`/api/items/${libraryItem.id}/cover`, serverAddress)
    return `${url}?token=${userToken}&ts=${lastUpdate}${raw ? '&raw=1' : ''}`
  },
  getLocalMediaProgressById: (state) => (localLibraryItemId, episodeId = null) => {
    return state.localMediaProgress.find(lmp => {
      if (episodeId != null && lmp.localEpisodeId != episodeId) return false
      return lmp.localLibraryItemId == localLibraryItemId
    })
  },
  getLocalMediaProgressByServerItemId: (state) => (libraryItemId, episodeId = null) => {
    return state.localMediaProgress.find(lmp => {
      if (episodeId != null && lmp.episodeId != episodeId) return false
      return lmp.libraryItemId == libraryItemId
    })
  },
  getJumpForwardIcon: state => (jumpForwardTime) => {
    const item = state.jumpForwardItems.find(i => i.value == jumpForwardTime)
    return item ? item.icon : 'forward_10'
  },
  getJumpBackwardsIcon: state => (jumpBackwardsTime) => {
    const item = state.jumpBackwardsItems.find(i => i.value == jumpBackwardsTime)
    return item ? item.icon : 'replay_10'
  }
}

export const actions = {
  async loadLocalMediaProgress({ state, commit }) {
    var mediaProgress = await this.$db.getAllLocalMediaProgress()
    commit('setLocalMediaProgress', mediaProgress)
  }
}

export const mutations = {
  setIsModalOpen(state, val) {
    state.isModalOpen = val
  },
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
  },
  removeLocalMediaProgressForItem(state, llid) {
    state.localMediaProgress = state.localMediaProgress.filter(lmp => lmp.localLibraryItemId !== llid)
  },
  setLastSearch(state, val) {
    state.lastSearch = val
  },
  setSelectedPlaylistItems(state, items) {
    state.selectedPlaylistItems = items
  },
  setShowPlaylistsAddCreateModal(state, val) {
    state.showPlaylistsAddCreateModal = val
  },
  setHapticFeedback(state, val) {
    state.hapticFeedback = val || 'LIGHT'
  }
}