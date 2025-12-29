export const state = () => ({
  isModalOpen: false,
  itemDownloads: [],
  bookshelfListView: false,
  series: null,
  localMediaProgress: [],
  lastSearch: null,
  jumpForwardItems: [
    {
      icon: 'forward_media',
      label: '5s',
      value: 5
    },
    {
      icon: 'forward_media',
      label: '10s',
      value: 10
    },
    {
      icon: 'forward_media',
      label: '15s',
      value: 15
    },
    {
      icon: 'forward_media',
      label: '30s',
      value: 30
    },
    {
      icon: 'forward_media',
      label: '60s',
      value: 60
    },
    {
      icon: 'forward_media',
      label: '2m',
      value: 120
    },
    {
      icon: 'forward_media',
      label: '5m',
      value: 300
    }
  ],
  jumpBackwardsItems: [
    {
      icon: 'replay',
      label: '5s',
      value: 5
    },
    {
      icon: 'replay',
      label: '10s',
      value: 10
    },
    {
      icon: 'replay',
      label: '15s',
      value: 15
    },
    {
      icon: 'replay',
      label: '30s',
      value: 30
    },
    {
      icon: 'replay',
      label: '60s',
      value: 60
    },
    {
      icon: 'replay',
      label: '2m',
      value: 120
    },
    {
      icon: 'replay',
      label: '5m',
      value: 300
    }
  ],
  libraryIcons: ['database', 'audiobookshelf', 'books-1', 'books-2', 'book-1', 'microphone-1', 'microphone-3', 'radio', 'podcast', 'rss', 'headphones', 'music', 'file-picture', 'rocket', 'power', 'star', 'heart'],
  selectedPlaylistItems: [],
  showPlaylistsAddCreateModal: false,
  showSelectLocalFolderModal: false,
  localFolderSelectData: null,
  hapticFeedback: 'LIGHT',
  showRSSFeedOpenCloseModal: false,
  rssFeedEntity: null
})

export const getters = {
  getDownloadItem:
    (state) =>
    (libraryItemId, episodeId = null) => {
      return state.itemDownloads.find((i) => {
        // if (episodeId && !i.episodes.some(e => e.id == episodeId)) return false
        if (episodeId && i.episodeId !== episodeId) return false
        return i.libraryItemId == libraryItemId
      })
    },
  getLibraryItemCoverSrc:
    (state, getters, rootState, rootGetters) =>
    (libraryItem, placeholder, raw = false) => {
      if (!libraryItem) return placeholder
      const media = libraryItem.media
      if (!media || !media.coverPath || media.coverPath === placeholder) return placeholder

      // Absolute URL covers (should no longer be used)
      if (media.coverPath.startsWith('http:') || media.coverPath.startsWith('https:')) return media.coverPath

      const serverAddress = rootGetters['user/getServerAddress']
      if (!serverAddress) return placeholder

      const lastUpdate = libraryItem.updatedAt || Date.now()

      if (process.env.NODE_ENV !== 'production') {
        // Testing
        // return `http://localhost:3333/api/items/${libraryItem.id}/cover?ts=${lastUpdate}`
      }

      const url = new URL(`${serverAddress}/api/items/${libraryItem.id}/cover`)
      const urlQuery = new URLSearchParams()
      urlQuery.append('ts', lastUpdate)
      if (raw) urlQuery.append('raw', '1')
      if (rootGetters.getDoesServerImagesRequireToken) {
        urlQuery.append('token', rootGetters['user/getToken'])
      }
      return `${url}?${urlQuery}`
    },
  getLibraryItemCoverSrcById:
    (state, getters, rootState, rootGetters) =>
    (libraryItemId, placeholder = null) => {
      if (!placeholder) placeholder = `${rootState.routerBasePath}/book_placeholder.jpg`
      if (!libraryItemId) return placeholder
      const serverAddress = rootGetters['user/getServerAddress']
      if (!serverAddress) return placeholder

      const url = new URL(`${serverAddress}/api/items/${libraryItemId}/cover`)
      if (rootGetters.getDoesServerImagesRequireToken) {
        return `${url}?token=${rootGetters['user/getToken']}`
      }
      return url.toString()
    },
  getLocalMediaProgressById:
    (state) =>
    (localLibraryItemId, episodeId = null) => {
      return state.localMediaProgress.find((lmp) => {
        if (episodeId != null && lmp.localEpisodeId != episodeId) return false
        return lmp.localLibraryItemId == localLibraryItemId
      })
    },
  getLocalMediaProgressByServerItemId:
    (state) =>
    (libraryItemId, episodeId = null) => {
      return state.localMediaProgress.find((lmp) => {
        if (episodeId != null && lmp.episodeId != episodeId) return false
        return lmp.libraryItemId == libraryItemId
      })
    },
  getJumpForwardIcon: (state) => (jumpForwardTime) => {
    const item = state.jumpForwardItems.find((i) => i.value == jumpForwardTime)
    return item ? item.icon : 'forward_10'
  },
  getJumpBackwardsIcon: (state) => (jumpBackwardsTime) => {
    const item = state.jumpBackwardsItems.find((i) => i.value == jumpBackwardsTime)
    return item ? item.icon : 'replay_10'
  },
  getJumpForwardItem: (state) => (jumpForwardTime) => {
    return state.jumpForwardItems.find((i) => i.value == jumpForwardTime) || state.jumpForwardItems[1]
  },
  getJumpBackwardsItem: (state) => (jumpBackwardsTime) => {
    return state.jumpBackwardsItems.find((i) => i.value == jumpBackwardsTime) || state.jumpBackwardsItems[1]
  }
}

