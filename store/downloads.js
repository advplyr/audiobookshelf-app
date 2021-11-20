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
  },
  linkOrphanDownloads({ state, commit }, audiobooks) {
    if (!state.mediaScanResults || !state.mediaScanResults.folders) {
      return
    }
    console.log('Link orphan downloads', JSON.stringify(state.mediaScanResults.folders))
    state.mediaScanResults.folders.forEach((folder) => {
      if (!folder.files || !folder.files.length) return

      console.log('Link orphan downloads check folder', folder.name)

      var download = state.downloads.find(dl => dl.folderName === folder.name)
      if (!download) {
        var matchingAb = audiobooks.find(ab => ab.book.title === folder.name)
        if (matchingAb) {
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
          console.log('Linking orphan download: ' + JSON.stringify(downloadObj))
          commit('addUpdateDownload', downloadObj)
        }
      }
    })
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