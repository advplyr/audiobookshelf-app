import { sort } from '@/assets/fastSort'
import { decode } from '@/plugins/init.client'

const STANDARD_GENRES = ['adventure', 'autobiography', 'biography', 'childrens', 'comedy', 'crime', 'dystopian', 'fantasy', 'fiction', 'health', 'history', 'horror', 'mystery', 'new_adult', 'nonfiction', 'philosophy', 'politics', 'religion', 'romance', 'sci-fi', 'self-help', 'short_story', 'technology', 'thriller', 'true_crime', 'western', 'young_adult']

export const state = () => ({
  audiobooks: [],
  listeners: [],
  genres: [...STANDARD_GENRES],
  tags: [],
  series: [],
  loadedLibraryId: 'main',
  lastLoad: 0,
  isLoading: false
})

export const getters = {
  getAudiobook: state => id => {
    return state.audiobooks.find(ab => ab.id === id)
  },
  getFiltered: (state, getters, rootState, rootGetters) => () => {
    var filtered = state.audiobooks
    var settings = rootState.user.settings || {}
    var filterBy = settings.mobileFilterBy || ''

    var searchGroups = ['genres', 'tags', 'series', 'authors', 'progress']
    var group = searchGroups.find(_group => filterBy.startsWith(_group + '.'))
    if (group) {
      var filter = decode(filterBy.replace(`${group}.`, ''))
      if (group === 'genres') filtered = filtered.filter(ab => ab.book && ab.book.genres.includes(filter))
      else if (group === 'tags') filtered = filtered.filter(ab => ab.tags.includes(filter))
      else if (group === 'series') {
        if (filter === 'No Series') filtered = filtered.filter(ab => ab.book && !ab.book.series)
        else filtered = filtered.filter(ab => ab.book && ab.book.series === filter)
      }
      // else if (group === 'series') filtered = filtered.filter(ab => ab.book && ab.book.series === filter)
      else if (group === 'authors') filtered = filtered.filter(ab => ab.book && ab.book.author === filter)
      else if (group === 'progress') {
        filtered = filtered.filter(ab => {
          var userAudiobook = rootGetters['user/getUserAudiobookData'](ab.id)
          var isRead = userAudiobook && userAudiobook.isRead
          if (filter === 'Read' && isRead) return true
          if (filter === 'Unread' && !isRead) return true
          if (filter === 'In Progress' && (userAudiobook && !userAudiobook.isRead && userAudiobook.progress > 0)) return true
          return false
        })
      }
    }
    return filtered
  },
  getFilteredAndSorted: (state, getters, rootState, rootGetters) => () => {
    var settings = rootState.user.settings
    var direction = settings.mobileOrderDesc ? 'desc' : 'asc'

    var filtered = getters.getFiltered()

    if (settings.mobileOrderBy === 'recent') {
      return sort(filtered)[direction]((ab) => {
        var abprogress = rootGetters['user/getUserAudiobookData'](ab.id)
        if (!abprogress) return 0
        return abprogress.lastUpdate
      })
    } else {
      var orderByNumber = settings.mobileOrderBy === 'book.volumeNumber'
      return sort(filtered)[direction]((ab) => {
        // Supports dot notation strings i.e. "book.title"
        var value = settings.mobileOrderBy.split('.').reduce((a, b) => a[b], ab)
        if (orderByNumber && !isNaN(value)) return Number(value)
        return value
      })
    }
  },
  getSeriesGroups: (state, getters, rootState) => () => {
    var series = {}
    state.audiobooks.forEach((audiobook) => {
      if (audiobook.book && audiobook.book.series) {
        if (series[audiobook.book.series]) {
          var bookLastUpdate = audiobook.book.lastUpdate
          if (bookLastUpdate > series[audiobook.book.series].lastUpdate) series[audiobook.book.series].lastUpdate = bookLastUpdate
          series[audiobook.book.series].books.push(audiobook)
        } else {
          series[audiobook.book.series] = {
            type: 'series',
            name: audiobook.book.series || '',
            books: [audiobook],
            lastUpdate: audiobook.book.lastUpdate
          }
        }
      }
    })
    var seriesArray = Object.values(series).map((_series) => {
      _series.books = sort(_series.books)['asc']((ab) => {
        return ab.book && ab.book.volumeNumber && !isNaN(ab.book.volumeNumber) ? Number(ab.book.volumeNumber) : null
      })
      return _series
    })
    if (state.keywordFilter) {
      const keywordFilter = state.keywordFilter.toLowerCase()
      return seriesArray.filter((_series) => _series.name.toLowerCase().includes(keywordFilter))
    }
    return seriesArray
  },
  getUniqueAuthors: (state) => {
    var _authors = state.audiobooks.filter(ab => !!(ab.book && ab.book.author)).map(ab => ab.book.author)
    return [...new Set(_authors)]
  },
  getGenresUsed: (state) => {
    var _genres = []
    state.audiobooks.filter(ab => !!(ab.book && ab.book.genres)).forEach(ab => _genres = _genres.concat(ab.book.genres))
    return [...new Set(_genres)].sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)
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
  load({ state, commit, dispatch, rootState }) {
    if (!rootState.user || !rootState.user.user) {
      console.error('audiobooks/load - User not set')
      return false
    }

    var currentLibraryId = rootState.libraries.currentLibraryId

    if (currentLibraryId === state.loadedLibraryId) {
      // Don't load again if already loaded in the last 5 minutes
      var lastLoadDiff = Date.now() - state.lastLoad
      if (lastLoadDiff < 5 * 60 * 1000) {
        // Already up to date
        return false
      }
    } else {
      commit('reset')
      commit('setLoading', true)
    }
    commit('setLoadedLibrary', currentLibraryId)

    this.$axios
      .$get(`/api/library/${currentLibraryId}/audiobooks`)
      .then((data) => {
        commit('set', data)
        commit('setLastLoad')
        commit('setLoading', false)

        dispatch('downloads/linkOrphanDownloads', data, { root: true })
      })
      .catch((error) => {
        console.error('Failed', error)
        commit('set', [])
        commit('setLoading', false)
      })
    return true
  },
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
  set(state, audiobooks) {
    // GENRES
    var genres = [...state.genres]
    audiobooks.forEach((ab) => {
      if (!ab.book) return
      genres = genres.concat(ab.book.genres)
    })
    state.genres = [...new Set(genres)] // Remove Duplicates
    state.genres.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)

    // TAGS
    var tags = []
    audiobooks.forEach((ab) => {
      tags = tags.concat(ab.tags)
    })
    state.tags = [...new Set(tags)] // Remove Duplicates
    state.tags.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)

    // SERIES
    var series = []
    audiobooks.forEach((ab) => {
      if (!ab.book || !ab.book.series || series.includes(ab.book.series)) return
      series.push(ab.book.series)
    })
    state.series = series
    state.series.sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1)

    audiobooks.forEach((ab) => {
      var indexOf = state.audiobooks.findIndex(_ab => _ab.id === ab.id)
      if (indexOf >= 0) {
        state.audiobooks.splice(indexOf, 1, ab)
      } else {
        state.audiobooks.push(ab)
      }
    })
    // state.audiobooks = audiobooks
    state.listeners.forEach((listener) => {
      listener.meth()
    })
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