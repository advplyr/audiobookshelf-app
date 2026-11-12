const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const test = require('node:test')

const source = readFileSync(resolve(__dirname, '../pages/bookshelf/narrators.vue'), 'utf8').split('<script>')[1].split('</script>')[0]
const context = { module: { exports: {} }, Intl, setTimeout, clearTimeout }
runInNewContext(source.replace('export default', 'module.exports ='), context)
const component = context.module.exports

function page() {
  const pending = []
  const state = {
    ...component.data(), currentLibraryId: 'one', sortBy: 'name', sortDesc: false,
    $eventBus: { $emit() {} },
    $nativeHttp: { get(url) { return new Promise((resolve, reject) => pending.push({ url, resolve, reject })) } }
  }
  for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state)
  return { state, pending }
}

test('name and count sorting support both directions without mutating fetched data', () => {
  const { state } = page()
  state.narrators = [{ name: 'Voice 10', numBooks: 2 }, { name: 'Voice 2', numBooks: 8 }, { name: 'Alpha', numBooks: 2 }]
  const sortedNames = () => Array.from(component.computed.sortedNarrators.call(state), (n) => n.name)
  assert.deepEqual(sortedNames(), ['Alpha', 'Voice 2', 'Voice 10'])
  state.sortDesc = true
  assert.deepEqual(sortedNames(), ['Voice 10', 'Voice 2', 'Alpha'])
  state.sortBy = 'numBooks'
  assert.deepEqual(sortedNames(), ['Voice 2', 'Alpha', 'Voice 10'])
  state.sortDesc = false
  assert.deepEqual(sortedNames(), ['Alpha', 'Voice 10', 'Voice 2'])
  assert.equal(state.narrators[0].name, 'Voice 10')
})

test('a previous library response cannot replace the current library', async () => {
  const { state, pending } = page()
  const old = state.init()
  state.currentLibraryId = 'two'
  const current = state.init()
  assert.equal(pending[1].url, '/api/libraries/two/narrators')
  pending[1].resolve({ narrators: [{ name: 'Current', numBooks: 3 }] })
  await current
  pending[0].resolve({ narrators: [{ name: 'Old', numBooks: 9 }] })
  await old
  assert.equal(state.narrators[0].name, 'Current')
  assert.equal(state.loading, false)
})

test('errors and invalid responses expose retry; a successful empty retry clears the error', async () => {
  for (const response of [null, { narrators: [{ name: 'Bad', numBooks: '3' }] }]) {
    const { state, pending } = page()
    const failed = state.init()
    pending[0].resolve(response)
    await failed
    assert.equal(state.loadFailed, true)
    assert.equal(state.loading, false)
    const retry = state.init()
    pending[1].resolve({ narrators: [] })
    await retry
    assert.equal(state.loadFailed, false)
    assert.equal(state.narrators.length, 0)
  }
  const { state, pending } = page()
  const offline = state.init()
  pending[0].reject(new Error('Offline'))
  await offline
  assert.equal(state.loadFailed, true)
})

test('socket updates invalidate in-flight responses before the debounced reload', async () => {
  const { state, pending } = page()
  const old = state.init()
  state.scheduleReload()
  pending[0].resolve({ narrators: [{ name: 'Removed', numBooks: 1 }] })
  await old
  assert.equal(state.narrators.length, 0)
  clearTimeout(state.reloadTimer)
})
