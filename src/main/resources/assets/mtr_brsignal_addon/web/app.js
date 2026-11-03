
const LANGUAGE_LABELS = { en: { en: 'English', 'zh-Hans': 'Simplified Chinese', 'zh-Hant': 'Traditional Chinese', ja: 'Japanese' }, 'zh-Hans': { en: '英文', 'zh-Hans': '简体中文', 'zh-Hant': '繁体中文', ja: '日文' }, 'zh-Hant': { en: '英文', 'zh-Hans': '簡體中文', 'zh-Hant': '繁體中文', ja: '日文' }, ja: { en: '英語', 'zh-Hans': '簡体字中国語', 'zh-Hant': '繁体字中国語', ja: '日本語' } };
const I18N = {
  en: { 'SIGNAL DESK':'SIGNAL DESK','SECTIONS':'SECTIONS','SIGNAL FACES':'SIGNAL FACES','DISPLAY':'DISPLAY','FIT NETWORK':'FIT NETWORK','RESET VIEW':'RESET VIEW','SENSORS':'SENSORS','Search sensors':'Search sensors','PLAYERS':'PLAYERS','STATUS: LIVE':'STATUS: LIVE','REQUESTS':'REQUESTS','REQUEST QUEUE':'REQUEST QUEUE','LINE':'LINE','REFRESH':'REFRESH','REDSTONE SENSOR':'REDSTONE SENSOR','CLOSE':'CLOSE','Name':'Name','Detection':'Detection','NATIVE DETECTION':'NATIVE DETECTION','BR AUTHORITY':'BR AUTHORITY','Output':'Output','CONTINUOUS':'CONTINUOUS','PULSE':'PULSE','Pulse length (ticks)':'Pulse length (ticks)','APPROACH SECTIONS':'APPROACH SECTIONS','TARGETED SECTIONS':'TARGETED SECTIONS','BIND':'BIND','CLEAR ALL':'CLEAR ALL','CANCEL':'CANCEL','SAVE':'SAVE','TOPOLOGY // ':'TOPOLOGY // ','PATH EDIT':'PATH EDIT','CHANGE':'CHANGE','ADD':'ADD','CONFIRM':'CONFIRM','ABANDON':'ABANDON','DELETE':'DELETE','INDICATIONS':'INDICATIONS','TRACK':'TRACK','SIGNAL':'SIGNAL','PLATFORM':'PLATFORM','OCCUPIED':'OCCUPIED','LOCKED':'LOCKED','CONNECTING':'CONNECTING','APPROVE':'APPROVE','REVOKE':'REVOKE','OVERRIDE':'OVERRIDE','TOPOLOGY SNAPSHOT':'TOPOLOGY SNAPSHOT','READ ONLY / LIVE':'READ ONLY / LIVE','UNNAMED DEPOT':'UNNAMED DEPOT','SEG':'SEG','UNBIND':'UNBIND','LOADED':'LOADED','UNLOADED':'UNLOADED','ON':'ON','OFF':'OFF','READ ONLY':'READ ONLY','SENSOR':'SENSOR','NONE':'NONE','APPROACHING':'APPROACHING','REQUESTED':'REQUESTED','CHECKING':'CHECKING','WAITING':'WAITING','AUTHORIZED':'AUTHORIZED','ACTIVE':'ACTIVE','PASSED':'PASSED','DENIED':'DENIED','REVOKED':'REVOKED','CANCELED':'CANCELED','INVALID':'INVALID','RELEASED':'RELEASED' },
  'zh-Hans': { 'SIGNAL DESK':'信号台','SECTIONS':'区段','SIGNAL FACES':'信号面','DISPLAY':'显示','FIT NETWORK':'适应线路','RESET VIEW':'重置视图','SENSORS':'传感器','Search sensors':'搜索传感器','PLAYERS':'玩家','STATUS: LIVE':'状态：实时','REQUESTS':'请求','REQUEST QUEUE':'请求队列','LINE':'线路','REFRESH':'刷新','REDSTONE SENSOR':'红石传感器','CLOSE':'关闭','Name':'名称','Detection':'检测方式','NATIVE DETECTION':'原生检测','BR AUTHORITY':'BR授权','Output':'输出','CONTINUOUS':'持续','PULSE':'脉冲','Pulse length (ticks)':'脉冲长度（tick）','APPROACH SECTIONS':'接近区段','TARGETED SECTIONS':'目标区段','BIND':'绑定','CLEAR ALL':'全部清除','CANCEL':'取消','SAVE':'保存','TOPOLOGY // ':'拓扑 // ','PATH EDIT':'路径编辑','CHANGE':'更换','ADD':'添加','CONFIRM':'确认','ABANDON':'放弃','DELETE':'删除','INDICATIONS':'图例','TRACK':'轨道','SIGNAL':'信号','PLATFORM':'站台','OCCUPIED':'占用','LOCKED':'锁闭','CONNECTING':'连接中','APPROVE':'批准','REVOKE':'撤销','OVERRIDE':'越红灯','TOPOLOGY SNAPSHOT':'拓扑快照','READ ONLY / LIVE':'只读 / 实时','UNNAMED DEPOT':'未命名车库','SEG':'段','UNBIND':'解绑','LOADED':'已加载','UNLOADED':'未加载','ON':'开启','OFF':'关闭','READ ONLY':'只读','R':'路线','N':'下一站','D':'终点','LOADING':'加载中','SENSOR':'传感器','NONE':'无','APPROACHING':'接近中','REQUESTED':'已请求','CHECKING':'检查中','WAITING':'等待中','AUTHORIZED':'已授权','ACTIVE':'运行中','PASSED':'已通过','DENIED':'已拒绝','REVOKED':'已撤销','CANCELED':'已取消','INVALID':'已失效','RELEASED':'已释放' },
  'zh-Hant': { 'SIGNAL DESK':'信號台','SECTIONS':'區段','SIGNAL FACES':'信號面','DISPLAY':'顯示','FIT NETWORK':'適應線路','RESET VIEW':'重設視圖','SENSORS':'感應器','Search sensors':'搜尋感應器','PLAYERS':'玩家','STATUS: LIVE':'狀態：即時','REQUESTS':'請求','REQUEST QUEUE':'請求佇列','LINE':'路線','REFRESH':'重新整理','REDSTONE SENSOR':'紅石感應器','CLOSE':'關閉','Name':'名稱','Detection':'偵測方式','NATIVE DETECTION':'原生偵測','BR AUTHORITY':'BR 授權','Output':'輸出','CONTINUOUS':'持續','PULSE':'脈衝','Pulse length (ticks)':'脈衝長度（tick）','APPROACH SECTIONS':'接近區段','TARGETED SECTIONS':'目標區段','BIND':'綁定','CLEAR ALL':'全部清除','CANCEL':'取消','SAVE':'儲存','TOPOLOGY // ':'拓撲 // ','PATH EDIT':'路徑編輯','CHANGE':'更換','ADD':'新增','CONFIRM':'確認','ABANDON':'放棄','DELETE':'刪除','INDICATIONS':'圖例','TRACK':'軌道','SIGNAL':'信號','PLATFORM':'月台','OCCUPIED':'佔用','LOCKED':'鎖閉','CONNECTING':'連線中','APPROVE':'批准','REVOKE':'撤銷','OVERRIDE':'越紅燈','TOPOLOGY SNAPSHOT':'拓撲快照','READ ONLY / LIVE':'唯讀 / 即時','UNNAMED DEPOT':'未命名車庫','SEG':'段','UNBIND':'解除綁定','LOADED':'已載入','UNLOADED':'未載入','ON':'開啟','OFF':'關閉','READ ONLY':'唯讀','R':'路線','N':'下一站','D':'終點','LOADING':'載入中','SENSOR':'感應器','NONE':'無','APPROACHING':'接近中','REQUESTED':'已請求','CHECKING':'檢查中','WAITING':'等待中','AUTHORIZED':'已授權','ACTIVE':'運行中','PASSED':'已通過','DENIED':'已拒絕','REVOKED':'已撤銷','CANCELED':'已取消','INVALID':'已失效','RELEASED':'已釋放' },
  ja: { 'SIGNAL DESK':'信号卓','SECTIONS':'区間','SIGNAL FACES':'信号面','DISPLAY':'表示','FIT NETWORK':'ネットワークに合わせる','RESET VIEW':'表示をリセット','SENSORS':'センサー','Search sensors':'センサーを検索','PLAYERS':'プレイヤー','STATUS: LIVE':'状態：ライブ','REQUESTS':'リクエスト','REQUEST QUEUE':'リクエストキュー','LINE':'路線','REFRESH':'更新','REDSTONE SENSOR':'レッドストーンセンサー','CLOSE':'閉じる','Name':'名前','Detection':'検知方式','NATIVE DETECTION':'標準検知','BR AUTHORITY':'BR権限','Output':'出力','CONTINUOUS':'連続','PULSE':'パルス','Pulse length (ticks)':'パルス長（tick）','APPROACH SECTIONS':'接近区間','TARGETED SECTIONS':'対象区間','BIND':'割り当て','CLEAR ALL':'すべて解除','CANCEL':'キャンセル','SAVE':'保存','TOPOLOGY // ':'トポロジー // ','PATH EDIT':'経路編集','CHANGE':'変更','ADD':'追加','CONFIRM':'確認','ABANDON':'破棄','DELETE':'削除','INDICATIONS':'凡例','TRACK':'線路','SIGNAL':'信号','PLATFORM':'ホーム','OCCUPIED':'占有','LOCKED':'ロック','CONNECTING':'接続中','APPROVE':'承認','REVOKE':'取消','OVERRIDE':'信号無視','TOPOLOGY SNAPSHOT':'トポロジースナップショット','READ ONLY / LIVE':'読み取り専用 / ライブ','UNNAMED DEPOT':'名称なし車庫','SEG':'区間','UNBIND':'解除','LOADED':'読み込み済み','UNLOADED':'未読み込み','ON':'オン','OFF':'オフ','READ ONLY':'読み取り専用' }
};
const STATUS_TRANSLATIONS = {
  'zh-Hans': {'LIVE SNAPSHOT':'实时快照','WAITING FOR SERVER':'等待服务器','LINE SNAPSHOT UNAVAILABLE':'线路快照不可用','COMMAND FAILED // SERVER UNAVAILABLE':'命令失败 // 服务器不可用','CHECKING NODE CANDIDATE':'检查节点候选','NO GRAPH NODE SELECTED':'未选择轨道节点','LINE PATH SAVED':'线路路径已保存','CONFIRM REJECTED // DELETE NOT PENDING':'确认拒绝 // 未处于待删除状态','CONFIRM REJECTED // VEHICLE NOT FOUND':'确认拒绝 // 未找到列车','QUARANTINED VEHICLE REMOVED':'已移除隔离列车','SELECT REPLACEMENT NODE':'选择替换节点','NODE CHANGE CONFIRMED // UNSAVED':'节点更换已确认 // 未保存','NODE CHANGE ABANDONED':'已放弃节点更换','NODE DELETED // UNSAVED':'节点已删除 // 未保存','NODE CHANGES CANCELED':'节点修改已取消','NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE':'节点候选拒绝 // 预览不可用','UNBIND':'解绑','UNBIND TARGET FIRST':'请先解绑目标区段','UNSAVED':'未保存','BINDING CONFIRMED / UNSAVED':'绑定已确认 / 未保存','SELECTING APPROACH':'正在选择接近区段','SELECTING TARGETS':'正在选择目标区段','TARGETS MUST BE A SUBSET OF APPROACH':'目标区段必须属于接近区段','CLEAR TARGETS FIRST':'请先清除目标区段','PULSE LENGTH MUST BE 1-1200 TICKS':'脉冲长度必须为 1-1200 tick'},
  'zh-Hant': {'LIVE SNAPSHOT':'即時快照','WAITING FOR SERVER':'等待伺服器','LINE SNAPSHOT UNAVAILABLE':'路線快照不可用','COMMAND FAILED // SERVER UNAVAILABLE':'命令失敗 // 伺服器不可用','CHECKING NODE CANDIDATE':'檢查節點候選','NO GRAPH NODE SELECTED':'未選取軌道節點','LINE PATH SAVED':'路線已儲存','CONFIRM REJECTED // DELETE NOT PENDING':'確認拒絕 // 未處於待刪除狀態','CONFIRM REJECTED // VEHICLE NOT FOUND':'確認拒絕 // 找不到列車','QUARANTINED VEHICLE REMOVED':'已移除隔離列車','SELECT REPLACEMENT NODE':'選取替換節點','NODE CHANGE CONFIRMED // UNSAVED':'節點更換已確認 // 未儲存','NODE CHANGE ABANDONED':'已放棄節點更換','NODE DELETED // UNSAVED':'節點已刪除 // 未儲存','NODE CHANGES CANCELED':'節點修改已取消','NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE':'節點候選拒絕 // 預覽不可用'},
  ja: {'LIVE SNAPSHOT':'ライブスナップショット','WAITING FOR SERVER':'サーバーを待機中','LINE SNAPSHOT UNAVAILABLE':'路線スナップショットを取得できません','COMMAND FAILED // SERVER UNAVAILABLE':'コマンド失敗 // サーバー利用不可','CHECKING NODE CANDIDATE':'ノード候補を確認中','NO GRAPH NODE SELECTED':'線路ノード未選択','LINE PATH SAVED':'路線を保存しました','CONFIRM REJECTED // DELETE NOT PENDING':'確認拒否 // 削除待ちではありません','CONFIRM REJECTED // VEHICLE NOT FOUND':'確認拒否 // 車両が見つかりません','QUARANTINED VEHICLE REMOVED':'隔離車両を削除しました','SELECT REPLACEMENT NODE':'置換ノードを選択','NODE CHANGE CONFIRMED // UNSAVED':'ノード変更を確認 // 未保存','NODE CHANGE ABANDONED':'ノード変更を破棄','NODE DELETED // UNSAVED':'ノードを削除 // 未保存','NODE CHANGES CANCELED':'ノード変更をキャンセル','NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE':'ノード候補拒否 // プレビュー不可','UNBIND':'解除','UNBIND TARGET FIRST':'先に対象区間を解除してください','UNSAVED':'未保存','BINDING CONFIRMED / UNSAVED':'割り当て確認済み / 未保存','SELECTING APPROACH':'接近区間を選択中','SELECTING TARGETS':'対象区間を選択中','TARGETS MUST BE A SUBSET OF APPROACH':'対象区間は接近区間に含めてください','CLEAR TARGETS FIRST':'対象区間を先に解除してください','PULSE LENGTH MUST BE 1-1200 TICKS':'パルス長は1-1200 tickです'}
};
Object.entries(STATUS_TRANSLATIONS).forEach(([lang, values]) => Object.assign(I18N[lang], values));
Object.assign(I18N['zh-Hant'], {'UNBIND':'解除綁定','UNBIND TARGET FIRST':'請先解除目標區段','UNSAVED':'未儲存','BINDING CONFIRMED / UNSAVED':'綁定已確認 / 未儲存','SELECTING APPROACH':'正在選取接近區段','SELECTING TARGETS':'正在選取目標區段','TARGETS MUST BE A SUBSET OF APPROACH':'目標區段必須屬於接近區段','CLEAR TARGETS FIRST':'請先清除目標區段','PULSE LENGTH MUST BE 1-1200 TICKS':'脈衝長度必須為 1-1200 tick'});
let language = localStorage.getItem('mtrbr-language') || 'en';
function tr(value) {
  const table = I18N[language] || I18N.en;
  if (table[value]) return table[value];
  const prefix = Object.keys(table).filter(key => key.length > 3 && value.startsWith(key + ' // ')).sort((a,b) => b.length-a.length)[0];
  return prefix ? table[prefix] + value.slice(prefix.length) : value;
}
const uiScale = document.querySelector('#ui-scale');
const uiScaleValue = document.querySelector('#ui-scale-value');
function applyUiScale(value) { const clamped = Math.max(50, Math.min(200, Number(value) || 100)); document.documentElement.style.setProperty('--ui-scale', clamped / 100); uiScale.value = clamped; uiScaleValue.textContent = `${clamped}%`; localStorage.setItem('mtrbr-ui-scale', clamped); }
uiScale.addEventListener('input', () => applyUiScale(uiScale.value));
applyUiScale(localStorage.getItem('mtrbr-ui-scale') || '100');

