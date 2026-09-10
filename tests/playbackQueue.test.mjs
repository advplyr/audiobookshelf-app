import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
// Load the Nuxt ES module without changing this project's CommonJS package type.
const helperSource = await readFile(new URL('../utils/playbackQueue.js', import.meta.url), 'utf8')
const { downloadedBookItems, fetchSeriesBooks, nativeQueueItems, resolvePlaybackQueue } = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)

const book = (id) => ({ id, mediaType: 'book', media: { numTracks: 1 } })
const local = (id, overrides = {}) => ({ id: `local_device_${id}`, libraryItemId: id, serverConnectionConfigId: 'server', mediaType: 'book', media: { tracks: [{}] }, ...overrides })
function context(localItems = []) {
  return {
    $platform: 'android',
    $encode: (value) => Buffer.from(value).toString('base64'),
    $db: { getLocalLibraryItems: async () => localItems },
    $store: { getters: { 'user/getServerConnectionConfigId': 'server', 'globals/getLocalMediaProgressById': () => null, 'user/getUserMediaProgress': () => null } }
  }
}

test('download filtering preserves source order and uses real local IDs, excluding invalid/foreign/non-audio items', () => {
  const books = ['c', 'b', 'a', 'missing', 'invalid', 'ebook', 'foreign'].map(book)
  books[4].isInvalid = true
  const items = downloadedBookItems(books, [local('a'), local('c'), local('invalid'), local('ebook', { media: { tracks: [] } }), local('foreign', { serverConnectionConfigId: 'other' })], 'server')
  assert.deepEqual(nativeQueueItems(items), [
    { libraryItemId: 'local_device_c', episodeId: null },
    { libraryItemId: 'local_device_a', episodeId: null }
  ])
})

test('partial downloads are not eligible for auto-advance', () => {
  assert.deepEqual(downloadedBookItems([{ ...book('a'), media: { numTracks: 3 } }], [local('a')], 'server'), [])
})

test('complete series uses server sequence order, all pages, and never shelf collapse', async () => {
  const calls = []
  const books = Array.from({ length: 205 }, (_, i) => book(String(205 - i)))
  const http = {
    get: async (url) => {
      calls.push(url)
      const query = new URL(url, 'http://test').searchParams
      assert.equal(query.get('sort'), 'sequence')
      assert.equal(query.get('desc'), '0')
      assert.equal(query.get('collapseseries'), null)
      assert.equal(query.get('filter'), 'series.encoded')
      const page = Number(query.get('page'))
      return { results: books.slice(page * 100, page * 100 + 100), total: 205 }
    }
  }
  assert.deepEqual(await fetchSeriesBooks(http, 'library', 'series', () => 'encoded'), books)
  assert.equal(calls.length, 3)
})

test('incomplete, failed or repeated series pages fail instead of playing a partial queue', async () => {
  await assert.rejects(fetchSeriesBooks({ get: async () => ({ results: [], total: 5 }) }, 'l', 's', String))
  await assert.rejects(
    fetchSeriesBooks(
      {
        get: async () => {
          throw new Error('offline')
        }
      },
      'l',
      's',
      String
    ),
    /offline/
  )
  await assert.rejects(fetchSeriesBooks({ get: async () => ({ results: [book('a')], total: 5 }) }, 'l', 's', String), /changed/)
})

test('Play All starts at first unfinished downloaded book using local progress', async () => {
  const ctx = context([local('a'), local('c'), local('d')])
  ctx.$store.getters['globals/getLocalMediaProgressById'] = (id) => ({ isFinished: id === 'local_device_a' })
  const result = await resolvePlaybackQueue(ctx, { queueSource: { sourceType: 'collection', sourceId: 'c', books: ['a', 'b', 'c', 'd'].map(book) } })
  assert.equal(result.queue.currentIndex, 1)
  assert.equal(result.payload.libraryItemId, 'local_device_c')
  assert.equal(result.payload.episodeId, null)
  assert.deepEqual(
    result.queue.items.map((item) => item.libraryItemId),
    ['a', 'c', 'd']
  )
})

test('starting a middle series book constructs the full queue and points at that book', async () => {
  const ctx = context(['1', '2', '3', '4'].map((id) => local(id)))
  ctx.$nativeHttp = { get: async () => ({ results: ['1', '2', '3', '4'].map(book), total: 4 }) }
  const result = await resolvePlaybackQueue(ctx, { libraryItemId: '3', queueSource: { sourceType: 'series', sourceId: 's', libraryId: 'l' } })
  assert.equal(result.queue.currentIndex, 2)
  assert.equal(nativeQueueItems(result.queue.items)[result.queue.currentIndex + 1].libraryItemId, 'local_device_4')
})

test('collection middle start follows collection order; missing download is never queued', async () => {
  const result = await resolvePlaybackQueue(context([local('1'), local('3'), local('4')]), { libraryItemId: 'local_device_3', queueSource: { sourceType: 'collection', sourceId: 'c', books: ['4', '3', '2', '1'].map(book) } })
  assert.equal(result.queue.currentIndex, 1)
  assert.equal(nativeQueueItems(result.queue.items)[2].libraryItemId, 'local_device_1')
})

test('manual non-downloaded selection has no queue; no source never acquires a queue', async () => {
  const payload = { libraryItemId: '2', queueSource: { sourceType: 'collection', sourceId: 'c', books: ['1', '2'].map(book) } }
  assert.equal((await resolvePlaybackQueue(context([local('1')]), payload)).queue, null)
  assert.equal((await resolvePlaybackQueue(context([local('1')]), { libraryItemId: '1' })).queue, null)
})

