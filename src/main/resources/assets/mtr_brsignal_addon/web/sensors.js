'use strict';

/* Redstone sensor list, map markers and editor. Loaded after app.js; shares its
   globals (tr, canvas, view, draw, sensors, canDispatch, drawSensors hooks). */

let sensorDraft = null;
let sensorBaseline = null;
let sensorBinding = null;
let sensorSelection = new Set();
let sensorOpenedFromLink = false;
let sensors = [];

function drawSensors() {
  for (const sensor of sensors.filter(s => s.dimension === selectedDimension()?.id)) {
    ctx.save();
    ctx.translate(sensor.x, sensor.z);
    ctx.scale(1 / view.scale, 1 / view.scale);
    const tone = sensor.loaded ? (sensor.powered ? themeColor('occupied') : themeColor('vehicle')) : themeColor('signalStem');
    ctx.fillStyle = tone;
    ctx.globalAlpha = .22;
    ctx.beginPath();
    ctx.arc(0, 0, 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.strokeStyle = tone;
    ctx.lineWidth = 1.6;
    ctx.stroke();
    ctx.fillStyle = sensor.powered ? '#f0b4b4' : '#9fe8c8';
    if (sensor.loaded) {
      ctx.beginPath();
      ctx.arc(0, 0, 2.6, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.globalAlpha = .55;
      ctx.beginPath();
      ctx.arc(0, 0, 2.6, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = 1;
    }
    if (sensorDraft?.id === sensor.id) {
      ctx.font = `500 10.5px "Plex Mono", ui-monospace, monospace`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.fillStyle = themeColor('label');
      ctx.fillText(sensor.name || tr('SENSOR'), 0, -13);
    }
    ctx.restore();
  }
}

function sensorAt(event) {
  const point = canvasPoint(event);
  return sensors.find(s => s.dimension === selectedDimension()?.id && Math.hypot(s.x - point.x, s.z - point.z) * view.scale < 12);
}

function sensorSectionAt(event) {
  const point = canvasPoint(event);
  let best = null, distance = 9 / view.scale;
  for (const rail of selectedDimension()?.rails || []) {
    for (let i = 1; i < rail.points.length; i++) {
      const a = rail.points[i - 1], b = rail.points[i], dx = b[0] - a[0], dz = b[1] - a[1];
      const t = Math.max(0, Math.min(1, ((point.x - a[0]) * dx + (point.z - a[1]) * dz) / (dx * dx + dz * dz || 1)));
      const d = Math.hypot(point.x - a[0] - t * dx, point.z - a[1] - t * dz);
      if (d < distance) { best = rail.id; distance = d; }
    }
  }
  return best;
}

function openSensor(sensor) {
  sensorDraft = JSON.parse(JSON.stringify(sensor));
  sensorBinding = null;
  sensorSelection.clear();
  sensorBaseline = sensorConfig(sensor);
  const dimension = topology?.dimensions?.find(d => d.id === sensor.dimension);
  if (dimension) topology.dimensions = [dimension, ...topology.dimensions.filter(d => d !== dimension)];
  selectedLineId = null;
  nodeChangeMode = false;
  document.body.classList.remove('line-mode');
  setMapTitle('TOPOLOGY');
  document.querySelector('#sensor-editor').hidden = false;
  document.querySelector('#sensor-position').textContent = `${sensor.dimension} / ${sensor.x}, ${sensor.y}, ${sensor.z} / ${sensor.loaded ? tr('LOADED') : tr('UNLOADED')}`;
  document.querySelector('#sensor-name').value = sensor.name;
  document.querySelector('#sensor-mode').value = sensor.enabled ? 'advanced' : 'native';
  document.querySelector('#sensor-output').value = sensor.pulse ? 'pulse' : 'continuous';
  document.querySelector('#sensor-ticks').value = sensor.pulseTicks;
  document.querySelector('#sensor-status').textContent = canDispatch ? '' : tr('READ ONLY');
  document.querySelector('#sensor-status').dataset.tone = canDispatch ? '' : 'warn';
  view.x = canvas.clientWidth / 2 - sensor.x * view.scale;
  view.y = canvas.clientHeight / 2 - sensor.z * view.scale;
  renderSensorList();
  renderSensorEditor();
  draw();
}

function renderSensorEditor() {
  if (!sensorDraft) return;
  if (!canDispatch) sensorMessage('READ ONLY', 'warn');
  document.querySelectorAll('#sensor-editor input, #sensor-editor select, #sensor-editor button:not(#sensor-close)').forEach(element => { element.disabled = !canDispatch; });
  for (const group of ['approach', 'targets']) {
    const box = document.querySelector(`#sensor-${group}-list`);
    box.replaceChildren();
    const values = sensorBinding === group ? [...sensorSelection] : sensorDraft[group];
    if (!values.length) {
      const empty = document.createElement('p');
      empty.className = 'bind-empty';
      empty.textContent = tr('NONE');
      box.append(empty);
    }
    for (const id of values) {
      const row = document.createElement('div');
      row.className = 'bind-row';
      const label = document.createElement('span');
      label.textContent = id;
      label.title = id;
      row.append(label);
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-small btn-ghost';
      button.textContent = tr('UNBIND');
      button.disabled = !canDispatch;
      button.onclick = () => {
        if (sensorBinding === group) sensorSelection.delete(id);
        else {
          if (group === 'approach' && sensorDraft.targets.includes(id)) { sensorMessage('UNBIND TARGET FIRST', 'warn'); return; }
          sensorDraft[group] = sensorDraft[group].filter(s => s !== id);
        }
        renderSensorEditor();
        draw();
      };
      row.append(button);
      box.append(row);
    }
    const bind = document.querySelector(`#sensor-${group}-bind`);
    bind.textContent = sensorBinding === group ? tr('CONFIRM') : tr('BIND');
    bind.disabled = !canDispatch || (sensorBinding && sensorBinding !== group);
    document.querySelector(`#sensor-${group}-clear`).disabled = !canDispatch || !!sensorBinding;
    const count = sensorBinding === group ? sensorSelection.size : sensorDraft[group].length;
    document.querySelector(`#sensor-${group}-count`).textContent = count;
  }
  document.querySelector('#sensor-cancel').hidden = !sensorBinding;
  document.querySelector('#sensor-save').disabled = !canDispatch || !!sensorBinding;
}

function sensorMessage(text, tone = '') {
  const element = document.querySelector('#sensor-status');
  element.textContent = tr(text);
  element.dataset.tone = tone;
}

function bindSensor(group) {
  if (!canDispatch || !sensorDraft) return;
  if (sensorBinding === group) {
    if (group === 'targets' && [...sensorSelection].some(id => !sensorDraft.approach.includes(id))) { sensorMessage('TARGETS MUST BE A SUBSET OF APPROACH', 'warn'); return; }
    if (group === 'approach' && sensorDraft.targets.some(id => !sensorSelection.has(id))) { sensorMessage('UNBIND TARGETS BEFORE REMOVING THEIR APPROACH', 'warn'); return; }
    sensorDraft[group] = [...sensorSelection];
    sensorBinding = null;
    sensorSelection.clear();
    sensorMessage('BINDING CONFIRMED / UNSAVED', 'warn');
  } else {
    sensorBinding = group;
    sensorSelection = new Set(sensorDraft[group]);
    sensorMessage('SELECTING ' + group.toUpperCase(), 'warn');
  }
  renderSensorEditor();
  draw();
}

function selectSensorSection(event) {
  if (!canDispatch) return;
  const id = sensorSectionAt(event);
  if (!id) return;
  if (sensorBinding === 'targets' && !sensorDraft.approach.includes(id)) { sensorMessage('TARGET MUST BELONG TO APPROACH', 'warn'); return; }
  if (!event.ctrlKey) sensorSelection.clear();
  if (sensorSelection.has(id)) sensorSelection.delete(id);
  else sensorSelection.add(id);
  renderSensorEditor();
  draw();
}

async function refreshSensors() {
  const response = await fetch('api/sensors', { cache: 'no-store', ...requestOptions });
  if (!response.ok) return;
  sensors = await response.json();
  renderSensorList();
  renderSensorEditor();
  if (sensorDraft) {
    const dimension = topology?.dimensions?.find(d => d.id === sensorDraft.dimension);
    if (dimension) topology.dimensions = [dimension, ...topology.dimensions.filter(d => d !== dimension)];
  }
  const wanted = new URLSearchParams(location.search).get('sensor');
  if (!sensorOpenedFromLink && wanted) {
    const sensor = sensors.find(s => s.id === wanted);
    if (sensor) { sensorOpenedFromLink = true; openSensor(sensor); view.fitted = true; }
  }
}

function renderSensorList() {
  const box = document.querySelector('#sensor-list');
  const search = document.querySelector('#sensor-search').value.toLowerCase();
  box.replaceChildren();
  const matches = sensors.filter(s => `${s.name} ${s.dimension} ${s.x} ${s.y} ${s.z}`.toLowerCase().includes(search));
  document.querySelector('#sensor-count').textContent = sensors.length;
  if (!matches.length) {
    const empty = document.createElement('p');
    empty.className = 'bind-empty';
    empty.textContent = tr('NONE');
    box.append(empty);
    return;
  }
  for (const sensor of matches) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'item';
    button.setAttribute('role', 'listitem');
    if (sensorDraft?.id === sensor.id) button.setAttribute('aria-current', 'true');
    const name = document.createElement('span');
    name.className = 'item-name';
    name.textContent = sensor.name || tr('SENSOR');
    const meta = document.createElement('span');
    meta.className = 'item-meta';
    meta.textContent = `${sensor.dimension} · ${sensor.x}, ${sensor.y}, ${sensor.z}`;
    const state = document.createElement('span');
    state.className = 'item-meta';
    state.textContent = `${sensor.loaded ? tr('LOADED') : tr('UNLOADED')} · ${sensor.powered ? tr('ON') : tr('OFF')}`;
    state.dataset.tone = sensor.loaded ? (sensor.powered ? 'on' : '') : 'dim';
    button.append(name, meta, state);
    button.title = `${sensor.dimension} / ${sensor.loaded ? tr('LOADED') : tr('UNLOADED')} / ${sensor.powered ? tr('ON') : tr('OFF')}`;
    button.onclick = () => openSensor(sensor);
    button.oncontextmenu = event => { event.preventDefault(); openSensor(sensor); };
    box.append(button);
  }
}

function sensorConfig(sensor) {
  return { name: sensor.name, enabled: sensor.enabled, pulse: sensor.pulse, pulseTicks: sensor.pulseTicks, approach: [...sensor.approach], targets: [...sensor.targets] };
}

canvas.addEventListener('contextmenu', event => {
  const sensor = sensorAt(event);
  if (sensor) { event.preventDefault(); openSensor(sensor); }
});
document.querySelector('#sensor-close').addEventListener('click', () => {
  sensorDraft = null;
  sensorBinding = null;
  sensorSelection.clear();
  document.querySelector('#sensor-editor').hidden = true;
  renderSensorList();
  draw();
});
document.querySelector('#sensor-search').addEventListener('input', renderSensorList);
for (const group of ['approach', 'targets']) {
  document.querySelector(`#sensor-${group}-bind`).addEventListener('click', () => bindSensor(group));
  document.querySelector(`#sensor-${group}-clear`).addEventListener('click', () => {
    if (!sensorDraft) return;
    if (group === 'approach' && sensorDraft.targets.length) { sensorMessage('CLEAR TARGETS FIRST', 'warn'); return; }
    sensorDraft[group] = [];
    renderSensorEditor();
    draw();
    sensorMessage('UNSAVED', 'warn');
  });
}
document.querySelector('#sensor-cancel').addEventListener('click', () => {
  sensorBinding = null;
  sensorSelection.clear();
  renderSensorEditor();
  draw();
});
document.querySelector('#sensor-save').addEventListener('click', async () => {
  if (!canDispatch || !sensorDraft || sensorBinding) return;
  const config = {
    name: document.querySelector('#sensor-name').value,
    enabled: document.querySelector('#sensor-mode').value === 'advanced',
    pulse: document.querySelector('#sensor-output').value === 'pulse',
    pulseTicks: Number(document.querySelector('#sensor-ticks').value),
    approach: sensorDraft.approach,
    targets: sensorDraft.targets
  };
  if (!Number.isInteger(config.pulseTicks) || config.pulseTicks < 1 || config.pulseTicks > 1200) { sensorMessage('PULSE LENGTH MUST BE 1-1200 TICKS', 'warn'); return; }
  try {
    const response = await fetch('api/sensors/save', {
      method: 'POST',
      headers: { ...requestOptions.headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: sensorDraft.id, config, expected: sensorBaseline })
    });
    const result = await response.json();
    sensorMessage(result.ok ? 'SAVED' : result.reason, result.ok ? 'ok' : 'error');
    if (result.ok) {
      sensorBaseline = sensorConfig(config);
      Object.assign(sensorDraft, config);
      await refreshSensors();
    }
  } catch (_) {
    sensorMessage('SAVE FAILED', 'error');
  }
});

renderSensorList();
refreshSensors();