function applyLanguage() {
  document.body.dataset.language = language;
  document.querySelectorAll('#language-switcher button').forEach(button => { button.textContent = ({en:'English','zh-Hans':'简体中文','zh-Hant':'繁體中文',ja:'日本語'})[button.dataset.language]; button.classList.toggle('active', button.dataset.language === language); });
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach(node => { const value = node.nodeValue.trim(); if (value && I18N.en[value]) node.nodeValue = node.nodeValue.replace(value, tr(value)); });
  document.querySelectorAll('input[placeholder],input[aria-label]').forEach(input => { const key = input.getAttribute('placeholder') || input.getAttribute('aria-label'); if (I18N.en[key]) { input.placeholder = tr(key); input.setAttribute('aria-label', tr(key)); } });
  renderPanels(); renderLines(); if (typeof renderSensorList === 'function') renderSensorList(); if (typeof renderSensorEditor === 'function') renderSensorEditor(); draw();
}
document.querySelectorAll('#language-switcher button').forEach(button => button.addEventListener('click', () => { language = button.dataset.language; localStorage.setItem('mtrbr-language', language); location.reload(); }));

const canvas = document.querySelector('#diagram');
const ctx = canvas.getContext('2d');
const view = { x: 0, y: 0, scale: 1, dragging: false, lastX: 0, lastY: 0, fitted: false };
let topology = null;
let sensors = [];
let sensorDraft = null;
let sensorBaseline = null;
let sensorBinding = null;
let sensorSelection = new Set();
let sensorOpenedFromLink = false;
let state = null;
let lines = null;
const token = new URLSearchParams(window.location.search).get('token') || '';
const deviceStorageKey = 'mtrbr-web-device-id';
const deviceId = (() => {
  let value = localStorage.getItem(deviceStorageKey);
  if (!value) {
    value = window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    localStorage.setItem(deviceStorageKey, value);
  }
  return value;
})();
const requestOptions = token ? { headers: { 'X-MTRBR-Token': token, 'X-MTRBR-Device': deviceId } } : {};
let canDispatch = false;
let invalidationReason = '';
let dismissedInvalidationReason = '';
let refreshInFlight = false;
let selectedVehicleId = null;
let hoveredVehicleId = null;
let vehicleMarkers = [];
let deleteVehicleId = null;
let deleteArmed = false;
const vehicleQuarantineStates = new Map();
let hoveredSignalId = null;
let signalDrag = null;
let selectedLineId = null;
let selectedNodeIndex = null;
let nodeChangeMode = false;
let nodeAddMode = false;
let nodeCandidate = null;
let nodeDraft = null;
let nodePreview = null;
let nodePreviewSerial = 0;
let lineNodePress = null;
const signalOffsets = JSON.parse(localStorage.getItem('mtrbr-signal-offsets') || '{}');

