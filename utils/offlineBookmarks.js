// Bookmark identity on the server is the library item and playback second.
// A separate local id tracks a pending create before the server has seen it.
const DUPLICATE_TOLERANCE_SECONDS = 5

function position(time) {
  const value = Number(time)
  if (!Number.isFinite(value) || value < 0) throw new Error('Invalid bookmark position')
  return Math.floor(value)
}

function key(bookmark) {
  return `${bookmark.libraryItemId}:${position(bookmark.time)}`
}

function sameContent(a, b) {
  // Older servers expose only title. Compare descriptions when both sides
  // support them; local descriptions remain on this device otherwise.
  return a.title === b.title && (a.description === undefined || b.description === undefined || a.description === b.description)
}

function overlay(snapshot, pending) {
  const bookmarks = new Map((snapshot || []).map((bookmark) => [key(bookmark), { ...bookmark }]))
  for (const operation of pending || []) {
    const id = key(operation.bookmark)
    if (operation.type === 'delete') bookmarks.delete(id)
    else bookmarks.set(id, { ...operation.bookmark, pendingSync: true, syncConflict: !!operation.conflict })
  }
  return [...bookmarks.values()].sort((a, b) => a.time - b.time)
}

function coalesce(pending, type, bookmark, base, id, inFlight = false) {
  const operations = [...pending]
  const index = operations.findIndex((operation) => key(operation.bookmark) === key(bookmark))
  const previous = index < 0 ? null : operations[index]
  if (previous?.type === 'create' && type === 'delete' && !inFlight) {
    operations.splice(index, 1)
    return operations
  }
  const nextType = previous?.type === 'create' && type !== 'delete' ? 'create'
    : previous?.type === 'delete' && type === 'create' && !inFlight ? 'update' : type
  const operation = {
    id: previous?.id || id,
    type: nextType,
    bookmark: { ...bookmark, time: position(bookmark.time) },
    base: previous?.base || base || null,
    baseDescription: previous?.baseDescription,
    conflict: null,
    force: false
  }
  if (index < 0) operations.push(operation)
  else operations.splice(index, 1, operation)
  return operations
}

// Never equate nearby bookmarks based on position alone. A nearby bookmark
// with matching content is an ambiguity to surface, not one to merge.
function classify(operation, serverBookmarks, tolerance = DUPLICATE_TOLERANCE_SECONDS) {
  const itemBookmarks = (serverBookmarks || []).filter((bookmark) => bookmark.libraryItemId === operation.bookmark.libraryItemId)
  const exact = itemBookmarks.find((bookmark) => position(bookmark.time) === position(operation.bookmark.time))
  if (operation.type === 'create') {
    if (exact) return sameContent(exact, operation.bookmark) ? 'done' : operation.force ? 'send' : 'collision'
    const nearby = itemBookmarks.find((bookmark) => Math.abs(position(bookmark.time) - position(operation.bookmark.time)) <= tolerance && sameContent(bookmark, operation.bookmark))
    return nearby && !operation.force ? 'possible-duplicate' : 'send'
  }
  if (operation.type === 'delete') {
    if (!exact) return 'done'
    if (!operation.force && operation.base && !sameContent(exact, operation.base)) return 'remote-changed'
    return 'send'
  }
  if (!exact) return operation.force ? 'send' : 'remote-deleted'
  if (sameContent(exact, operation.bookmark)) return 'done'
  if (!operation.force && operation.base && !sameContent(exact, operation.base)) return 'remote-changed'
  return 'send'
}

function afterConfirmed(pending, submitted, serverBookmark) {
  return pending.flatMap((entry) => {
    if (entry.id !== submitted.id) return [entry]
    if (entry.type === submitted.type && sameContent(entry.bookmark, submitted.bookmark)) return []
    // The user made another change while the request was in flight. Keep it
    // and use the newly confirmed server state as its comparison base.
    return [{ ...entry, type: submitted.type === 'delete' && entry.type === 'create' ? 'create' : entry.type === 'delete' ? 'delete' : 'update', base: serverBookmark || null }]
  })
}

module.exports = { DUPLICATE_TOLERANCE_SECONDS, position, key, sameContent, overlay, coalesce, classify, afterConfirmed }
