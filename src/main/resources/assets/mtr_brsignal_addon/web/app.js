const canvas = document.querySelector('#diagram');
const ctx = canvas.getContext('2d');
const view = { x: 0, y: 0, scale: 1, dragging: false, lastX: 0, lastY: 0, fitted: false };
let topology = null;
let state = null;
const token = new URLSearchParams(window.location.search).get('token') || '';
const requestOptions = token ? { headers: { 'X-MTRBR-Token': token } } : {};
let canDispatch = false;
let selectedVehicleId = null;
let hoveredVehicleId = null;
let vehicleMarkers = [];

function currentRequests() { return selectedState()?.requests || []; }
function highlightedRequest() { return currentRequests().find(request => request.vehicleId === (hoveredVehicleId ?? selectedVehicleId)); }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); }
function requestStateClass(stateName) {
  const state = String(stateName || '').toUpperCase();
  if (['ACTIVE', 'AUTHORIZED', 'APPROACHING', 'REQUESTED', 'CHECKING', 'PASSED'].includes(state)) return 'state-green';
  if (state === 'WAITING') return 'state-yellow';
  if (['DENIED', 'INVALID', 'REVOKED', 'CANCELED', 'RELEASED'].includes(state)) return 'state-red';
  if (['OVERRIDE', 'NONE'].includes(state)) return 'state-blue';
  return 'state-white';
}

function resize() {
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.floor(rect.width * devicePixelRatio);
  canvas.height = Math.floor(rect.height * devicePixelRatio);
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
  draw();
}

function selectedDimension() { return topology && topology.dimensions && topology.dimensions[0]; }
function selectedState() {
  const data = selectedDimension();
  return data && state && state.dimensions && state.dimensions.find(item => item.id === data.id);
}

function fit() {
  const data = selectedDimension();
  if (!data || !data.rails.length) return;
  const points = data.rails.flatMap(rail => rail.points);
  const xs = points.map(point => point[0]);
  const zs = points.map(point => point[1]);
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minZ = Math.min(...zs), maxZ = Math.max(...zs);
  view.scale = Math.max(.05, Math.min(w / Math.max(1, maxX - minX) * .82, h / Math.max(1, maxZ - minZ) * .78));
  view.x = w / 2 - (minX + maxX) / 2 * view.scale;
  view.y = h / 2 - (minZ + maxZ) / 2 * view.scale;
  view.fitted = true;
  draw();
}

function sectionColor(section) {
  if (section && section.occupied) return '#c82424';
  if (section && section.locked) return '#f4f7f8';
  if (section && section.reserved) return '#f0b42e';
  return '#969da0';
}

function signalColor(aspect) {
  return { GREEN: '#15ed6b', RED: '#c82424', YELLOW: '#d88900', DOUBLE_YELLOW: '#ffd452' }[aspect] || '#5a6a70';
}

function railMidpoint(points) {
  let distance = 0;
  const segments = [];
  for (let i = 1; i < points.length; i++) {
    const dx = points[i][0] - points[i - 1][0];
    const dz = points[i][1] - points[i - 1][1];
    const length = Math.hypot(dx, dz);
    segments.push(length);
    distance += length;
  }
  let remaining = distance / 2;
  for (let i = 1; i < points.length; i++) {
    if (remaining <= segments[i - 1]) {
      const ratio = segments[i - 1] ? remaining / segments[i - 1] : 0;
      const dx = points[i][0] - points[i - 1][0];
      const dz = points[i][1] - points[i - 1][1];
      return {
        x: points[i - 1][0] + dx * ratio,
        z: points[i - 1][1] + dz * ratio,
        dx,
        dz
      };
    }
    remaining -= segments[i - 1];
  }
  const last = points.length - 1;
  return { x: points[last][0], z: points[last][1], dx: 1, dz: 0 };
}

function drawRail(points, trimDistance = .4312) {
  const visiblePoints = trimRail(points, trimDistance);
  if (visiblePoints.length < 2) return;
  ctx.beginPath();
  visiblePoints.forEach((point, index) => index ? ctx.lineTo(point[0], point[1]) : ctx.moveTo(point[0], point[1]));
  ctx.stroke();
}