function currentRequests() { return (selectedState()?.requests || []).map(request => ({ ...request, vehicleId: String(request.vehicleId) })); }
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

function shortCodeCompare(left, right) {
  const first = String(left || '').toUpperCase();
  const second = String(right || '').toUpperCase();
  const firstStartsDigit = /^[0-9]/.test(first);
  const secondStartsDigit = /^[0-9]/.test(second);
  if (firstStartsDigit !== secondStartsDigit) return firstStartsDigit ? -1 : 1;
  const length = Math.min(first.length, second.length);
  for (let index = 0; index < length; index++) {
    const difference = first.charCodeAt(index) - second.charCodeAt(index);
    if (difference) return difference;
  }
  return first.length - second.length;
}

function updateInvalidation(reason) {
  invalidationReason = reason || '';
  const modal = document.querySelector('#token-invalidation');
  const message = document.querySelector('#token-invalidation-message');
  if (!invalidationReason || dismissedInvalidationReason === invalidationReason) {
    modal.hidden = true;
    return;
  }
  message.textContent = invalidationReason === 'LEAKED'
    ? 'TOKEN INVALIDATION: HAS BEEN LEAKED'
    : invalidationReason === 'PLAYER_OFFLINE'
      ? 'TOKEN INVALIDATION: PLAYER OFFLINE'
      : `TOKEN INVALIDATION: ${invalidationReason}`;
  modal.hidden = false;
}

function resize() {
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.floor(rect.width * devicePixelRatio);
  canvas.height = Math.floor(rect.height * devicePixelRatio);
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
  draw();
}

function canvasPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return { x: (event.clientX - rect.left - view.x) / view.scale, z: (event.clientY - rect.top - view.y) / view.scale };
}

function selectedDimension() { return topology && topology.dimensions && topology.dimensions[0]; }
function selectedState() {
  const data = selectedDimension();
  return data && state && state.dimensions && state.dimensions.find(item => item.id === data.id);
}
function selectedLineDimension() {
  const data = selectedDimension();
  return data && lines?.dimensions?.find(item => item.id === data.id);
}
function selectedLine() { return selectedLineDimension()?.depots?.find(line => line.id === selectedLineId) || null; }
function lineMode() { return selectedLine() !== null; }

function fit() {
  const data = selectedDimension();
  if (!data) return;
  const line = selectedLine();
  const points = line ? line.segments.flatMap(segment => segment.points) : data.rails.flatMap(rail => rail.points);
  if (!points.length) return;
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
  if (section && section.locked) return '#f7f7f7';
  if (section && section.reserved) return '#f0b42e';
  return '#969da0';
}

function signalColor(aspect) {
  return { GREEN: '#15ed6b', RED: '#c82424', YELLOW: '#d88900', DOUBLE_YELLOW: '#ffd452' }[aspect] || '#5a6a70';
}

function signalOffset(signal) { return signalOffsets[signal.id] || { x: 0, z: 0 }; }
function signalPosition(signal) {
  const offset = signalOffset(signal);
  return { x: signal.x + offset.x, z: signal.z + offset.z };
}
function saveSignalOffsets() { localStorage.setItem('mtrbr-signal-offsets', JSON.stringify(signalOffsets)); }

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
  ctx.lineWidth = Math.max(1.536, 1.088 / view.scale);
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

function drawStationLabel(platforms) {
  if (!platforms.length || !platforms[0].name) return;
  const marker = railMidpoint(platforms[0].points);
  const horizontal = Math.abs(marker.dx) >= Math.abs(marker.dz);
  const candidates = platforms.map(platform => ({ platform, marker: railMidpoint(platform.points) }));
  const chosen = candidates.reduce((best, candidate) => horizontal
    ? candidate.marker.z > best.marker.z ? candidate : best
    : candidate.marker.x > best.marker.x ? candidate : best);
  const labelX = chosen.marker.x + (horizontal ? 0 : 4);
  const labelZ = chosen.marker.z + (horizontal ? 4 : 0);
  ctx.save();
  ctx.translate(labelX, labelZ);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = '700 18px "Terminus", "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#f2f4f4';
  ctx.fillText(platforms[0].name, 0, 0);
  ctx.restore();
}

function drawPlatformNumber(platform) {
  if (!platform.number) return;
  const points = offsetRail(platform.points, platform.side || 1, 2.6);
  ctx.save();
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = '700 10px "Terminus", "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#f7f7f7';
  const inset = ctx.measureText(platform.number).width / view.scale;
  const ends = [pointAlongRail(points, inset), pointAlongRail([...points].reverse(), inset)];
  for (const end of ends) ctx.fillText(platform.number, end[0] * view.scale, end[1] * view.scale);
  ctx.restore();
}

