function drawSensors() {
  for (const sensor of sensors.filter(s => s.dimension === selectedDimension()?.id)) {
    ctx.save(); ctx.translate(sensor.x, sensor.z); ctx.scale(1 / view.scale, 1 / view.scale);
    ctx.fillStyle = sensor.loaded ? (sensor.powered ? '#ec4949' : '#39c5a3') : '#899196';
    ctx.fillRect(-5, -5, 10, 10); ctx.strokeStyle = '#ffffff'; ctx.strokeRect(-5, -5, 10, 10);
    if (sensorDraft?.id === sensor.id) { ctx.font = '12px sans-serif'; ctx.fillStyle = '#fff'; ctx.fillText(sensor.name || 'SENSOR', 9, -8); }
    ctx.restore();
  }
}
function sensorAt(event) {
  const point = canvasPoint(event);
  return sensors.find(s => s.dimension === selectedDimension()?.id && Math.hypot(s.x - point.x, s.z - point.z) * view.scale < 10);
}
function sensorSectionAt(event) {
  const point = canvasPoint(event); let best = null, distance = 9 / view.scale;
  for (const rail of selectedDimension()?.rails || []) for (let i = 1; i < rail.points.length; i++) {
    const a = rail.points[i - 1], b = rail.points[i], dx = b[0] - a[0], dz = b[1] - a[1];
    const t = Math.max(0, Math.min(1, ((point.x - a[0]) * dx + (point.z - a[1]) * dz) / (dx * dx + dz * dz || 1)));
    const d = Math.hypot(point.x - a[0] - t * dx, point.z - a[1] - t * dz);
    if (d < distance) { best = rail.id; distance = d; }
  }
  return best;
}
function openSensor(sensor) {
  sensorDraft = JSON.parse(JSON.stringify(sensor)); sensorBinding = null; sensorSelection.clear();
  sensorBaseline = sensorConfig(sensor);
  const dimension = topology?.dimensions?.find(d => d.id === sensor.dimension);
  if (dimension) topology.dimensions = [dimension, ...topology.dimensions.filter(d => d !== dimension)];
  selectedLineId = null; nodeChangeMode = false;
  document.querySelector('#sensor-editor').hidden = false;
  document.querySelector('#sensor-position').textContent = `${sensor.dimension} / ${sensor.x}, ${sensor.y}, ${sensor.z} / ${sensor.loaded ? 'LOADED' : 'UNLOADED'}`;
  document.querySelector('#sensor-name').value = sensor.name;
  document.querySelector('#sensor-mode').value = sensor.enabled ? 'advanced' : 'native';
  document.querySelector('#sensor-output').value = sensor.pulse ? 'pulse' : 'continuous';
  document.querySelector('#sensor-ticks').value = sensor.pulseTicks;
  document.querySelector('#sensor-status').textContent = canDispatch ? '' : 'READ ONLY';
  view.x = canvas.clientWidth / 2 - sensor.x * view.scale; view.y = canvas.clientHeight / 2 - sensor.z * view.scale;
  renderSensorEditor(); draw();
}
function renderSensorEditor() {
  if (!sensorDraft) return;
  if (!canDispatch) sensorMessage('READ ONLY');
  document.querySelectorAll('#sensor-editor input, #sensor-editor select, #sensor-editor button:not(#sensor-close)').forEach(e => e.disabled = !canDispatch);
  for (const group of ['approach', 'targets']) {
    const box = document.querySelector(`#sensor-${group}-list`); box.replaceChildren();
    const values = sensorBinding === group ? [...sensorSelection] : sensorDraft[group];
    for (const id of values) {
      const row = document.createElement('div'); row.className = 'sensor-section-row';
      const label = document.createElement('span'); label.textContent = id; row.append(label);
      const button = document.createElement('button'); button.textContent = 'UNBIND'; button.disabled = !canDispatch;
      button.onclick = () => {
        if (sensorBinding === group) sensorSelection.delete(id);
        else {
          if (group === 'approach' && sensorDraft.targets.includes(id)) { sensorMessage('UNBIND TARGET FIRST'); return; }
          sensorDraft[group] = sensorDraft[group].filter(s => s !== id);
        }
        renderSensorEditor(); draw();
      }; row.append(button); box.append(row);
    }
    document.querySelector(`#sensor-${group}-bind`).textContent = sensorBinding === group ? 'CONFIRM' : 'BIND';
    document.querySelector(`#sensor-${group}-bind`).disabled = !canDispatch || (sensorBinding && sensorBinding !== group);
    document.querySelector(`#sensor-${group}-clear`).disabled = !canDispatch || !!sensorBinding;
  }
  document.querySelector('#sensor-cancel').hidden = !sensorBinding;
  document.querySelector('#sensor-save').disabled = !canDispatch || !!sensorBinding;
}
function sensorMessage(text) { document.querySelector('#sensor-status').textContent = text; }
function bindSensor(group) {
  if (!canDispatch || !sensorDraft) return;
  if (sensorBinding === group) {
    if (group === 'targets' && [...sensorSelection].some(id => !sensorDraft.approach.includes(id))) { sensorMessage('TARGETS MUST BE A SUBSET OF APPROACH'); return; }
    if (group === 'approach' && sensorDraft.targets.some(id => !sensorSelection.has(id))) { sensorMessage('UNBIND TARGETS BEFORE REMOVING THEIR APPROACH'); return; }
    sensorDraft[group] = [...sensorSelection]; sensorBinding = null; sensorSelection.clear();
    sensorMessage('BINDING CONFIRMED / UNSAVED');
  } else { sensorBinding = group; sensorSelection = new Set(sensorDraft[group]); sensorMessage('SELECTING ' + group.toUpperCase()); }
  renderSensorEditor(); draw();
}
function selectSensorSection(event) {
  if (!canDispatch) return;
  const id = sensorSectionAt(event); if (!id) return;
  if (sensorBinding === 'targets' && !sensorDraft.approach.includes(id)) { sensorMessage('TARGET MUST BELONG TO APPROACH'); return; }
  if (!event.ctrlKey) sensorSelection.clear();
  if (sensorSelection.has(id)) sensorSelection.delete(id); else sensorSelection.add(id);
  renderSensorEditor(); draw();
}
async function refreshSensors() {
  const response = await fetch('api/sensors', { cache: 'no-store', ...requestOptions });
  if (!response.ok) return; sensors = await response.json(); renderSensorList(); renderSensorEditor();
  if (sensorDraft) {
    const dimension = topology?.dimensions?.find(d => d.id === sensorDraft.dimension);
    if (dimension) topology.dimensions = [dimension, ...topology.dimensions.filter(d => d !== dimension)];
  }
  const wanted = new URLSearchParams(location.search).get('sensor');
  if (!sensorOpenedFromLink && wanted) {
    const sensor = sensors.find(s => s.id === wanted); if (sensor) { sensorOpenedFromLink = true; openSensor(sensor); view.fitted = true; }
  }
}
function renderSensorList() {
  const box = document.querySelector('#sensor-list'), search = document.querySelector('#sensor-search').value.toLowerCase(); box.replaceChildren();
  for (const sensor of sensors.filter(s => `${s.name} ${s.dimension} ${s.x} ${s.y} ${s.z}`.toLowerCase().includes(search))) {
    const button = document.createElement('button'); button.textContent = `${sensor.name || 'SENSOR'} / ${sensor.x}, ${sensor.y}, ${sensor.z}`;
    button.title = `${sensor.dimension} / ${sensor.loaded ? 'LOADED' : 'UNLOADED'} / ${sensor.powered ? 'ON' : 'OFF'}`;
    button.onclick = () => openSensor(sensor); button.oncontextmenu = e => { e.preventDefault(); openSensor(sensor); }; box.append(button);
  }
}
canvas.addEventListener('contextmenu', e => { const sensor = sensorAt(e); if (sensor) { e.preventDefault(); openSensor(sensor); } });
document.querySelector('#sensor-close').onclick = () => { sensorDraft = null; sensorBinding = null; sensorSelection.clear(); document.querySelector('#sensor-editor').hidden = true; draw(); };
document.querySelector('#sensor-search').oninput = renderSensorList;
for (const group of ['approach', 'targets']) {
  document.querySelector(`#sensor-${group}-bind`).onclick = () => bindSensor(group);
  document.querySelector(`#sensor-${group}-clear`).onclick = () => {
    if (group === 'approach' && sensorDraft.targets.length) { sensorMessage('CLEAR TARGETS FIRST'); return; }
    sensorDraft[group] = []; renderSensorEditor(); draw(); sensorMessage('UNSAVED');
  };
}
document.querySelector('#sensor-cancel').onclick = () => { sensorBinding = null; sensorSelection.clear(); renderSensorEditor(); draw(); };
function sensorConfig(sensor) {
  return { name: sensor.name, enabled: sensor.enabled, pulse: sensor.pulse, pulseTicks: sensor.pulseTicks, approach: [...sensor.approach], targets: [...sensor.targets] };
}
document.querySelector('#sensor-save').onclick = async () => {
  if (!canDispatch || !sensorDraft || sensorBinding) return;
  const config = { name: document.querySelector('#sensor-name').value, enabled: document.querySelector('#sensor-mode').value === 'advanced', pulse: document.querySelector('#sensor-output').value === 'pulse', pulseTicks: Number(document.querySelector('#sensor-ticks').value), approach: sensorDraft.approach, targets: sensorDraft.targets };
  if (!Number.isInteger(config.pulseTicks) || config.pulseTicks < 1 || config.pulseTicks > 1200) { sensorMessage('PULSE LENGTH MUST BE 1-1200 TICKS'); return; }
  try {
    const response = await fetch('api/sensors/save', { method: 'POST', headers: { ...requestOptions.headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ id: sensorDraft.id, config, expected: sensorBaseline }) });
    const result = await response.json(); sensorMessage(result.ok ? 'SAVED' : result.reason);
    if (result.ok) { sensorBaseline = sensorConfig(config); Object.assign(sensorDraft, config); await refreshSensors(); }
  } catch (_) { sensorMessage('SAVE FAILED'); }
};