function drawPlatform(platform) {
  ctx.strokeStyle = '#ff9900';
  ctx.lineWidth = Math.max(1.92, 1.36 / view.scale);
  drawRail(offsetRail(platform.points, platform.side || 1, 2.6), 0);
}

function offsetRail(points, side, distance) {
  return points.map((point, index) => {
    const previous = points[Math.max(0, index - 1)];
    const next = points[Math.min(points.length - 1, index + 1)];
    const dx = next[0] - previous[0], dz = next[1] - previous[1];
    const length = Math.hypot(dx, dz) || 1;
    return [point[0] - dz / length * side * distance, point[1] + dx / length * side * distance];
  });
}

function drawStationLabel(platform, seenNames) {
  if (!platform.name) return;
  if (seenNames.has(platform.name)) return;
  seenNames.add(platform.name);
  const marker = railMidpoint(platform.points);
  const horizontal = Math.abs(marker.dx) >= Math.abs(marker.dz);
  const normalX = horizontal ? 0 : 1;
  const normalZ = horizontal ? 1 : 0;
  const normalLength = Math.hypot(normalX, normalZ) || 1;
  ctx.save();
  ctx.translate(marker.x + normalX / normalLength * 4, marker.z + normalZ / normalLength * 4);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = '700 18px "Terminus", "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#f2f4f4';
  ctx.fillText(platform.name, 0, 0);
  ctx.restore();
}

function trimRail(points, trimDistance) {
  const result = points.map(point => [point[0], point[1]]);
  let remaining = trimDistance;
  while (result.length > 1 && remaining > 0) {
    const dx = result[1][0] - result[0][0], dz = result[1][1] - result[0][1];
    const length = Math.hypot(dx, dz);
    if (length <= remaining) { result.shift(); remaining -= length; } else { result[0] = [result[0][0] + dx * remaining / length, result[0][1] + dz * remaining / length]; remaining = 0; }
  }
  remaining = trimDistance;
  while (result.length > 1 && remaining > 0) {
    const last = result.length - 1, dx = result[last - 1][0] - result[last][0], dz = result[last - 1][1] - result[last][1];
    const length = Math.hypot(dx, dz);
    if (length <= remaining) { result.pop(); remaining -= length; } else { result[last] = [result[last][0] + dx * remaining / length, result[last][1] + dz * remaining / length]; remaining = 0; }
  }
  return result;
}

function drawVehicle(code, marker, index) {
  ctx.save();
  ctx.translate(marker.x, marker.z);
  if (Math.abs(marker.dz) > Math.abs(marker.dx)) ctx.rotate(Math.PI / 2);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = '700 14.4px "Terminus", "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#39d8ff';
  ctx.shadowColor = '#001820';
  ctx.shadowBlur = 3;
  const width = ctx.measureText(code).width + 4;
  const y = index * -18;
  ctx.fillStyle = '#030506';
  ctx.shadowBlur = 0;
  ctx.fillRect(-width / 2, y - 9, width, 18);
  ctx.fillStyle = '#39d8ff';
  ctx.shadowBlur = 3;
  ctx.fillText(code, 0, y);
  ctx.restore();
}