function pointAlongRail(points, distance) {
  let remaining = distance;
  for (let index = 1; index < points.length; index++) {
    const start = points[index - 1], end = points[index];
    const length = Math.hypot(end[0] - start[0], end[1] - start[1]);
    if (length >= remaining) {
      const ratio = length ? remaining / length : 0;
      return [start[0] + (end[0] - start[0]) * ratio, start[1] + (end[1] - start[1]) * ratio];
    }
    remaining -= length;
  }
  return points[points.length - 1];
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

function drawArrow(point, dx, dz, color) {
  const angle = Math.atan2(dz, dx);
  ctx.save();
  ctx.translate(point.x, point.z);
  ctx.rotate(angle);
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(2.4, 0); ctx.lineTo(-1.5, -.9); ctx.lineTo(-1.5, .9); ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function drawLineArrow(points, color) {
  const marker = railMidpoint(points);
  drawArrow(marker, marker.dx, marker.dz, color);
}

function drawSelectedLine(line) {
  for (const segment of line.segments) {
    const color = segment.disconnected ? '#ef4f4f' : segment.platform ? '#ff9900' : '#39d8ff';
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(.72, .544 / view.scale);
    drawRail(segment.points, 0);
    drawLineArrow(segment.points, color);
    if (segment.disconnected) {
      const start = segment.points[0];
      ctx.fillStyle = '#ef4f4f';
      ctx.beginPath();
      ctx.arc(start[0], start[1], Math.max(1.2, 2.8 / view.scale), 0, Math.PI * 2);
      ctx.fill();
    }
  }
}

function drawLineNodes(line) {
  const nodes = nodeDraft || line.nodes || [];
  const drawNode = (node, index, selected) => {
    const visual = lineNodePosition(line, index, node);
    ctx.save();
    const blinkYellow = Math.floor(Date.now() / 420) % 2 === 0;
    ctx.fillStyle = selected ? (blinkYellow ? '#f0b42e' : '#8a671b') : '#8ceaff';
    ctx.strokeStyle = selected ? '#f0b42e' : '#003c4d';
    ctx.lineWidth = Math.max(.5, 1.4 / view.scale);
    ctx.beginPath();
    ctx.arc(visual.x, visual.z, Math.max(.78, 2 / view.scale), 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  };
  nodes.forEach((node, index) => { if (index !== selectedNodeIndex) drawNode(node, index, false); });
  if (selectedNodeIndex !== null && nodes[selectedNodeIndex]) drawNode(nodes[selectedNodeIndex], selectedNodeIndex, true);
  if (nodeCandidate) {
    const visual = { x: nodeCandidate.displayX ?? nodeCandidate.x, z: nodeCandidate.displayZ ?? nodeCandidate.z };
    ctx.save();
    ctx.strokeStyle = nodePreview?.ok === false ? '#ef4f4f' : '#fff36b';
    ctx.lineWidth = Math.max(.7, 1.8 / view.scale);
    ctx.setLineDash([1, 1]);
    ctx.beginPath();
    ctx.arc(visual.x, visual.z, Math.max(2, 5 / view.scale), 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }
}

function draw() {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  const data = selectedDimension();
  if (!data) return;
  const liveState = selectedState();
  const sections = new Map((liveState?.sections || []).map(section => [section.id, section]));
  const signalAspects = liveState?.signalAspects || {};
  ctx.save();
  ctx.translate(view.x, view.y);
  ctx.scale(view.scale, view.scale);
  ctx.lineCap = 'butt';
  ctx.lineJoin = 'round';
  vehicleMarkers = [];
  const line = selectedLine();
  const highlighted = line ? null : highlightedRequest();
  const highlightedSections = new Set(highlighted?.sections || []);
  const blinkYellow = Math.floor(Date.now() / 420) % 2 === 0;

  for (const platform of (data.platforms || [])) drawPlatform(platform);

  const sectionLayer = rail => {
    const section = sections.get(rail.id);
    if (section?.occupied) return 2;
    if (section?.locked) return 1;
    return 0;
  };
  const rails = line ? data.rails : [...data.rails].sort((left, right) => sectionLayer(left) - sectionLayer(right));
  for (const rail of rails) {
    const section = sections.get(rail.id);
    ctx.strokeStyle = line
      ? '#969da0'
      : section?.occupied
        ? '#c82424'
        : sectionColor(section);
    ctx.lineWidth = Math.max(.72, .544 / view.scale);
    drawRail(rail.points);
    if (sensorDraft && (sensorSelection.has(rail.id) || sensorDraft.approach.includes(rail.id) || sensorDraft.targets.includes(rail.id))) {
      ctx.save();
      ctx.strokeStyle = sensorBinding && sensorSelection.has(rail.id) ? '#ffffff' : sensorDraft.targets.includes(rail.id) ? '#17c4ae' : '#e1af31';
      ctx.lineWidth = Math.max(1.5, 3 / view.scale); drawRail(rail.points); ctx.restore();
    }
    if (!line && section && section.vehicles && section.vehicles.length) {
      const midpoint = railMidpoint(rail.points);
      section.vehicles.forEach((code, index) => {
        const vehicleId = section.vehicleIds?.[index] ?? currentRequests().find(entry => entry.code === code)?.vehicleId ?? null;
        vehicleMarkers.push({ code, vehicleId, marker: midpoint, index });
      });
    }
  }

  // Draw request highlights as a final overlay so the base free-section layer
  // can never cover the yellow/gray selection state.
  if (!line && highlightedSections.size) {
    for (const rail of data.rails) {
      if (!highlightedSections.has(rail.id)) continue;
      const section = sections.get(rail.id);
      if (section?.occupied) continue;
      ctx.strokeStyle = blinkYellow ? '#f0b42e' : '#969da0';
      ctx.lineWidth = Math.max(.72, .544 / view.scale);
      drawRail(rail.points);
    }
  }

  const stations = new Map();
  for (const platform of (data.platforms || [])) {
    drawPlatformNumber(platform);
    if (platform.name) stations.set(platform.name, [...(stations.get(platform.name) || []), platform]);
  }
  stations.forEach(drawStationLabel);

  ctx.save();
  ctx.strokeStyle = '#3d8cff';
  ctx.lineWidth = Math.max(.72, .544 / view.scale);
  for (const link of (data.repeaterLinks || [])) {
    ctx.beginPath();
    ctx.moveTo(link.signalX, link.signalZ);
    ctx.lineTo(link.repeaterX, link.repeaterZ);
    ctx.stroke();
  }
  ctx.restore();

  for (const signal of data.signals) {
    const position = signalPosition(signal);
    ctx.save();
    ctx.translate(position.x, position.z);
    ctx.rotate(signal.angle * Math.PI / 180);
    ctx.strokeStyle = '#b7bec1';
    ctx.lineWidth = .44;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(1.92, 0);
    ctx.stroke();
    ctx.fillStyle = signalColor(signalAspects[signal.id] || 'UNKNOWN');
    ctx.beginPath();
    ctx.arc(0, 0, .704, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
    if (signal.id === hoveredSignalId || signal.id === signalDrag?.id || signal.id === document.querySelector('#signal-name-form').dataset.signalId && !document.querySelector('#signal-name-form').hidden) {
      ctx.save();
      ctx.strokeStyle = '#747c80';
      ctx.lineWidth = .38;
	  ctx.setLineDash([.6, .6]);
      ctx.beginPath();
      ctx.moveTo(position.x, position.z);
      ctx.lineTo(signal.nodeX, signal.nodeZ);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.scale(1 / view.scale, 1 / view.scale);
      ctx.font = '700 13px "Terminus", "Microsoft YaHei", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillStyle = '#e2e7e8';
      ctx.fillText(signal.name || `${signal.signalX}, ${signal.signalY}, ${signal.signalZ}`, position.x * view.scale, position.z * view.scale - 15);
      ctx.restore();
    }
  }

  if (!line) vehicleMarkers.forEach(({ code, marker, index }) => drawVehicle(code, marker, index));
  if (line) {
    drawSelectedLine(line);
    drawLineNodes(line);
  }

  if (typeof drawSensors === 'function') drawSensors();
  ctx.restore();
  positionVehicleDeleteForm();
  document.querySelector('#scale-label').textContent = `${Math.round(100 / view.scale)} m`;
}

function renderPanels() {
  const players = state?.players || [];
  document.querySelector('#player-list').innerHTML = players.map(player => `<div class="player"><img src="${encodeURI(player.avatar)}" alt=""><span class="player-name">${escapeHtml(player.name)}</span><b class="player-state ${player.dispatching ? 'on' : 'off'}">${player.dispatching ? 'ON' : 'OFF'}</b></div>`).join('');
  const requests = [...currentRequests()].sort((left, right) => shortCodeCompare(left.code, right.code));
  requests.forEach(request => vehicleQuarantineStates.set(request.vehicleId, request.quarantineState || 'NORMAL'));
  document.querySelector('#request-list').innerHTML = requests.map(request => `<div class="request ${request.vehicleId === selectedVehicleId ? 'selected' : ''}" data-vehicle-id="${request.vehicleId}"><span class="request-code">${escapeHtml(request.code)}</span><span class="request-state ${requestStateClass(request.state)}">${escapeHtml(request.state)}</span><span class="request-detail">R: ${escapeHtml(request.route || '--')} | N: ${escapeHtml(request.next || '--')} | D: ${escapeHtml(request.destination || '--')}</span></div>`).join('');
  document.querySelectorAll('.request').forEach(row => row.addEventListener('click', () => openVehicleDelete(row.dataset.vehicleId)));
  const selected = requests.find(request => request.vehicleId === selectedVehicleId);
  const actions = document.querySelector('#action-drawer');
  actions.hidden = !(canDispatch && selected);
  document.querySelectorAll('[data-action]').forEach(button => { button.disabled = !canDispatch; });
  document.querySelector('#action-code').textContent = selected?.code || '--';
  document.querySelector('.mode').textContent = canDispatch ? 'DISPATCH ENABLED' : 'READ ONLY / LIVE';
  renderLineEditPrompt();
}

function renderLineEditPrompt() {
  const prompt = document.querySelector('#line-edit-prompt');
  const line = selectedLine();
  prompt.hidden = !line || selectedNodeIndex === null;
  const nodeCount = lineNodes(line).length;
  const internal = selectedNodeIndex !== null && selectedNodeIndex > 0 && selectedNodeIndex < nodeCount - 1;
  document.querySelector('#node-change').disabled = !canDispatch || !internal || nodeChangeMode;
  document.querySelector('#node-add').disabled = !canDispatch || selectedNodeIndex === null || selectedNodeIndex >= nodeCount - 1 || nodeChangeMode;
  document.querySelector('#node-confirm').disabled = !canDispatch || !nodeChangeMode || !nodeCandidate || !nodePreview?.ok;
  document.querySelector('#node-abandon').disabled = !nodeChangeMode;
  document.querySelector('#node-delete').disabled = !canDispatch || !internal || nodeChangeMode;
  document.querySelector('#node-cancel').disabled = !nodeDraft;
  document.querySelector('#node-save').disabled = !canDispatch || !nodeDraft;
}

function renderLines() {
  const lineDimension = selectedLineDimension();
  const entries = lineDimension?.depots || [];
  document.querySelector('#line-list').innerHTML = entries.map(line => `<div class="line ${line.id === selectedLineId ? 'selected' : ''}" data-line-id="${escapeHtml(line.id)}"><span class="line-name">${escapeHtml(line.name || 'UNNAMED DEPOT')}</span><span class="line-count">${line.segments.length} SEG</span><span class="line-id">${escapeHtml(line.id)}</span></div>`).join('');
  document.querySelectorAll('.line').forEach(row => row.addEventListener('click', () => selectLine(row.dataset.lineId)));
}

function selectLine(lineId) {
  selectedLineId = selectedLineId === lineId ? null : lineId;
  selectedVehicleId = null;
  hoveredVehicleId = null;
  document.querySelector('#vehicle-drawer').classList.remove('open');
  document.querySelector('#vehicle-drawer').setAttribute('aria-hidden', 'true');
  const line = selectedLine();
  nodeDraft = null;
  selectedNodeIndex = null;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.body.classList.toggle('line-mode', !!line);
  document.querySelector('#status').textContent = line ? `LINE // ${line.name || line.id}` : 'LIVE SNAPSHOT';
  document.querySelector('.map-title').firstChild.textContent = line ? 'DEPOT PATH // ' : 'TOPOLOGY // ';
  renderLines();
  renderPanels();
  fit();
}

async function loadLines(force = false) {
  if (lines && !force) return;
  const response = await fetch('api/lines', { cache: 'no-store', ...requestOptions });
  if (!response.ok) throw new Error('line snapshot unavailable');
  lines = await response.json();
  if (selectedLineId && !selectedLine()) {
    selectedLineId = null;
    document.body.classList.remove('line-mode');
    document.querySelector('.map-title').firstChild.textContent = 'TOPOLOGY // ';
  }
  renderLines();
  draw();
}

function selectVehicle(vehicleId) {
	  vehicleId = String(vehicleId ?? '');
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

function vehicleMarker(vehicleId) {
  return vehicleMarkers.find(marker => marker.vehicleId === vehicleId) || null;
}

function vehicleLabelPosition(vehicle) {
  const vertical = Math.abs(vehicle.marker.dz) > Math.abs(vehicle.marker.dx);
  const offset = vehicle.index * -18;
  return {
    x: view.x + vehicle.marker.x * view.scale + (vertical ? -offset : 0),
    z: view.y + vehicle.marker.z * view.scale + (vertical ? 0 : offset)
  };
}

function positionVehicleDeleteForm() {
  const form = document.querySelector('#vehicle-delete-form');
  if (form.hidden || deleteVehicleId === null) return;
  const marker = vehicleMarker(deleteVehicleId);
  if (!marker) {
    closeVehicleDelete();
    return;
  }
  const position = vehicleLabelPosition(marker);
  form.style.left = `${position.x}px`;
  form.style.top = `${position.z - 16}px`;
}

function closeVehicleDelete() {
  const form = document.querySelector('#vehicle-delete-form');
  form.hidden = true;
  deleteVehicleId = null;
  deleteArmed = false;
  document.querySelector('#vehicle-delete-confirm').disabled = true;
  document.querySelector('#vehicle-quarantine').disabled = true;
  document.querySelector('#vehicle-delete').disabled = true;
}

function openVehicleDelete(vehicleId) {
  vehicleId = String(vehicleId ?? '');
  if (!vehicleId) return;
  if (deleteVehicleId === vehicleId && !document.querySelector('#vehicle-delete-form').hidden) {
    closeVehicleDelete();
    draw();
    return;
  }
  selectVehicle(vehicleId);
  const form = document.querySelector('#vehicle-delete-form');
  deleteVehicleId = vehicleId;
  deleteArmed = vehicleQuarantineStates.get(vehicleId) === 'DELETE_PENDING';
  form.dataset.vehicleId = String(vehicleId);
  const quarantineState = vehicleQuarantineStates.get(vehicleId) || 'NORMAL';
  const quarantine = document.querySelector('#vehicle-quarantine');
  const deleteButton = document.querySelector('#vehicle-delete');
  const confirm = document.querySelector('#vehicle-delete-confirm');
  quarantine.textContent = quarantineState === 'QUARANTINED' || quarantineState === 'DELETE_PENDING' ? 'RELEASE' : 'QUARANTINE';
  deleteButton.textContent = quarantineState === 'DELETE_PENDING' ? 'CANCEL' : 'DELETE';
  quarantine.disabled = !canDispatch || quarantineState === 'DELETE_PENDING';
  deleteButton.disabled = !canDispatch || quarantineState === 'NORMAL';
  confirm.disabled = !canDispatch || quarantineState !== 'DELETE_PENDING';
  form.hidden = false;
  positionVehicleDeleteForm();
  draw();
}

function refreshVehicleDeleteForm() {
  if (deleteVehicleId !== null && !document.querySelector('#vehicle-delete-form').hidden) openVehicleDeleteControls(deleteVehicleId);
}

function openVehicleDeleteControls(vehicleId) {
  const form = document.querySelector('#vehicle-delete-form');
  const quarantineState = vehicleQuarantineStates.get(vehicleId) || 'NORMAL';
  const quarantine = document.querySelector('#vehicle-quarantine');
  const deleteButton = document.querySelector('#vehicle-delete');
  const confirm = document.querySelector('#vehicle-delete-confirm');
  quarantine.textContent = quarantineState === 'QUARANTINED' || quarantineState === 'DELETE_PENDING' ? 'RELEASE' : 'QUARANTINE';
  deleteButton.textContent = quarantineState === 'DELETE_PENDING' ? 'CANCEL' : 'DELETE';
  quarantine.disabled = !canDispatch || quarantineState === 'DELETE_PENDING';
  deleteButton.disabled = !canDispatch || quarantineState === 'NORMAL';
  confirm.disabled = !canDispatch || quarantineState !== 'DELETE_PENDING';
}

function armVehicleDelete() {
  if (!canDispatch || deleteVehicleId === null) return;
  const current = vehicleQuarantineStates.get(deleteVehicleId) || 'NORMAL';
  const action = current === 'DELETE_PENDING' ? 'cancel_delete' : 'delete_pending';
  postVehicleAction(action,
    action === 'delete_pending' ? 'DELETE PENDING // CONFIRM TO REMOVE' : 'DELETE CANCELED // VEHICLE QUARANTINED',
    action === 'delete_pending' ? 'DELETE REJECTED // VEHICLE NOT QUARANTINED' : 'CANCEL REJECTED // INVALID DELETE STATE',
    action === 'delete_pending' ? 'DELETE_PENDING' : 'QUARANTINED');
}

async function postVehicleAction(action, successText, failureText, nextState) {
  if (!canDispatch || deleteVehicleId === null) return;
  const vehicleId = deleteVehicleId;
  const buttons = [document.querySelector('#vehicle-quarantine'), document.querySelector('#vehicle-delete'), document.querySelector('#vehicle-delete-confirm')];
  buttons.forEach(button => { button.disabled = true; });
  try {
    const response = await fetch('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json', ...requestOptions.headers }, body: JSON.stringify({ action, vehicleId }) });
    if (!response.ok) {
      document.querySelector('#status').textContent = failureText;
      openVehicleDeleteControls(vehicleId);
      return;
    }
    if (nextState) vehicleQuarantineStates.set(vehicleId, nextState);
    document.querySelector('#status').textContent = successText;
    openVehicleDeleteControls(vehicleId);
  } catch (_) {
    document.querySelector('#status').textContent = `${action.toUpperCase()} FAILED // SERVER UNAVAILABLE`;
    openVehicleDeleteControls(vehicleId);
  }
}

async function toggleVehicleQuarantine() {
  if (!canDispatch || deleteVehicleId === null) return;
  const vehicleId = deleteVehicleId;
  const current = vehicleQuarantineStates.get(vehicleId) || 'NORMAL';
  const action = current === 'QUARANTINED' ? 'release' : 'quarantine';
  await postVehicleAction(action,
    action === 'quarantine' ? 'VEHICLE QUARANTINED' : 'QUARANTINE RELEASED',
    action === 'quarantine' ? 'QUARANTINE REJECTED // VEHICLE NOT FOUND' : 'RELEASE REJECTED // INVALID STATE',
    action === 'quarantine' ? 'QUARANTINED' : 'NORMAL');
}

async function confirmVehicleDelete() {
  if (!canDispatch || deleteVehicleId === null || vehicleQuarantineStates.get(deleteVehicleId) !== 'DELETE_PENDING') {
    document.querySelector('#status').textContent = tr('CONFIRM REJECTED // DELETE NOT PENDING');
    return;
  }
  const vehicleId = deleteVehicleId;
  const response = await fetch('api/commands', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...requestOptions.headers },
    body: JSON.stringify({ action: 'confirm_quarantine_removal', vehicleId })
  });
  if (!response.ok) {
    document.querySelector('#status').textContent = tr('CONFIRM REJECTED // VEHICLE NOT FOUND');
    return;
  }
  if (selectedVehicleId === vehicleId) selectedVehicleId = null;
  closeVehicleDelete();
  document.querySelector('#status').textContent = tr('QUARANTINED VEHICLE REMOVED');
  await refresh();
}

function focusVehicle(vehicleId) {
  const vehicle = vehicleMarkers.find(marker => marker.vehicleId === vehicleId);
  if (!vehicle) return;
  view.x = canvas.clientWidth / 2 - vehicle.marker.x * view.scale;
  view.y = canvas.clientHeight / 2 - vehicle.marker.z * view.scale;
  view.fitted = true;
}

function vehicleAt(event) {
  const rect = canvas.getBoundingClientRect();
  const x = event.clientX - rect.left, z = event.clientY - rect.top;
  return vehicleMarkers.find(vehicle => {
    const label = vehicleLabelPosition(vehicle);
    return Math.abs(label.x - x) < 16 && Math.abs(label.z - z) < 10;
  });
}

function projection(points, x, z) {
  let best = null;
  for (let index = 1; index < points.length; index++) {
    const start = points[index - 1], end = points[index];
    const dx = end[0] - start[0], dz = end[1] - start[1];
    const lengthSquared = dx * dx + dz * dz;
    if (!lengthSquared) continue;
    const ratio = Math.max(0, Math.min(1, ((x - start[0]) * dx + (z - start[1]) * dz) / lengthSquared));
    const px = start[0] + dx * ratio, pz = start[1] + dz * ratio;
    const distanceSquared = (x - px) ** 2 + (z - pz) ** 2;
    if (!best || distanceSquared < best.distanceSquared) best = { x: px, z: pz, dx, dz, distanceSquared };
  }
  return best;
}

function lineNodes(line = selectedLine()) {
  return nodeDraft || line?.nodes || [];
}

function lineNodePosition(line, index, node) {
  return { x: node.displayX ?? node.x, z: node.displayZ ?? node.z };
}

function sameNode(left, right) {
  return !!left && !!right && left.x === right.x && left.y === right.y && left.z === right.z;
}

function repeatedNodePair(nodes) {
  for (let index = 1; index < nodes.length; index++) {
    if (sameNode(nodes[index - 1], nodes[index])) return [index - 1, index];
  }
  return null;
}

function candidateDraft() {
  const line = selectedLine();
  if (!line || !nodeCandidate || selectedNodeIndex === null) return null;
  const draft = lineNodes(line).map(node => ({ ...node }));
  if (nodeAddMode) draft.splice(selectedNodeIndex + 1, 0, { ...nodeCandidate });
  else draft[selectedNodeIndex] = { ...nodeCandidate };
  return draft;
}

function previewFailureText(result) {
  if (Number.isInteger(result?.fromIndex) && Number.isInteger(result?.toIndex) && result.from && result.to) {
    const from = `${result.from.x},${result.from.y},${result.from.z}`;
    const to = `${result.to.x},${result.to.y},${result.to.z}`;
    return `${result.reason || result.error} // ${result.fromIndex}-${result.toIndex} // ${from} > ${to}`;
  }
  return result?.error || 'UNKNOWN';
}

async function previewNodeCandidate() {
  const line = selectedLine();
  const dimension = selectedDimension();
  const draft = candidateDraft();
  if (!line || !dimension || !draft) return;
  const repeated = repeatedNodePair(draft);
  if (repeated) {
    nodePreview = { ok: false, error: 'REPEATED_NODE', fromIndex: repeated[0], toIndex: repeated[1], from: draft[repeated[0]], to: draft[repeated[1]] };
    document.querySelector('#status').textContent = `NODE CANDIDATE REJECTED // ${previewFailureText(nodePreview)}`;
    renderPanels();
    draw();
    return;
  }
  const serial = ++nodePreviewSerial;
  nodePreview = null;
  document.querySelector('#status').textContent = tr('CHECKING NODE CANDIDATE');
  renderPanels();
  draw();
  try {
    const response = await fetch('api/lines/preview-nodes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...requestOptions.headers },
      body: JSON.stringify({ dimension: dimension.id, depotId: line.id, fingerprint: line.fingerprint, nodes: draft })
    });
    const result = await response.json();
    if (serial !== nodePreviewSerial) return;
    nodePreview = result;
    document.querySelector('#status').textContent = result.ok
      ? 'NODE CANDIDATE VALID // CONFIRM TO APPLY'
      : `NODE CANDIDATE REJECTED // ${previewFailureText(result)}`;
  } catch (_) {
    if (serial !== nodePreviewSerial) return;
    nodePreview = { ok: false, error: 'PREVIEW_UNAVAILABLE' };
    document.querySelector('#status').textContent = tr('NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE');
  }
  renderPanels();
  draw();
}

function nodeAt(event, nodes, line = selectedLine()) {
  const point = canvasPoint(event);
  const tolerance = 10 / view.scale;
  let best = null;
  nodes.forEach((node, index) => {
    const visual = line ? lineNodePosition(line, index, node) : { x: node.displayX ?? node.x, z: node.displayZ ?? node.z };
    const distance = Math.hypot(visual.x - point.x, visual.z - point.z);
    if (distance <= tolerance && (!best || distance < best.distance)) best = { node, index, distance };
  });
  return best;
}

function graphNodeAt(event) {
  const data = selectedDimension();
  if (!data) return null;
  const nodes = new Map();
  data.rails.forEach(rail => {
    const start = rail.points[0], end = rail.points[rail.points.length - 1];
    [[start, rail.startNode], [end, rail.endNode]].forEach(([point, node]) => {
      if (!node) return;
      const key = `${node.x}:${node.y}:${node.z}`;
      if (!nodes.has(key)) nodes.set(key, { ...node, displayX: point[0], displayZ: point[1] });
    });
  });
  return nodeAt(event, [...nodes.values()], null)?.node || null;
}

function signalAt(event) {
  if (lineMode()) return null;
  const data = selectedDimension();
  if (!data) return null;
  const point = canvasPoint(event);
  const x = point.x, z = point.z;
  const tolerance = 10 / view.scale;
  return data.signals.find(signal => {
    const position = signalPosition(signal);
    return Math.hypot(position.x - x, position.z - z) <= tolerance;
  }) || null;
}

function openSignalName(signal) {
  const position = signalPosition(signal);
  const form = document.querySelector('#signal-name-form');
  const input = document.querySelector('#signal-name');
  if (!form.hidden && form.dataset.signalId === signal.id) {
    form.hidden = true;
    hoveredSignalId = null;
    draw();
    return;
  }
  form.dataset.signalId = signal.id;
  form.style.left = `${view.x + position.x * view.scale}px`;
  form.style.top = `${view.y + position.z * view.scale - 35}px`;
  input.value = signal.name || '';
  input.disabled = !canDispatch;
  form.querySelector('button').disabled = !canDispatch;
  form.hidden = false;
  input.focus();
  input.select();
  hoveredSignalId = signal.id;
  draw();
}

async function saveSignalName(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!canDispatch || !form.dataset.signalId) return;
  const name = document.querySelector('#signal-name').value.trim();
  if (name && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/.test(name)) return;
  const response = await fetch('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json', ...requestOptions.headers }, body: JSON.stringify({ action: 'name_signal', signalId: form.dataset.signalId, name }) });
  if (response.ok) {
    form.hidden = true;
    refresh();
  }
}

