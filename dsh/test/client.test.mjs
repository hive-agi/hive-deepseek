// Unit tests for the browser half: load lib/client.js the way dsh's module
// loader does (a registered factory given `require`), then exercise the pure
// exports and the plugin's registrations against a recording context.
import { test } from 'node:test'
// Legacy (loose) assert: values built inside the vm realm carry that realm's
// Object/Array prototypes, which strict deep equality rejects.
import assert from 'node:assert'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'

const source = readFileSync(fileURLToPath(new URL('../lib/client.js', import.meta.url)), 'utf8')

function load(extraGlobals = {}) {
  const registered = []
  const sandbox = {
    window: { __ModuleLoader__: { load: entry => registered.push(entry) }, dispatchEvent: () => true },
    ...extraGlobals,
  }
  sandbox.globalThis = sandbox
  vm.runInNewContext(source, sandbox)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'dsh-hive-vessel')
  const react = { createElement: (...args) => args, Fragment: 'Fragment', useSyncExternalStore: () => {}, useState: v => [v, () => {}], useEffect: () => {} }
  return registered[0].factory(name => {
    if (name === 'react') return react
    throw new Error(`unexpected require ${name}`)
  })
}

const client = load()

const panel = (id, title) => ({
  op: 'ui/show-panel',
  'panel/id': id,
  doc: { 'doc/title': title, 'doc/blocks': [] },
  lines: [{ text: title, face: 'title' }],
})

test('only platform modules are required', () => {
  assert.equal(typeof client.apply, 'function')
  assert.deepEqual(client.inject, ['slots', 'sidebarRightTabs', 'sidebarRight'])
})

test('show-panel stores, orders and focuses panels; close drops and refocuses', () => {
  let s = client.initialState()
  s = client.reduce(s, panel('a', 'A'))
  s = client.reduce(s, panel('b', 'B'))
  s = client.reduce(s, panel('a', 'A2'))
  assert.deepEqual(s.order, ['a', 'b'])
  assert.equal(s.focus, 'a')
  assert.equal(s.panels.a.title, 'A2')
  s = client.reduce(s, { op: 'ui/close-panel', 'panel/id': 'a' })
  assert.deepEqual(s.order, ['b'])
  assert.equal(s.focus, 'b')
  assert.equal(s.panels.a, undefined)
})

test('notices and events are bounded; unknown ops are ignored', () => {
  let s = client.initialState()
  for (let i = 0; i < 30; i++) s = client.reduce(s, { op: 'ui/notify', message: `n${i}` })
  assert.equal(s.notices.length, 20)
  assert.equal(s.notices.at(-1).message, 'n29')
  assert.equal(client.reduce(s, { op: 'mystery/op' }), s)
  s = client.reduce(s, { op: 'json/event', event: 'carto/tick', data: { n: 1 } })
  assert.deepEqual(s.events.at(-1), { event: 'carto/tick', data: { n: 1 } })
})

test('terminal ops are reported, never silently dropped', () => {
  const s = client.reduce(client.initialState(), { op: 'ui/send-to-terminal', terminal: 'repl', text: 'x' })
  assert.equal(s.notices[0].level, 'warn')
  assert.deepEqual(client.effectsOf({ op: 'ui/send-to-terminal', terminal: 'repl' }).map(e => e.kind), ['toast'])
})

test('effects are data', () => {
  assert.deepEqual(client.effectsOf(panel('a', 'A')), [{ kind: 'reveal-tab' }])
  assert.deepEqual(client.effectsOf({ op: 'ui/open-file', file: '/x', line: 3 }),
    [{ kind: 'open-file', file: '/x', line: 3, column: undefined }])
  assert.deepEqual(client.effectsOf({ op: 'json/event', event: 'e', data: 1 }), [{ kind: 'emit', event: 'e', data: 1 }])
  assert.deepEqual(client.effectsOf({ op: 'nope' }), [])
})

