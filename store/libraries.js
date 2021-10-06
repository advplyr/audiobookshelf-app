export const state = () => ({
  libraries: [],
  lastLoad: 0,
  listeners: [],
  currentLibraryId: 'main',
  showModal: false,
  folders: [],
  folderLastUpdate: 0
})

export const getters = {
  getCurrentLibrary: state => {
    return state.libraries.find(lib => lib.id === state.currentLibraryId)
  }
}

export const actions = {
  fetch({ state, commit, rootState }, libraryId) {
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/fetch - User not set')
      return false
    }

    var library = state.libraries.find(lib => lib.id === libraryId)
    if (library) {
      commit('setCurrentLibrary', libraryId)
      return library
    }

    return this.$axios
      .$get(`/api/library/${libraryId}`)
      .then((data) => {
        commit('addUpdate', data)
        commit('setCurrentLibrary', libraryId)
        return data
      })
      .catch((error) => {
        console.error('Failed', error)
        return false
      })
  },
  // Return true if calling load
  load({ state, commit, rootState }) {
    if (!rootState.user || !rootState.user.user) {
      console.error('libraries/load - User not set')
      return false
    }

    // Don't load again if already loaded in the last 5 minutes
    var lastLoadDiff = Date.now() - state.lastLoad
    if (lastLoadDiff < 5 * 60 * 1000) {
      // Already up to date
      return false
    }

    this.$axios
      .$get(`/api/libraries`)
      .then((data) => {
        commit('set', data)
        commit('setLastLoad')
      })
      .catch((error) => {
        console.error('Failed', error)
        commit('set', [])
      })
    return true
  },

}

export const mutations = {
  setFolders(state, folders) {
    state.folders = folders
  },
  setFoldersLastUpdate(state) {
    state.folderLastUpdate = Date.now()
  },
  setShowModal(state, val) {
    state.showModal = val
  },
  setLastLoad(state) {
    state.lastLoad = Date.now()
  },
  setCurrentLibrary(state, val) {
    state.currentLibraryId = val
  },
  set(state, libraries) {
    console.log('set libraries', libraries)
    state.libraries = libraries
    state.listeners.forEach((listener) => {
      listener.meth()
    })
  },
  addUpdate(state, library) {
    var index = state.libraries.findIndex(a => a.id === library.id)
    if (index >= 0) {
      state.libraries.splice(index, 1, library)
    } else {
      state.libraries.push(library)
    }

    state.listeners.forEach((listener) => {
      listener.meth()
    })
  },
  remove(state, library) {
    state.libraries = state.libraries.filter(a => a.id !== library.id)

    state.listeners.forEach((listener) => {
      listener.meth()
    })
  },
  addListener(state, listener) {
    var index = state.listeners.findIndex(l => l.id === listener.id)
    if (index >= 0) state.listeners.splice(index, 1, listener)
    else state.listeners.push(listener)
  },
  removeListener(state, listenerId) {
    state.listeners = state.listeners.filter(l => l.id !== listenerId)
  }
}