async function dispatch(action) {
  if (!canDispatch || selectedVehicleId === null) return;
  try {
    const response = await fetch('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json', ...requestOptions.headers }, body: JSON.stringify({ action, vehicleId: String(selectedVehicleId) }) });
    if (!response.ok) {
      let reason = 'COMMAND REJECTED';
      try { reason = `COMMAND REJECTED // ${(await response.json()).reason || response.status}`; } catch (_) { reason += ` // ${response.status}`; }
      document.querySelector('#status').textContent = reason;
      return;
    }
    document.querySelector('#status').textContent = `${action.toUpperCase()} SUBMITTED`;
    await refresh();
  } catch (_) {
    document.querySelector('#status').textContent = tr('COMMAND FAILED // SERVER UNAVAILABLE');
  }
}

function changeNode() {
  if (!canDispatch || selectedNodeIndex === null) return;
  nodeChangeMode = true;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.querySelector('#status').textContent = tr('SELECT REPLACEMENT NODE');
  renderPanels();
  draw();
}

function confirmNodeChange() {
  if (!nodeChangeMode || !nodeCandidate || !nodePreview?.ok || selectedNodeIndex === null) return;
  const line = selectedLine();
  const draft = candidateDraft();
  const repeated = repeatedNodePair(draft);
  if (repeated) {
    document.querySelector('#status').textContent = `NODE CHANGE REJECTED // REPEATED NODE ${repeated[0]}-${repeated[1]}`;
    renderPanels();
    draw();
    return;
  }
  if (nodeAddMode) selectedNodeIndex++;
  nodeDraft = draft;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.querySelector('#status').textContent = tr('NODE CHANGE CONFIRMED // UNSAVED');
  renderPanels();
  draw();
}

