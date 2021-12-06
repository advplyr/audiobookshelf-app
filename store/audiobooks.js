export const state = () => ({

})

export const getters = {
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

}

export const mutations = {

}