test('file addresses are session-scoped, the only scope dsh viewers claim', () => {
  assert.equal(client.fileAddress('/home/me/a b#.clj', 's-1'), 'dsh-resource://file/session/s-1//home/me/a%20b%23.clj')
  assert.equal(client.fileAddress('/home/me/a.clj', undefined), undefined)
  assert.equal(client.fileAddress('src/a.clj', 's-1'), 'dsh-resource://file/session/s-1/src/a.clj')
  assert.equal(client.fileAddress('./src/a.clj', 's-1'), 'dsh-resource://file/session/s-1/src/a.clj')
  assert.equal(client.fileAddress('C:\\x\\y.txt', 's-1'), 'dsh-resource://file/session/s-1/C:/x/y.txt')
  assert.equal(client.fileAddress('src/a.clj', undefined), undefined)
})

test('every hive-vessel face has a style', () => {
  for (const face of ['title', 'heading', 'plain', 'muted', 'info', 'success', 'warn', 'error', 'added', 'removed', 'hunk', 'code', 'link']) {
    assert.equal(typeof client.lineStyle(face), 'object', face)
  }
})

test('reconnect backoff doubles from 1 s and caps at 30 s', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 20].map(client.reconnectDelay),
    [1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000])
})

test('bridge url and config resolution', () => {
  assert.equal(client.bridgeUrl('http://h:1/', '/vessel/events'), 'http://h:1/vessel/events')
  assert.equal(client.bridgeUrl('http://h:1', '/vessel/events', 'a b'), 'http://h:1/vessel/events?token=a%20b')
  const storage = { getItem: k => ({ 'hive-vessel.url': 'http://s:2' })[k] ?? null }
  assert.deepEqual(client.resolveConfig({}, storage), { url: 'http://s:2', token: undefined })
  assert.deepEqual(client.resolveConfig({ url: 'http://c:3', token: 't' }, storage), { url: 'http://c:3', token: 't' })
  assert.deepEqual(client.resolveConfig(undefined, { getItem: () => { throw new Error('blocked') } }), { url: 'http://127.0.0.1:7925', token: undefined })
})

test('apply registers the tab type, body, title and bridge through ctx.effect', () => {
  const effects = []
  const registered = { tabs: [], slots: [] }
  const sources = []
  class FakeEventSource {
    constructor (url) { this.url = url; this.listeners = {}; sources.push(this) }
    addEventListener (name, fn) { this.listeners[name] = fn }
    close () { this.closed = true }
  }
  const globals = {
    document: { createElement: () => ({ setAttribute () {}, style: {}, appendChild () {}, remove () {} }), body: { appendChild () {} } },
    EventSource: FakeEventSource,
    fetch: () => Promise.resolve(),
    location: { href: 'http://127.0.0.1:3080/?token=secret' },
    CustomEvent: class { constructor (type, init) { this.type = type; this.detail = init.detail } },
    setTimeout: () => 0,
  }
  const c = load(globals)
  const opened = []
  const ctx = {
    effect: (fn, label) => { effects.push(label); return fn() },
    sidebarRightTabs: { register: def => { registered.tabs.push(def); return () => {} } },
    slots: {
      inject: (_seat, fn) => fn(),
      register: (opts, component) => { registered.slots.push([opts.name, opts.key, typeof component]); return () => {} },
    },
    sidebarRight: { openTab: kind => opened.push(['tab', kind]), openResource: (a, o) => opened.push(['resource', a, o]) },
    emit: () => {},
  }
  c.apply(ctx, { url: 'http://127.0.0.1:9999', token: 'tk' })
  assert.equal(effects.length, 4)
  assert.equal(registered.tabs[0].id, 'dsh-hive-vessel')
  assert.equal(registered.tabs[0].kind, 'hive')
  assert.deepEqual(registered.slots.map(s => s.slice(0, 2)), [
    ['sidebar.right.pane.tab', 'dsh-hive-vessel'],
    ['sidebar.right.pane.tab.title', 'dsh-hive-vessel'],
  ])
  assert.equal(sources[0].url, 'http://127.0.0.1:9999/vessel/events?token=tk')
  sources[0].listeners.vessel({ data: JSON.stringify(panel('p', 'P')) })
  sources[0].listeners.vessel({ data: JSON.stringify({ op: 'ui/open-file', file: '/abs/f.clj', line: 4 }) })
  // No Hive body has mounted, so no session is known: the open waits and the tab is revealed.
  assert.deepEqual(opened, [['tab', 'hive'], ['tab', 'hive']])
})