function abandonNodeChange() {
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.querySelector('#status').textContent = tr('NODE CHANGE ABANDONED');
  renderPanels();
  draw();
}

function deleteNode() {
  const line = selectedLine();
  if (!canDispatch || !line || selectedNodeIndex === null || selectedNodeIndex <= 0 || selectedNodeIndex >= lineNodes(line).length - 1) return;
  const draft = lineNodes(line).map(node => ({ ...node }));
  draft.splice(selectedNodeIndex, 1);
  nodeDraft = draft;
  selectedNodeIndex = Math.min(selectedNodeIndex, draft.length - 2);
  document.querySelector('#status').textContent = tr('NODE DELETED // UNSAVED');
  renderPanels();
  draw();
}

function cancelNodeDraft() {
	nodeDraft = null;
	nodeChangeMode = false;
	nodeAddMode = false;
	nodeCandidate = null;
	nodePreview = null;
	nodePreviewSerial++;
	document.querySelector('#status').textContent = tr('NODE CHANGES CANCELED');
  renderPanels();
  draw();
}

async function saveNodeDraft() {
  const line = selectedLine();
  const dimension = selectedDimension();
  if (!canDispatch || !line || !dimension || !nodeDraft) return;
  const repeated = repeatedNodePair(nodeDraft);
  if (repeated) {
    document.querySelector('#status').textContent = `LINE SAVE REJECTED // REPEATED NODE ${repeated[0]}-${repeated[1]}`;
    return;
  }
  const response = await fetch('api/lines/save-nodes', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...requestOptions.headers },
    body: JSON.stringify({ dimension: dimension.id, depotId: line.id, fingerprint: line.fingerprint, nodes: nodeDraft })
  });
  const result = await response.json();
  if (!response.ok || !result.ok) {
    document.querySelector('#status').textContent = `LINE SAVE FAILED // ${result.error || 'UNKNOWN'}`;
    return;
  }
  line.segments = result.segments;
  line.nodes = result.nodes;
  line.fingerprint = result.fingerprint;
  nodeDraft = null;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  selectedNodeIndex = null;
  document.querySelector('#status').textContent = tr('LINE PATH SAVED');
  renderLines();
  renderPanels();
  fit();
}

