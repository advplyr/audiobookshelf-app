export const state = () => ({

})

export const getters = {
  getBookCoverSrc: (state, getters, rootState, rootGetters) => (bookItem, placeholder = '/book_placeholder.jpg') => {
    if (!bookItem) return placeholder
    var book = bookItem.book
    if (!book || !book.cover || book.cover === placeholder) return placeholder

    // Absolute URL covers (should no longer be used)
    if (book.cover.startsWith('http:') || book.cover.startsWith('https:')) return book.cover

    var userToken = rootGetters['user/getToken']
    var bookLastUpdate = book.lastUpdate || Date.now()

    if (!bookItem.id) {
      console.error('No book item id', bookItem)
    }
    if (process.env.NODE_ENV !== 'production') { // Testing
      return `http://localhost:3333/api/books/${bookItem.id}/cover?token=${userToken}&ts=${bookLastUpdate}`
    }

    var url = new URL(`/api/books/${bookItem.id}/cover`, rootState.serverUrl)
    return `${url}?token=${userToken}&ts=${bookLastUpdate}`
  }
}

export const actions = {

}

export const mutations = {

}