test('iOS and web do not build Android queues or call the database', async () => {
  for (const platform of ['ios', 'web']) {
    const result = await resolvePlaybackQueue({ $platform: platform }, { libraryItemId: '1', queueSource: { sourceType: 'series' } })
    assert.equal(result.queue, null)
  }
})

test('podcast playlist preserves local episode identity and streaming entries', async () => {
  const items = [
    { libraryItemId: 'p', episodeId: 'e1', localLibraryItem: { id: 'local_p' }, localEpisode: { id: 'local_e1' } },
    { libraryItemId: 'p', episodeId: 'e2' }
  ]
  const result = await resolvePlaybackQueue(context(), { libraryItemId: 'local_p', episodeId: 'local_e1', queueSource: { sourceType: 'playlist', sourceId: 'p1', items } })
  assert.equal(result.queue.currentIndex, 0)
  assert.deepEqual(nativeQueueItems(result.queue.items), [
    { libraryItemId: 'local_p', episodeId: 'local_e1' },
    { libraryItemId: 'p', episodeId: 'e2' }
  ])
  assert.equal(result.payload.serverEpisodeId, 'e1')
})

// Exercise the real container methods with a mocked Capacitor boundary.
async function container(native) {
  const source = await readFile(new URL('../components/app/AudioPlayerContainer.vue', import.meta.url), 'utf8')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { AbsAudioPlayer: native, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, nativeQueueItems, resolvePlaybackQueue, console }
  vm.runInNewContext(script, sandbox)
  const ctx = context([local('1'), local('2')])
  ctx.$store.state = { playbackQueue: null }
  ctx.$store.commit = (name, payload) => {
    if (name === 'setPlaybackQueue') ctx.$store.state.playbackQueue = payload
    if (name === 'clearPlaybackQueue') ctx.$store.state.playbackQueue = null
  }
  ctx.$toast = {
    error: (message) => {
      ctx.error = message
    }
  }
  ctx.$refs = {}
  ctx.$store.getters.getIsMediaStreaming = () => false
  Object.assign(ctx, sandbox.component.data())
  for (const [name, fn] of Object.entries(sandbox.component.methods)) ctx[name] = fn.bind(ctx)
  return ctx
}

test('unrelated playback clears both queues before starting; failed queue fetch does not play', async () => {
  const calls = []
  const ctx = await container({ clearPlaylistQueue: async () => calls.push('clear') })
  ctx.$store.state.playbackQueue = { sourceType: 'series' }
  ctx.startLibraryItem = async (_, queue) => {
    assert.equal(queue, null)
    calls.push('play')
  }
  await ctx.playLibraryItem({ libraryItemId: 'unrelated' })
  assert.deepEqual(calls, ['clear', 'play'])
  assert.equal(ctx.$store.state.playbackQueue, null)
  ctx.$nativeHttp = {
    get: async () => {
      throw new Error('offline')
    }
  }
  await ctx.playLibraryItem({ queueSource: { sourceType: 'series', sourceId: 's' } })
  assert.equal(ctx.error, 'offline')
  assert.equal(calls.filter((call) => call === 'play').length, 1)
})

test('native queue installation completes before local book preparation', async () => {
  const calls = []
  const ctx = await container({
    clearPlaylistQueue: async () => calls.push('clear'),
    setPlaylistQueue: async (queue) => {
      await Promise.resolve()
      assert.equal(queue.items[1].libraryItemId, 'local_device_2')
      assert.equal(queue.items[1].episodeId, null)
      calls.push('queue')
    },
    prepareLibraryItem: async (payload) => {
      calls.push('play')
      assert.equal(payload.libraryItemId, 'local_device_1')
      assert.equal(payload.episodeId, null)
      return {}
    }
  })
  await ctx.playLibraryItem({ queueSource: { sourceType: 'collection', sourceId: 'c', books: [book('1'), book('2')] } })
  assert.deepEqual(calls, ['clear', 'queue', 'play'])
  assert.equal(ctx.$store.state.playbackQueue.sourceType, 'collection')
})

test('failed native queue installation prevents playback', async () => {
  let played = false
  const ctx = await container({
    clearPlaylistQueue: async () => {},
    setPlaylistQueue: async () => {
      throw new Error('bridge failure')
    },
    prepareLibraryItem: async () => {
      played = true
    }
  })
  await ctx.playLibraryItem({ queueSource: { sourceType: 'collection', sourceId: 'c', books: [book('1')] } })
  assert.equal(played, false)
  assert.equal(ctx.$store.state.playbackQueue, null)
  assert.equal(ctx.error, 'bridge failure')
})

test('delayed ENDED reads authoritative native index; stale responses cannot replace a new queue', async () => {
  let resolve
  const ctx = await container({
    getPlaylistQueue: () =>
      new Promise((done) => {
        resolve = done
      })
  })
  const queue = { sourceType: 'collection', sourceId: 'c', items: [{ libraryItemId: 'a' }, { libraryItemId: 'b' }, { libraryItemId: 'c' }], currentIndex: 0 }
  ctx.$store.state.playbackQueue = queue
  const sync = ctx.onPlaybackEnded()
  resolve({ items: nativeQueueItems(queue.items), currentIndex: 2 })
  await sync
  assert.equal(ctx.$store.state.playbackQueue.currentIndex, 2)
  const stale = ctx.onPlaybackEnded()
  const replacement = { ...queue, sourceId: 'new' }
  ctx.$store.state.playbackQueue = replacement
  resolve({ items: [], currentIndex: -1 })
  await stale
  assert.equal(ctx.$store.state.playbackQueue, replacement)
  const ended = ctx.onPlaybackEnded()
  resolve({ items: [], currentIndex: -1 })
  await ended
  assert.equal(ctx.$store.state.playbackQueue, null)
})
