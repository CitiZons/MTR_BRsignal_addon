const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../src/main/resources/assets/mtr_brsignal_addon/web');
const out = path.resolve(__dirname, '../build/sensor-web-qa');
fs.mkdirSync(out, { recursive: true });
let editable = true;
const sensor = { id: 'minecraft:overworld@1', dimension: 'minecraft/overworld', x: 20, y: 64, z: 30, name: 'Crossing East', enabled: true, pulse: false, pulseTicks: 20, approach: ['A', 'T'], targets: ['T'], loaded: true, powered: false };
const rails = [{ id: 'A', points: [[0, 0], [50, 0]] }, { id: 'T', points: [[50, 0], [100, 0]] }, { id: 'B', points: [[50, 0], [100, 30]] }];
const dimension = { id: 'minecraft/overworld', revision: 1, rails, signals: [], platforms: [], nodes: [], repeaterLinks: [] };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname.endsWith('/api/session')) { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ canDispatch: editable })); return; }
  if (url.pathname.endsWith('/api/sensors/save')) {
    let body = ''; req.on('data', chunk => body += chunk); req.on('end', () => {
      if (!editable) { res.statusCode = 403; res.end('{"ok":false,"reason":"READ_ONLY"}'); return; }
      Object.assign(sensor, JSON.parse(body).config); res.end('{"ok":true}');
    }); return;
  }
  const fixtures = { topology: { dimensions: [dimension] }, state: { dimensions: [{ id: dimension.id, sections: [], requests: [], players: [], signalAspects: {} }] }, lines: { dimensions: [{ id: dimension.id, depots: [] }] }, sensors: [sensor] };
  const name = url.pathname.split('/').at(-1);
  if (fixtures[name]) { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(fixtures[name])); return; }
  const file = path.join(root, name || 'index.html');
  if (!fs.existsSync(file)) { res.statusCode = 404; res.end(); return; }
  res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  res.end(fs.readFileSync(file));
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/?sensor=${encodeURIComponent(sensor.id)}`);
    await page.locator('#sensor-editor').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#sensor-name').inputValue(), 'Crossing East');
    await page.locator('#sensor-approach-bind').click();
    assert.equal(await page.locator('#sensor-approach-bind').textContent(), 'CONFIRM');
    const select = async (x, z, ctrl = false) => {
      const position = await page.evaluate(({ x, z }) => { const r = canvas.getBoundingClientRect(); return { x: r.left + view.x + x * view.scale, y: r.top + view.y + z * view.scale }; }, { x, z });
      if (ctrl) await page.keyboard.down('Control');
      await page.mouse.click(position.x, position.y);
      if (ctrl) await page.keyboard.up('Control');
    };
    await page.evaluate(() => { view.scale = 3; view.x = 50; view.y = 220; draw(); });
    await select(25, 0); await select(75, 0, true);
    assert.deepEqual(await page.evaluate(() => [...sensorSelection].sort()), ['A', 'T']);
    await page.locator('#sensor-cancel').click();
    assert.deepEqual(await page.evaluate(() => sensorDraft.approach), ['A', 'T']);
    await page.locator('#sensor-targets-bind').click();
    await select(75, 15);
    assert.match(await page.locator('#sensor-status').textContent(), /MUST BELONG/);
    await select(75, 0); await page.locator('#sensor-targets-bind').click();
    await page.locator('#sensor-output').selectOption('pulse');
    await page.locator('#sensor-ticks').fill('8');
    await page.locator('#sensor-save').click();
    await page.waitForFunction(() => document.querySelector('#sensor-status').textContent === 'SAVED');
    assert.equal(sensor.pulseTicks, 8); assert.equal(sensor.pulse, true);
    await page.screenshot({ path: path.join(out, 'desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 900, height: 650 });
    await page.screenshot({ path: path.join(out, 'compact.png'), fullPage: true });
    const overflow = await page.locator('#sensor-editor').evaluate(e => e.scrollWidth > e.clientWidth);
    assert.equal(overflow, false, 'Editor horizontal overflow');
    const pixels = await page.evaluate(() => { const c = document.querySelector('canvas'); const data = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let count = 0; for (let i = 3; i < data.length; i += 4) if (data[i]) count++; return count; });
    assert(pixels > 100, 'Map should render nonblank content');
    editable = false; await page.evaluate(() => refresh());
    await page.waitForFunction(() => document.querySelector('#sensor-save').disabled);
    assert.equal(await page.locator('#sensor-approach-bind').isDisabled(), true);
    assert.deepEqual(errors, []);
    console.log('Web sensor QA passed: deep link, Ctrl selection, cancel, subset, save, read-only, compact layout, canvas.');
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(e => { console.error(e); server.close(); process.exitCode = 1; });