function draw() {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  const data = selectedDimension();
  if (!data) return;
  const sections = new Map((selectedState()?.sections || []).map(section => [section.id, section]));
  ctx.save();
  ctx.translate(view.x, view.y);
  ctx.scale(view.scale, view.scale);
  ctx.lineCap = 'butt';
  ctx.lineJoin = 'round';
  vehicleMarkers = [];
  const highlighted = highlightedRequest();
  const highlightedSections = new Set(highlighted?.sections || []);
  const blinkYellow = Math.floor(Date.now() / 420) % 2 === 0;

  for (const platform of (data.platforms || [])) drawPlatform(platform);

  for (const rail of data.rails) {
    const section = sections.get(rail.id);
    ctx.strokeStyle = highlightedSections.has(rail.id) ? (blinkYellow ? '#f0b42e' : '#969da0') : sectionColor(section);
    ctx.lineWidth = Math.max(.72, .544 / view.scale);
    drawRail(rail.points);
    if (section && section.vehicles && section.vehicles.length) {
      const midpoint = railMidpoint(rail.points);
      section.vehicles.forEach((code, index) => {
        const vehicleId = section.vehicleIds?.[index] ?? currentRequests().find(entry => entry.code === code)?.vehicleId ?? null;
        vehicleMarkers.push({ code, vehicleId, marker: midpoint, index });
      });
    }
  }

  const stationNames = new Set();
  for (const platform of (data.platforms || [])) drawStationLabel(platform, stationNames);

  for (const signal of data.signals) {
    ctx.save();
    ctx.translate(signal.x, signal.z);
    ctx.rotate(signal.angle * Math.PI / 180);
    ctx.strokeStyle = '#b7bec1';
    ctx.lineWidth = .44;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(1.92, 0);
    ctx.stroke();
    ctx.fillStyle = signalColor(signal.aspect);
    ctx.beginPath();
    ctx.arc(0, 0, .704, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  // Vehicle short codes must remain readable above stations, rails, and signals.
  vehicleMarkers.forEach(({ code, marker, index }) => drawVehicle(code, marker, index));

  ctx.restore();
  document.querySelector('#scale-label').textContent = `${Math.round(100 / view.scale)} m`;
}

function renderPanels() {
  const players = state?.players || [];
  document.querySelector('#player-list').innerHTML = players.map(player => `<div class="player"><img src="${encodeURI(player.avatar)}" alt=""><span class="player-name">${escapeHtml(player.name)}</span><b class="player-state ${player.dispatching ? 'on' : 'off'}">${player.dispatching ? 'ON' : 'OFF'}</b></div>`).join('');
  const requests = [...currentRequests()].sort((left, right) => String(left.code).localeCompare(String(right.code), undefined, { numeric: true, sensitivity: 'base' }));
  document.querySelector('#request-list').innerHTML = requests.map(request => `<div class="request ${request.vehicleId === selectedVehicleId ? 'selected' : ''}" data-vehicle-id="${request.vehicleId}"><span class="request-code">${escapeHtml(request.code)}</span><span class="request-state ${requestStateClass(request.state)}">${escapeHtml(request.state)}</span><span class="request-detail">R: ${escapeHtml(request.route || '--')} | N: ${escapeHtml(request.next || '--')} | D: ${escapeHtml(request.destination || '--')}</span></div>`).join('');
  document.querySelectorAll('.request').forEach(row => row.addEventListener('click', () => selectVehicle(Number(row.dataset.vehicleId))));
  const selected = requests.find(request => request.vehicleId === selectedVehicleId);
  const actions = document.querySelector('#action-drawer');
  actions.hidden = !(canDispatch && selected);
  document.querySelector('#action-code').textContent = selected?.code || '--';
  document.querySelector('.mode').textContent = canDispatch ? 'DISPATCH ENABLED' : 'READ ONLY / LIVE';
}

function selectVehicle(vehicleId) {
  const deselected = selectedVehicleId === vehicleId;
  selectedVehicleId = deselected ? null : vehicleId;
  if (!deselected) {
    document.querySelector('#vehicle-drawer').classList.add('open');
    document.querySelector('#vehicle-drawer').setAttribute('aria-hidden', 'false');
	  focusVehicle(vehicleId);
  }
  renderPanels();
  draw();
}

function focusVehicle(vehicleId) {
  const vehicle = vehicleMarkers.find(marker => marker.vehicleId === vehicleId);
  if (!vehicle) return;
  view.x = canvas.clientWidth / 2 - vehicle.marker.x * view.scale;
  view.y = canvas.clientHeight / 2 - vehicle.marker.z * view.scale;
  view.fitted = true;
}

function vehicleAt(event) {
  const x = (event.offsetX - view.x) / view.scale;
  const z = (event.offsetY - view.y) / view.scale;
  const tolerance = 16 / view.scale;
  return vehicleMarkers.find(vehicle => Math.abs(vehicle.marker.x - x) < tolerance && Math.abs(vehicle.marker.z - z) < tolerance);
}

async function dispatch(action) {
  if (!canDispatch || selectedVehicleId === null) return;
  await fetch('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json', ...requestOptions.headers }, body: JSON.stringify({ action, vehicleId: selectedVehicleId }) });
  refresh();
}