async function refresh() {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    const [topologyResponse, stateResponse, sessionResponse] = await Promise.all([
      fetch('api/topology', { cache: 'no-store', ...requestOptions }),
      fetch('api/state', { cache: 'no-store', ...requestOptions }),
      fetch('api/session', { cache: 'no-store', ...requestOptions })
    ]);
    if (!topologyResponse.ok || !stateResponse.ok) throw new Error('snapshot unavailable');
    topology = await topologyResponse.json();
    state = await stateResponse.json();
    const session = sessionResponse.ok ? await sessionResponse.json() : { canDispatch: false, invalidationReason: '' };
    canDispatch = session.canDispatch === true;
    await refreshSensors();
    updateInvalidation(session.invalidationReason);
    const data = selectedDimension();
    if (!lineMode()) document.querySelector('#status').textContent = tr('LIVE SNAPSHOT');
    document.querySelector('#updated').textContent = new Date().toLocaleTimeString();
    if (!data) return;
    document.querySelector('#dimension').textContent = data.id.toUpperCase();
    document.querySelector('#map-dimension').textContent = data.id;
    document.querySelector('#revision').textContent = `REV ${data.revision}`;
    document.querySelector('#rail-count').textContent = data.rails.length;
    document.querySelector('#signal-count').textContent = data.signals.length;
    renderPanels();
    if (lines) renderLines();
    if (!view.fitted) fit(); else draw();
  } catch (_) {
    document.querySelector('#status').textContent = tr('WAITING FOR SERVER');
  } finally {
    refreshInFlight = false;
  }
}