export const actions = {
  async loadLocalMediaProgress({ state, commit }) {
    const mediaProgress = await this.$db.getAllLocalMediaProgress()
    commit('setLocalMediaProgress', mediaProgress)
  }
}

export const mutations = {
  setIsModalOpen(state, val) {
    state.isModalOpen = val
  },
  addUpdateItemDownload(state, downloadItem) {
    var index = state.itemDownloads.findIndex((i) => i.id == downloadItem.id)
    if (index >= 0) {
      state.itemDownloads.splice(index, 1, downloadItem)
    } else {
      state.itemDownloads.push(downloadItem)
    }
  },
  updateDownloadItemPart(state, downloadItemPart) {
    const downloadItem = state.itemDownloads.find((i) => i.id == downloadItemPart.downloadItemId)
    if (!downloadItem) {
      console.error('updateDownloadItemPart: Download item not found for itemPart', JSON.stringify(downloadItemPart))
      return
    }

    let totalBytes = 0
    let totalBytesDownloaded = 0
    downloadItem.downloadItemParts = downloadItem.downloadItemParts.map((dip) => {
      let newDip = dip.id == downloadItemPart.id ? downloadItemPart : dip

      totalBytes += newDip.completed ? Number(newDip.bytesDownloaded) : Number(newDip.fileSize)
      totalBytesDownloaded += Number(newDip.bytesDownloaded)

      return newDip
    })

    if (totalBytes > 0) {
      downloadItem.itemProgress = Math.min(1, totalBytesDownloaded / totalBytes)
      console.log(`updateDownloadItemPart: filename=${downloadItemPart.filename}, totalBytes=${totalBytes}, downloaded=${totalBytesDownloaded}, itemProgress=${downloadItem.itemProgress}`)
    } else {
      downloadItem.itemProgress = 0
    }
  },
  removeItemDownload(state, id) {
    state.itemDownloads = state.itemDownloads.filter((i) => i.id != id)
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
    var index = state.localMediaProgress.findIndex((lmp) => lmp.id == prog.id)
    if (index >= 0) {
      state.localMediaProgress.splice(index, 1, prog)
    } else {
      state.localMediaProgress.push(prog)
    }
  },
  removeLocalMediaProgress(state, id) {
    state.localMediaProgress = state.localMediaProgress.filter((lmp) => lmp.id != id)
  },
  removeLocalMediaProgressForItem(state, llid) {
    state.localMediaProgress = state.localMediaProgress.filter((lmp) => lmp.localLibraryItemId !== llid)
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
  showSelectLocalFolderModal(state, data) {
    state.localFolderSelectData = data
    state.showSelectLocalFolderModal = true
  },
  setShowSelectLocalFolderModal(state, val) {
    state.showSelectLocalFolderModal = val
  },
  setHapticFeedback(state, val) {
    state.hapticFeedback = val || 'LIGHT'
  },
  setShowRSSFeedOpenCloseModal(state, val) {
    state.showRSSFeedOpenCloseModal = val
  },
  setRSSFeedOpenCloseModal(state, entity) {
    state.rssFeedEntity = entity
    state.showRSSFeedOpenCloseModal = true
  }
}
