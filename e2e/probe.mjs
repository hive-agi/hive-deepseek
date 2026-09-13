// Drive a real DeepSeek Harness web page in headless Chromium and check what
// dsh-hive-vessel renders for hive's pushes. Run through e2e/run.sh, which
// boots dsh with the plugin installed and hive-deepseek's demo process.
//
//   node probe.mjs <dsh-url-with-token> <out-dir> <chromium> <workspace-dir> <file>
//
// Exit code 0 only when every check passed; report.json holds the details.
import { chromium } from 'playwright-core'
import { mkdirSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'

const [url, outDir, executablePath, workspace, file] = process.argv.slice(2)
mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({ headless: true, executablePath })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const logs = []
page.on('console', m => logs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', e => logs.push(`[pageerror] ${e.message}`))

const checks = []
const check = (name, ok, detail) => {
  checks.push({ name, ok: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail === undefined ? '' : ' ' + JSON.stringify(detail)}`)
}
const shot = name => page.screenshot({ path: join(outDir, name) })
const hiveState = () => page.evaluate(() => window.__hiveVessel?.store.get())

try {
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await page.waitForFunction(() => window.__hiveVessel?.store.get().status === 'connected', null, { timeout: 60000 })
  check('plugin loaded inside dsh and connected to the hive bridge', true)

  // A session gives the right Sidebar a surface to reveal the Hive tab on.
  await page.getByRole('button', { name: 'Continue' }).click({ timeout: 5000 }).catch(() => {})
  await page.getByRole('button', { name: 'Configure later' }).click({ timeout: 5000 }).catch(() => {})
  const composer = page.locator('[data-composer-input][contenteditable="true"]').first()
  const ready = await composer.waitFor({ timeout: 5000 }).then(() => true, () => false)
  if (!ready) {
    // Fresh DSH_HOME: no workspace yet, pick one with dsh's in-browser picker.
    await page.getByRole('button', { name: 'Choose workspace' }).click({ timeout: 10000 })
    await page.getByRole('button', { name: 'Edit path' }).click({ timeout: 10000 })
    await page.keyboard.press('Control+A')
    await page.keyboard.type(workspace)
    await page.keyboard.press('Enter')
    await page.getByRole('button', { name: 'Open', exact: true }).click({ timeout: 10000 })
  }
  await page.locator('[data-composer-input][contenteditable="true"]').first().click({ timeout: 10000 })
  await page.keyboard.type('hive e2e session')
  await page.keyboard.press('Enter')
  await page.getByRole('button', { name: 'Open right sidebar' }).waitFor({ timeout: 30000 })
  check('dsh session created', true)

  await page.evaluate(() => window.__hiveVessel.post({ op: 'e2e/ready' }))

  await page.waitForFunction(() => Object.keys(window.__hiveVessel.store.get().panels).length >= 2, null, { timeout: 60000 })
  const state = await hiveState()
  check('panels arrived in order', JSON.stringify(state.order) === '["demo","notes"]', state.order)
  check('failure notice recorded', state.notices.some(n => n.level === 'error' && n.message.includes('rename-symbol')), state.notices)
  check('json/event recorded', state.events.some(e => e.event === 'demo/tick'), state.events)

  const toasts = await page.$$eval('[data-hive-toast]', els => els.map(e => ({ level: e.dataset.hiveToast, text: e.textContent })))
  check('error toast shown', toasts.some(t => t.level === 'error'), toasts)

  const body = page.locator('[data-hive-vessel]')
  await body.waitFor({ state: 'visible', timeout: 15000 })
  check('Hive tab revealed in the right Sidebar', true)
  await shot('1-notes-panel.png')

  await page.locator('[data-hive-panel-tab="demo"]').click()
  const panel = page.locator('[data-hive-panel="demo"]')
  await panel.waitFor({ state: 'visible', timeout: 10000 })
  const lines = await panel.locator('[data-face]').evaluateAll(els => els.map(e => [e.dataset.face, e.textContent]))
  check('panel paints hive-vessel render-lines', lines[0]?.[0] === 'title' && lines[0]?.[1] === 'Operation #2', lines.slice(0, 3))
  check('diff faces applied', lines.some(([f, t]) => f === 'added' && t === '+(new-name)') &&
    lines.some(([f, t]) => f === 'removed' && t === '-(old-name)'), lines.filter(([f]) => f === 'added' || f === 'removed'))
  await shot('2-operation-panel.png')

  // hive's own ui/open-file arrives after the panels; dsh opens its viewer.
  const name = basename(file)
  const opened = await page.waitForFunction(n => [...document.querySelectorAll('body *')]
    .some(e => e.children.length === 0 && e.textContent === n), name, { timeout: 20000 }).then(() => true, () => false)
  check('ui/open-file opened the file in dsh', opened, name)
  await shot('3-open-file.png')

  // A link line in the panel opens the same viewer through the tab actions.
  // The file tab took focus; go back to the Hive chip first.
  await page.getByText('Hive', { exact: true }).first().click({ timeout: 10000 })
  await page.locator('[data-hive-panel-tab="demo"]').click({ timeout: 10000 })
  // Record what the plugin reports back to hive for this click.
  await page.evaluate(() => {
    const hv = window.__hiveVessel
    hv.sent = []
    const post = hv.post
    hv.post = m => { hv.sent.push(m); return post(m) }
  })
  await page.locator('[data-hive-panel="demo"] [data-face="link"]').click({ timeout: 10000 })
  const reopened = await page.waitForFunction(() => document.querySelector('[data-hive-vessel]') === null ||
    document.querySelector('[data-hive-vessel]').getBoundingClientRect().width === 0, null, { timeout: 15000 })
    .then(() => true, () => false)
  const sent = await page.evaluate(() => window.__hiveVessel.sent)
  check('clicking a panel link opens the file in dsh and hides the Hive body', reopened, sent)
  await shot('4-link-opened.png')
} catch (e) {
  check('unexpected error', false, String(e?.message ?? e).slice(0, 500))
  await shot('error.png').catch(() => {})
} finally {
  writeFileSync(join(outDir, 'console.log'), logs.join('\n'))
  writeFileSync(join(outDir, 'report.json'), JSON.stringify({ checks }, null, 2))
  await browser.close()
}
process.exit(checks.every(c => c.ok) ? 0 : 1)