canvas.addEventListener('pointerdown', event => {
  if (event.button === 2) return;
  if (sensorBinding && event.button === 0 && sensorSectionAt(event)) return;
  if (lineMode()) {
    if (nodeChangeMode) {
      nodeCandidate = graphNodeAt(event);
      nodePreview = null;
      if (nodeCandidate) previewNodeCandidate();
      else document.querySelector('#status').textContent = tr('NO GRAPH NODE SELECTED');
      renderPanels();
      draw();
      return;
    }
    const selected = nodeAt(event, lineNodes());
    if (selected) {
      lineNodePress = { index: selected.index, startX: event.clientX, startY: event.clientY, moved: false };
      view.dragging = true;
      view.lastX = event.clientX;
      view.lastY = event.clientY;
      canvas.setPointerCapture(event.pointerId);
      canvas.classList.add('dragging');
      return;
    }
  }
  const signal = signalAt(event);
  if (signal) {
    signalDrag = { id: signal.id, signal, startX: event.clientX, startY: event.clientY, moved: false };
    canvas.setPointerCapture(event.pointerId);
    canvas.classList.add('dragging');
    return;
  }
  view.dragging = true;
  view.lastX = event.clientX;
  view.lastY = event.clientY;
  canvas.setPointerCapture(event.pointerId);
  canvas.classList.add('dragging');
});
canvas.addEventListener('pointermove', event => {
  if (signalDrag) {
    const { x, z } = canvasPoint(event);
    signalOffsets[signalDrag.id] = { x: x - signalDrag.signal.x, z: z - signalDrag.signal.z };
    signalDrag.moved ||= Math.hypot(event.clientX - signalDrag.startX, event.clientY - signalDrag.startY) > 3;
    draw();
    return;
  }
  if (lineNodePress) lineNodePress.moved ||= Math.hypot(event.clientX - lineNodePress.startX, event.clientY - lineNodePress.startY) > 3;
  if (!view.dragging) return;
  view.x += event.clientX - view.lastX;
  view.y += event.clientY - view.lastY;
  view.lastX = event.clientX;
  view.lastY = event.clientY;
  view.fitted = true;
  draw();
});
canvas.addEventListener('mousemove', event => {
  if (lineMode()) {
    const hit = nodeChangeMode ? graphNodeAt(event) : nodeAt(event, lineNodes());
    canvas.style.cursor = hit ? 'pointer' : 'grab';
    return;
  }
  const signal = signalAt(event);
  const signalId = signal?.id ?? null;
  if (signalId !== hoveredSignalId) {
    hoveredSignalId = signalId;
    canvas.style.cursor = signal ? 'move' : view.dragging ? 'grabbing' : 'grab';
    draw();
  }
  const vehicle = vehicleAt(event);
  const vehicleId = vehicle?.vehicleId ?? null;
  if (vehicleId !== hoveredVehicleId) {
    hoveredVehicleId = vehicleId;
    draw();
  }
});
canvas.addEventListener('mouseleave', () => {
  if (lineMode()) {
    return;
  }
  if (hoveredVehicleId !== null || hoveredSignalId !== null) {
    hoveredVehicleId = null;
    if (document.querySelector('#signal-name-form').hidden) hoveredSignalId = null;
    draw();
  }
});
canvas.addEventListener('click', event => {
  if (sensorBinding) { selectSensorSection(event); return; }
  if (sensorAt(event)) { openSensor(sensorAt(event)); return; }
  if (lineMode()) return;
  if (signalAt(event)) return;
  const vehicle = vehicleAt(event);
  if (vehicle?.vehicleId !== null && vehicle?.vehicleId !== undefined) openVehicleDelete(vehicle.vehicleId);
});
canvas.addEventListener('pointerup', event => {
  if (!signalDrag && !lineNodePress && !view.dragging) return;
  if (signalDrag) {
    const dragged = signalDrag;
    signalDrag = null;
    saveSignalOffsets();
    canvas.releasePointerCapture(event.pointerId);
    canvas.classList.remove('dragging');
    if (!dragged.moved) openSignalName(dragged.signal);
    draw();
    return;
  }
  if (lineNodePress) {
    const pressed = lineNodePress;
    lineNodePress = null;
    view.dragging = false;
    canvas.releasePointerCapture(event.pointerId);
    canvas.classList.remove('dragging');
    if (!pressed.moved) {
      selectedNodeIndex = selectedNodeIndex === pressed.index ? null : pressed.index;
      document.querySelector('#status').textContent = selectedNodeIndex === null ? 'NODE SELECTION CLEARED' : `NODE ${selectedNodeIndex} SELECTED`;
      renderPanels();
      draw();
    }
    return;
  }
  view.dragging = false;
  canvas.releasePointerCapture(event.pointerId);
  canvas.classList.remove('dragging');
});
canvas.addEventListener('wheel', event => {
  event.preventDefault();
  const point = canvasPoint(event);
  const rect = canvas.getBoundingClientRect();
  const before = { x: point.x, y: point.z };
  view.scale = Math.max(.02, Math.min(8, view.scale * (event.deltaY < 0 ? 1.12 : .89)));
  view.x = event.clientX - rect.left - before.x * view.scale;
  view.y = event.clientY - rect.top - before.y * view.scale;
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
document.querySelector('#line-toggle').addEventListener('click', async () => {
  const drawer = document.querySelector('#line-drawer');
  const opening = !drawer.classList.contains('open');
  drawer.classList.toggle('open');
  drawer.setAttribute('aria-hidden', String(!drawer.classList.contains('open')));
  if (opening) {
    try { await loadLines(); } catch (_) { document.querySelector('#status').textContent = tr('LINE SNAPSHOT UNAVAILABLE'); }
  }
});
document.querySelector('#line-refresh').addEventListener('click', async () => {
  try { await loadLines(true); } catch (_) { document.querySelector('#status').textContent = tr('LINE SNAPSHOT UNAVAILABLE'); }
});
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => dispatch(button.dataset.action)));
document.querySelector('#node-change').addEventListener('click', changeNode);
document.querySelector('#node-add').addEventListener('click', () => {
  if (!canDispatch || selectedNodeIndex === null || selectedNodeIndex >= lineNodes().length - 1) return;
  nodeChangeMode = true;
  nodeAddMode = true;
  nodeCandidate = null;
  nodePreview = null;
  document.querySelector('#status').textContent = tr('SELECT NODE TO INSERT');
  renderPanels();
  draw();
});
document.querySelector('#node-confirm').addEventListener('click', confirmNodeChange);
document.querySelector('#node-abandon').addEventListener('click', abandonNodeChange);
document.querySelector('#node-delete').addEventListener('click', deleteNode);
document.querySelector('#node-cancel').addEventListener('click', cancelNodeDraft);
document.querySelector('#node-save').addEventListener('click', saveNodeDraft);
document.querySelector('#signal-name-form').addEventListener('submit', saveSignalName);
document.querySelector('#signal-name').addEventListener('keydown', event => { if (event.key === 'Escape') document.querySelector('#signal-name-form').hidden = true; });
document.querySelector('#vehicle-delete').addEventListener('click', armVehicleDelete);
document.querySelector('#vehicle-quarantine').addEventListener('click', toggleVehicleQuarantine);
document.querySelector('#vehicle-delete-confirm').addEventListener('click', confirmVehicleDelete);
document.addEventListener('pointerdown', event => {
  const form = document.querySelector('#signal-name-form');
  if (!form.hidden && !form.contains(event.target)) {
    const clickedSignal = event.target === canvas ? signalAt(event) : null;
    if (clickedSignal?.id !== form.dataset.signalId) {
      form.hidden = true;
      hoveredSignalId = null;
      draw();
    }
  }
  const deleteForm = document.querySelector('#vehicle-delete-form');
  if (!deleteForm.hidden && !deleteForm.contains(event.target)) {
    const clickedVehicle = event.target === canvas ? vehicleAt(event) : null;
    if (clickedVehicle?.vehicleId !== deleteVehicleId) {
      closeVehicleDelete();
      draw();
    }
  }
});
document.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  document.querySelector('#signal-name-form').hidden = true;
  closeVehicleDelete();
  draw();
});
document.querySelector('#token-invalidation-close').addEventListener('click', () => {
  dismissedInvalidationReason = invalidationReason;
  document.querySelector('#token-invalidation').hidden = true;
});
new ResizeObserver(resize).observe(document.querySelector('.diagram-wrap'));
window.addEventListener('resize', resize);
resize();
refresh();
applyLanguage();
setInterval(refresh, 2000);
setInterval(draw, 420);
