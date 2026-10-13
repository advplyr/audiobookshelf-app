export function sortPodcastEpisodes(episodes, sortKey, sortDesc) {
  return [...episodes].sort((a, b) => {
    let aValue = getNestedValue(a, sortKey)
    let bValue = getNestedValue(b, sortKey)

    // Keep episodes without a publication date in the same position as the episode table.
    if (sortKey === 'publishedAt') {
      if (!aValue) aValue = Number.MAX_VALUE
      if (!bValue) bValue = Number.MAX_VALUE
    }

    if (sortDesc) {
      return compareValues(bValue, aValue)
    }
    return compareValues(aValue, bValue)
  })
}

function getNestedValue(object, path) {
  return path.split('.').reduce((value, key) => value?.[key], object)
}

function compareValues(a, b) {
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}