async function refresh() {
  try {
    const [topologyResponse, stateResponse, sessionResponse] = await Promise.all([
      fetch('api/topology', { cache: 'no-store', ...requestOptions }),
      fetch('api/state', { cache: 'no-store', ...requestOptions }),
      fetch('api/session', { cache: 'no-store', ...requestOptions })
    ]);
    if (!topologyResponse.ok || !stateResponse.ok) throw new Error('snapshot unavailable');
    topology = await topologyResponse.json();
    state = await stateResponse.json();
    canDispatch = sessionResponse.ok && (await sessionResponse.json()).canDispatch;
    const data = selectedDimension();
    document.querySelector('#status').textContent = 'SNAPSHOT: LIVE';
    document.querySelector('#updated').textContent = new Date().toLocaleTimeString();
    if (!data) return;
    document.querySelector('#dimension').textContent = data.id.toUpperCase();
    document.querySelector('#map-dimension').textContent = data.id;
    document.querySelector('#revision').textContent = `REV ${data.revision}`;
    document.querySelector('#rail-count').textContent = data.rails.length;
    document.querySelector('#signal-count').textContent = data.signals.length;
    renderPanels();
    if (!view.fitted) fit(); else draw();
  } catch (_) {
    document.querySelector('#status').textContent = 'WAITING FOR SERVER';
  }
}

canvas.addEventListener('pointerdown', event => {
  view.dragging = true;
  view.lastX = event.clientX;
  view.lastY = event.clientY;
  canvas.setPointerCapture(event.pointerId);
  canvas.classList.add('dragging');
});
canvas.addEventListener('pointermove', event => {
  if (!view.dragging) return;
  view.x += event.clientX - view.lastX;
  view.y += event.clientY - view.lastY;
  view.lastX = event.clientX;
  view.lastY = event.clientY;
  view.fitted = true;
  draw();
});
canvas.addEventListener('mousemove', event => {
  const vehicle = vehicleAt(event);
  const vehicleId = vehicle?.vehicleId ?? null;
  if (vehicleId !== hoveredVehicleId) {
    hoveredVehicleId = vehicleId;
    draw();
  }
});
canvas.addEventListener('mouseleave', () => { if (hoveredVehicleId !== null) { hoveredVehicleId = null; draw(); } });
canvas.addEventListener('click', event => {
  const vehicle = vehicleAt(event);
  if (vehicle?.vehicleId !== null && vehicle?.vehicleId !== undefined) selectVehicle(vehicle.vehicleId);
});
canvas.addEventListener('pointerup', event => {
  view.dragging = false;
  canvas.releasePointerCapture(event.pointerId);
  canvas.classList.remove('dragging');
});
canvas.addEventListener('wheel', event => {
  event.preventDefault();
  const before = { x: (event.offsetX - view.x) / view.scale, y: (event.offsetY - view.y) / view.scale };
  view.scale = Math.max(.02, Math.min(8, view.scale * (event.deltaY < 0 ? 1.12 : .89)));
  view.x = event.offsetX - before.x * view.scale;
  view.y = event.offsetY - before.y * view.scale;
  view.fitted = true;
  draw();
}, { passive: false });
document.querySelector('#fit').addEventListener('click', fit);
document.querySelector('#reset').addEventListener('click', () => { view.fitted = false; fit(); });
document.querySelector('#vehicle-toggle').addEventListener('click', () => {
  const drawer = document.querySelector('#vehicle-drawer');
  drawer.classList.toggle('open');
  drawer.setAttribute('aria-hidden', String(!drawer.classList.contains('open')));
});
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => dispatch(button.dataset.action)));
window.addEventListener('resize', resize);
resize();
refresh();
setInterval(refresh, 1000);
setInterval(draw, 420);
