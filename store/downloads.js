import { Capacitor } from '@capacitor/core'

export const state = () => ({
  downloads: [],
  showModal: false,
  mediaScanResults: {},
})

export const getters = {
  getDownload: (state) => id => {
    return state.downloads.find(d => d.id === id)
  },
  getDownloads: state => {
    return state.downloads
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
  async loadFromStorage({ commit, state }) {
    var downloads = await this.$sqlStore.getAllDownloads()
    console.log('Load downloads from storage', downloads.length)
    downloads.forEach(ab => {
      if (ab.isDownloading || ab.isPreparing) {
        ab.isIncomplete = true
      }
      ab.isDownloading = false
      ab.isPreparing = false
      commit('setDownload', ab)
    })
    return state.downloads
  },
  async linkOrphanDownloads({ state, commit, rootState }) {
    if (!state.mediaScanResults || !state.mediaScanResults.folders) {
      return
    }

    for (let i = 0; i < state.mediaScanResults.folders.length; i++) {
      var folder = state.mediaScanResults.folders[i]
      if (!folder.files || !folder.files.length) return

      var download = state.downloads.find(dl => dl.folderName === folder.name)
      if (!download) {
        var results = await this.$axios.$get(`/api/libraries/${rootState.libraries.currentLibraryId}/search?q=${folder.name}`)
        var matchingAb = null
        if (results && results.audiobooks) {
          matchingAb = results.audiobooks.find(ab => {
            return ab.audiobook.book.title === folder.name
          })
        }

        if (matchingAb) {
          matchingAb = matchingAb.audiobook
          // Found matching download for ab
          var audioFile = folder.files.find(f => f.isAudio)
          if (!audioFile) {
            return
          }
          var coverImg = folder.files.find(f => !f.isAudio)
          const downloadObj = {
            id: matchingAb.id,
            audiobook: { ...matchingAb },
            contentUrl: audioFile.uri,
            simplePath: audioFile.simplePath,
            folderUrl: folder.uri,
            folderName: folder.name,
            storageType: '',
            storageId: '',
            basePath: '',
            size: audioFile.size,
            coverUrl: coverImg ? coverImg.uri : null,
            cover: coverImg ? Capacitor.convertFileSrc(coverImg.uri) : null,
            coverSize: coverImg ? coverImg.size : 0,
            coverBasePath: ''
          }
          commit('addUpdateDownload', downloadObj)
        }
      }
    }
  }
}

export const mutations = {
  setShowModal(state, val) {
    state.showModal = val
  },
  setDownload(state, download) {
    if (!download || !download.id) {
      return
    }
    var index = state.downloads.findIndex(d => d.id === download.id)
    if (index >= 0) {
      state.downloads.splice(index, 1, download)
    } else {
      state.downloads.push(download)
    }
  },
  addUpdateDownload(state, download) {
    if (!download || !download.id) {
      console.error('Orphan invalid download ' + download.id)
      return
    }
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
  },
  setMediaScanResults(state, val) {
    state.mediaScanResults = val
  }
}