export const state = () => ({
  libraryItemPlaying: null,
  mediaEntityPlaying: null
})

export const getters = {
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

    var url = new URL(`/api/items/${libraryItem.id}/cover`, rootState.serverUrl)
    return `${url}?token=${userToken}&ts=${lastUpdate}`
  }
}

export const actions = {

}

export const mutations = {
  setLibraryItemPlaying(state, libraryItem) {
    state.libraryItemPlaying = libraryItem
  },
  setMediaEntityPlaying(state, mediaEntity) {
    state.mediaEntityPlaying = mediaEntity
  }
}