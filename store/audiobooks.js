const STANDARD_GENRES = ['adventure', 'autobiography', 'biography', 'childrens', 'comedy', 'crime', 'dystopian', 'fantasy', 'fiction', 'health', 'history', 'horror', 'mystery', 'new_adult', 'nonfiction', 'philosophy', 'politics', 'religion', 'romance', 'sci-fi', 'self-help', 'short_story', 'technology', 'thriller', 'true_crime', 'western', 'young_adult']

export const state = () => ({
  audiobooks: [],
  listeners: [],
  loadedLibraryId: 'main',
  lastLoad: 0,
  isLoading: false
})

export const getters = {
  getAudiobook: state => id => {
    return state.audiobooks.find(ab => ab.id === id)
  },
  getBookCoverSrc: (state, getters, rootState, rootGetters) => (bookItem, placeholder = '/book_placeholder.jpg') => {
    var book = bookItem.book
    if (!book || !book.cover || book.cover === placeholder) return placeholder
    var cover = book.cover

    // Absolute URL covers (should no longer be used)
    if (cover.startsWith('http:') || cover.startsWith('https:')) return cover

    // Server hosted covers
    try {
      // Ensure cover is refreshed if cached
      var bookLastUpdate = book.lastUpdate || Date.now()
      var userToken = rootGetters['user/getToken']

      // Map old covers to new format /s/book/{bookid}/*
      if (cover.substr(1).startsWith('local')) {
        cover = cover.replace('local', `s/book/${bookItem.id}`)
        if (cover.includes(bookItem.path)) { // Remove book path
          cover = cover.replace(bookItem.path, '').replace('//', '/').replace('\\\\', '/')
        }
      }

      var url = new URL(cover, rootState.serverUrl)
      return url + `?token=${userToken}&ts=${bookLastUpdate}`
    } catch (err) {
      console.error(err)
      return placeholder
    }
  }
}

export const actions = {
  useDownloaded({ commit, rootGetters }) {
    commit('set', rootGetters['downloads/getAudiobooks'])
  }
}

export const mutations = {
  setLoadedLibrary(state, val) {
    state.loadedLibraryId = val
  },
  setLoading(state, val) {
    state.isLoading = val
  },
  setLastLoad(state, val) {
    state.lastLoad = val
  },
  reset(state) {
    state.audiobooks = []
    state.genres = [...STANDARD_GENRES]
    state.tags = []
    state.series = []
  },
  addUpdate(state, audiobook) {
    var index = state.audiobooks.findIndex(a => a.id === audiobook.id)
    var origAudiobook = null
    if (index >= 0) {
      origAudiobook = { ...state.audiobooks[index] }
      state.audiobooks.splice(index, 1, audiobook)
    } else {
      state.audiobooks.push(audiobook)
    }

    if (audiobook.book) {
      // GENRES
      var newGenres = []
      audiobook.book.genres.forEach((genre) => {
        if (!state.genres.includes(genre)) newGenres.push(genre)
      })
      if (newGenres.length) {
        state.genres = state.genres.concat(newGenres)
        state.genres.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)
      }

      // SERIES
      if (audiobook.book.series && !state.series.includes(audiobook.book.series)) {
        state.series.push(audiobook.book.series)
        state.series.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)
      }
      if (origAudiobook && origAudiobook.book && origAudiobook.book.series) {
        var isInAB = state.audiobooks.find(ab => ab.book && ab.book.series === origAudiobook.book.series)
        if (!isInAB) state.series = state.series.filter(series => series !== origAudiobook.book.series)
      }
    }

    // TAGS
    var newTags = []
    audiobook.tags.forEach((tag) => {
      if (!state.tags.includes(tag)) newTags.push(tag)
    })
    if (newTags.length) {
      state.tags = state.tags.concat(newTags)
      state.tags.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)
    }

    state.listeners.forEach((listener) => {
      if (!listener.audiobookId || listener.audiobookId === audiobook.id) {
        listener.meth()
      }
    })
  },
  remove(state, audiobook) {
    state.audiobooks = state.audiobooks.filter(a => a.id !== audiobook.id)

    if (audiobook.book) {
      // GENRES
      audiobook.book.genres.forEach((genre) => {
        if (!STANDARD_GENRES.includes(genre)) {
          var isInOtherAB = state.audiobooks.find(ab => {
            return ab.book && ab.book.genres.includes(genre)
          })
          if (!isInOtherAB) {
            // Genre is not used by any other audiobook - remove it
            state.genres = state.genres.filter(g => g !== genre)
          }
        }
      })

      // SERIES
      if (audiobook.book.series) {
        var isInOtherAB = state.audiobooks.find(ab => ab.book && ab.book.series === audiobook.book.series)
        if (!isInOtherAB) {
          // Series not used in any other audiobook - remove it
          state.series = state.series.filter(s => s !== audiobook.book.series)
        }
      }
    }

    // TAGS
    audiobook.tags.forEach((tag) => {
      var isInOtherAB = state.audiobooks.find(ab => {
        return ab.tags.includes(tag)
      })
      if (!isInOtherAB) {
        // Tag is not used by any other audiobook - remove it
        state.tags = state.tags.filter(t => t !== tag)
      }
    })

    state.listeners.forEach((listener) => {
      if (!listener.audiobookId || listener.audiobookId === audiobook.id) {
        listener.meth()
      }
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