/**
 * Client-side book/collection filter and sort helpers.
 * Used where the server ignores or lacks filter/sort (collections).
 */

export function parseFilter(filterBy, decodeFn) {
  if (!filterBy || filterBy === 'all') return null
  const parts = filterBy.split('.')
  const group = parts.shift()
  const encoded = parts.join('.')
  const value = encoded ? decodeFn(encoded) : null
  return { group, value }
}

function getMedia(libraryItem) {
  return libraryItem?.media || {}
}

function getMetadata(libraryItem) {
  return getMedia(libraryItem).metadata || {}
}

function getTags(libraryItem) {
  const media = getMedia(libraryItem)
  return media.tags || libraryItem.tags || []
}

function getProgress(libraryItem, getUserMediaProgress) {
  if (!getUserMediaProgress) return null
  return getUserMediaProgress(libraryItem.id) || null
}

/**
 * @param {object} libraryItem
 * @param {string} filterBy
 * @param {(encoded: string) => string} decodeFn
 * @param {(id: string) => object|null} getUserMediaProgress
 * @param {object} [filterData] optional library filterData for author name fallback
 */
export function bookMatchesFilter(libraryItem, filterBy, decodeFn, getUserMediaProgress, filterData = null) {
  const parsed = parseFilter(filterBy, decodeFn)
  if (!parsed) return true

  const { group, value } = parsed
  const meta = getMetadata(libraryItem)
  const media = getMedia(libraryItem)

  if (group === 'genres') {
    return (meta.genres || []).includes(value)
  }
  if (group === 'tags') {
    return getTags(libraryItem).includes(value)
  }
  if (group === 'authors') {
    if (Array.isArray(meta.authors) && meta.authors.length) {
      return meta.authors.some((au) => au.id === value || au === value)
    }
    // Minified books only expose authorName — match via filterData name
    const author = (filterData?.authors || []).find((au) => au.id === value)
    if (author?.name && meta.authorName) {
      return meta.authorName
        .split(',')
        .map((n) => n.trim().toLowerCase())
        .includes(author.name.toLowerCase())
    }
    return false
  }
  if (group === 'narrators') {
    if (Array.isArray(meta.narrators) && meta.narrators.length) {
      return meta.narrators.includes(value)
    }
    if (meta.narratorName) {
      return meta.narratorName
        .split(',')
        .map((n) => n.trim().toLowerCase())
        .includes(String(value).toLowerCase())
    }
    return false
  }
  if (group === 'languages') {
    return meta.language === value
  }
  if (group === 'publishers') {
    return meta.publisher === value
  }
  if (group === 'series') {
    if (value === 'no-series') {
      const series = meta.series
      return !series || (Array.isArray(series) && !series.length)
    }
    if (Array.isArray(meta.series) && meta.series.length) {
      return meta.series.some((se) => se.id === value)
    }
    return false
  }
  if (group === 'progress') {
    const progress = getProgress(libraryItem, getUserMediaProgress)
    if (value === 'finished') return !!progress?.isFinished
    if (value === 'not-finished') return !progress?.isFinished
    if (value === 'not-started') return !progress || (!progress.isFinished && !progress.currentTime && !progress.progress)
    if (value === 'in-progress') return !!progress && !progress.isFinished && (!!progress.currentTime || !!progress.progress)
    return true
  }
  if (group === 'ebooks') {
    if (value === 'ebook') return !!(media.ebookFormat || media.ebookFileFormat || libraryItem.media?.ebookFile)
    return true
  }
  if (group === 'issues') {
    return !!(libraryItem.isMissing || libraryItem.isInvalid)
  }
  if (group === 'explicit') {
    return !!meta.explicit
  }
  if (group === 'feed-open') {
    return !!(libraryItem.rssFeed || media.rssFeed)
  }

  return true
}

export function collectionMatchesFilter(collection, filterBy, decodeFn, getUserMediaProgress, filterData = null) {
  const parsed = parseFilter(filterBy, decodeFn)
  if (!parsed) return true
  const books = collection?.books || []
  if (!books.length) return false
  return books.some((book) => bookMatchesFilter(book, filterBy, decodeFn, getUserMediaProgress, filterData))
}

function compareValues(a, b, desc) {
  if (a == null && b == null) return 0
  if (a == null) return desc ? -1 : 1
  if (b == null) return desc ? 1 : -1
  if (typeof a === 'string' && typeof b === 'string') {
    const cmp = a.localeCompare(b, undefined, { sensitivity: 'base' })
    return desc ? -cmp : cmp
  }
  if (a < b) return desc ? 1 : -1
  if (a > b) return desc ? -1 : 1
  return 0
}

function getNested(obj, path) {
  return path.split('.').reduce((acc, key) => (acc == null ? acc : acc[key]), obj)
}

export function sortCollections(collections, sortBy, sortDesc) {
  const list = [...(collections || [])]
  if (!sortBy || sortBy === 'random') {
    if (sortBy === 'random') {
      for (let i = list.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        ;[list[i], list[j]] = [list[j], list[i]]
      }
    }
    return list
  }

  list.sort((a, b) => {
    let av
    let bv
    if (sortBy === 'name') {
      av = a.name || ''
      bv = b.name || ''
    } else if (sortBy === 'numBooks') {
      av = (a.books || []).length
      bv = (b.books || []).length
    } else if (sortBy === 'createdAt') {
      av = a.createdAt || 0
      bv = b.createdAt || 0
    } else if (sortBy === 'recent' || sortBy === 'lastUpdate') {
      av = a.lastUpdate || a.updatedAt || 0
      bv = b.lastUpdate || b.updatedAt || 0
    } else {
      av = a[sortBy]
      bv = b[sortBy]
    }
    return compareValues(av, bv, sortDesc)
  })
  return list
}

export function filterAndSortBooks(books, filterBy, orderBy, orderDesc, decodeFn, getUserMediaProgress, filterData = null) {
  let list = [...(books || [])]
  if (filterBy && filterBy !== 'all') {
    list = list.filter((book) => bookMatchesFilter(book, filterBy, decodeFn, getUserMediaProgress, filterData))
  }
  if (!orderBy || orderBy === 'random') {
    if (orderBy === 'random') {
      for (let i = list.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        ;[list[i], list[j]] = [list[j], list[i]]
      }
    }
    return list
  }

  list.sort((a, b) => {
    let av
    let bv
    if (orderBy === 'progress') {
      av = getProgress(a, getUserMediaProgress)?.progress || 0
      bv = getProgress(b, getUserMediaProgress)?.progress || 0
    } else if (orderBy === 'progress.createdAt') {
      av = getProgress(a, getUserMediaProgress)?.createdAt || 0
      bv = getProgress(b, getUserMediaProgress)?.createdAt || 0
    } else if (orderBy === 'progress.finishedAt') {
      av = getProgress(a, getUserMediaProgress)?.finishedAt || 0
      bv = getProgress(b, getUserMediaProgress)?.finishedAt || 0
    } else {
      av = getNested(a, orderBy)
      bv = getNested(b, orderBy)
    }
    return compareValues(av, bv, orderDesc)
  })
  return list
}

export function filterAndSortCollections(collections, filterBy, sortBy, sortDesc, decodeFn, getUserMediaProgress, filterData = null) {
  let list = [...(collections || [])]
  if (filterBy && filterBy !== 'all') {
    list = list.filter((c) => collectionMatchesFilter(c, filterBy, decodeFn, getUserMediaProgress, filterData))
  }
  return sortCollections(list, sortBy, sortDesc)
}
