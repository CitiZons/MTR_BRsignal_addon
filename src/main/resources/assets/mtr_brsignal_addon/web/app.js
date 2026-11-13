'use strict';

const storage = (() => {
  try {
    const probe = '__mtrbr_storage_probe__';
    localStorage.setItem(probe, '1');
    localStorage.removeItem(probe);
    return localStorage;
  } catch (_) {
    return { getItem: () => null, setItem: () => {}, removeItem: () => {} };
  }
})();

const LANGUAGE_NAMES = { en: 'English', 'zh-Hans': '简体中文', 'zh-Hant': '繁體中文', ja: '日本語' };
const I18N = {
  en: {
    'SKIP TO MAP': 'SKIP TO MAP', 'DISMISS': 'DISMISS', 'SIGNAL DESK': 'SIGNAL DESK', 'NIGHT MODE': 'NIGHT MODE', 'DAY MODE': 'DAY MODE', 'RAILROUTE MODE': 'RAILROUTE MODE', 'DRAWER WIDTH': 'DRAWER WIDTH', 'RIGHT DRAWER WIDTH': 'RIGHT DRAWER WIDTH', 'DIMENSION': 'DIMENSION', 'REVISION': 'REVISION',
    'OVERVIEW': 'OVERVIEW', 'SECTIONS': 'SECTIONS', 'SIGNAL FACES': 'SIGNAL FACES', 'VEHICLES': 'VEHICLES',
    'FIT': 'FIT', 'RESET': 'RESET', 'DRAG HINT': 'DRAG TO PAN · SCROLL TO ZOOM', 'SENSORS': 'SENSORS',
    'Search sensors': 'Search sensors', 'PLAYERS': 'PLAYERS', 'SETTINGS': 'SETTINGS', 'UI SCALE': 'UI SCALE',
    'REQUESTS': 'REQUESTS', 'REQUEST QUEUE': 'REQUEST QUEUE', 'LINE': 'LINE', 'REFRESH': 'REFRESH',
    'TOPOLOGY': 'TOPOLOGY', 'DEPOT PATH': 'DEPOT PATH', 'SIGNAL NAME': 'SIGNAL NAME',
    'REDSTONE SENSOR': 'REDSTONE SENSOR', 'CLOSE': 'CLOSE', 'Name': 'Name', 'Detection': 'Detection',
    'NATIVE DETECTION': 'NATIVE DETECTION', 'BR AUTHORITY': 'BR AUTHORITY', 'Output': 'Output',
    'CONTINUOUS': 'CONTINUOUS', 'PULSE': 'PULSE', 'Pulse length (ticks)': 'Pulse length (ticks)',
    'APPROACH SECTIONS': 'APPROACH SECTIONS', 'TARGETED SECTIONS': 'TARGETED SECTIONS',
    'BIND': 'BIND', 'CONFIRM': 'CONFIRM', 'UNBIND': 'UNBIND', 'CLEAR ALL': 'CLEAR ALL',
    'CANCEL': 'CANCEL', 'SAVE': 'SAVE', 'QUARANTINE': 'QUARANTINE', 'RELEASE': 'RELEASE', 'PATH EDIT': 'PATH EDIT', 'CHANGE': 'CHANGE', 'ADD': 'ADD',
    'ABANDON': 'ABANDON', 'DELETE': 'DELETE', 'INDICATIONS': 'INDICATIONS', 'TRACK': 'TRACK',
    'SIGNAL': 'SIGNAL', 'PLATFORM': 'PLATFORM', 'OCCUPIED': 'OCCUPIED', 'LOCKED': 'LOCKED',
    'RESERVED': 'RESERVED', 'STATUS': 'STATUS', 'SELECTED': 'SELECTED', 'APPROVE': 'APPROVE',
    'REVOKE': 'REVOKE', 'OVERRIDE': 'OVERRIDE', 'SHORTCUTS': 'SHORTCUTS',
    'SHORTCUT HINT': 'F FIT · R RESET · / SEARCH · ESC CLOSE', 'TOPOLOGY SNAPSHOT': 'TOPOLOGY SNAPSHOT',
    'CONNECTING': 'CONNECTING', 'LIVE SNAPSHOT': 'LIVE SNAPSHOT', 'WAITING FOR SERVER': 'WAITING FOR SERVER',
    'READ ONLY': 'READ ONLY', 'DISPATCH ENABLED': 'DISPATCH ENABLED',
    'LINE SNAPSHOT UNAVAILABLE': 'LINE SNAPSHOT UNAVAILABLE', 'UNNAMED DEPOT': 'UNNAMED DEPOT',
    'SEG': 'SEG', 'LOADED': 'LOADED', 'UNLOADED': 'UNLOADED', 'ON': 'ON', 'OFF': 'OFF',
    'SENSOR': 'SENSOR', 'NONE': 'NONE', 'SAVED': 'SAVED', 'UNSAVED': 'UNSAVED',
    'COMMAND FAILED // SERVER UNAVAILABLE': 'COMMAND FAILED // SERVER UNAVAILABLE',
    'CHECKING NODE CANDIDATE': 'CHECKING NODE CANDIDATE', 'NO GRAPH NODE SELECTED': 'NO GRAPH NODE SELECTED',
    'LINE PATH SAVED': 'LINE PATH SAVED', 'SELECT REPLACEMENT NODE': 'SELECT REPLACEMENT NODE',
    'SELECT NODE TO INSERT': 'SELECT NODE TO INSERT',
    'NODE CHANGE CONFIRMED // UNSAVED': 'NODE CHANGE CONFIRMED // UNSAVED',
    'NODE CHANGE ABANDONED': 'NODE CHANGE ABANDONED', 'NODE DELETED // UNSAVED': 'NODE DELETED // UNSAVED',
    'NODE CHANGES CANCELED': 'NODE CHANGES CANCELED',
    'NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE': 'NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE',
    'UNBIND TARGET FIRST': 'UNBIND TARGET FIRST', 'BINDING CONFIRMED / UNSAVED': 'BINDING CONFIRMED / UNSAVED',
    'SELECTING APPROACH': 'SELECTING APPROACH', 'SELECTING TARGETS': 'SELECTING TARGETS',
    'TARGETS MUST BE A SUBSET OF APPROACH': 'TARGETS MUST BE A SUBSET OF APPROACH',
    'TARGET MUST BELONG TO APPROACH': 'TARGET MUST BELONG TO APPROACH',
    'CLEAR TARGETS FIRST': 'CLEAR TARGETS FIRST',
    'PULSE LENGTH MUST BE 1-1200 TICKS': 'PULSE LENGTH MUST BE 1-1200 TICKS',
    'QUARANTINED VEHICLE REMOVED': 'QUARANTINED VEHICLE REMOVED',
    'CONFIRM REJECTED // DELETE NOT PENDING': 'CONFIRM REJECTED // DELETE NOT PENDING',
    'CONFIRM REJECTED // VEHICLE NOT FOUND': 'CONFIRM REJECTED // VEHICLE NOT FOUND',
    'LINE SNAPSHOT': 'LINE SNAPSHOT', 'SAVE FAILED': 'SAVE FAILED'
  },
  'zh-Hans': {
    'SKIP TO MAP': '跳到地图', 'DISMISS': '关闭', 'SIGNAL DESK': '信号调度台', 'NIGHT MODE': '切换夜间', 'DAY MODE': '切换白天', 'RAILROUTE MODE': '切换 Rail Route', 'DRAWER WIDTH': '侧栏宽度', 'RIGHT DRAWER WIDTH': '右栏宽度', 'DIMENSION': '维度', 'REVISION': '修订',
    'OVERVIEW': '总览', 'SECTIONS': '区段', 'SIGNAL FACES': '信号面', 'VEHICLES': '列车',
    'FIT': '适应', 'RESET': '重置', 'DRAG HINT': '拖拽平移 · 滚轮缩放', 'SENSORS': '传感器',
    'Search sensors': '搜索传感器', 'PLAYERS': '玩家', 'SETTINGS': '设置', 'UI SCALE': '界面缩放',
    'REQUESTS': '请求', 'REQUEST QUEUE': '请求队列', 'LINE': '线路', 'REFRESH': '刷新',
    'TOPOLOGY': '拓扑', 'DEPOT PATH': '车库路径', 'SIGNAL NAME': '信号名称',
    'REDSTONE SENSOR': '红石传感器', 'CLOSE': '关闭', 'Name': '名称', 'Detection': '检测方式',
    'NATIVE DETECTION': '原生检测', 'BR AUTHORITY': 'BR 授权', 'Output': '输出',
    'CONTINUOUS': '持续', 'PULSE': '脉冲', 'Pulse length (ticks)': '脉冲长度（tick）',
    'APPROACH SECTIONS': '接近区段', 'TARGETED SECTIONS': '目标区段',
    'BIND': '绑定', 'CONFIRM': '确认', 'UNBIND': '解绑', 'CLEAR ALL': '全部清除',
    'CANCEL': '取消', 'SAVE': '保存', 'QUARANTINE': '隔离', 'RELEASE': '释放隔离', 'PATH EDIT': '路径编辑', 'CHANGE': '更换', 'ADD': '添加',
    'ABANDON': '放弃', 'DELETE': '删除', 'INDICATIONS': '图例', 'TRACK': '轨道',
    'SIGNAL': '信号', 'PLATFORM': '站台', 'OCCUPIED': '占用', 'LOCKED': '锁闭',
    'RESERVED': '预留', 'STATUS': '状态', 'SELECTED': '已选', 'APPROVE': '批准',
    'REVOKE': '撤销', 'OVERRIDE': '越红灯', 'SHORTCUTS': '快捷键',
    'SHORTCUT HINT': 'F 适应 · R 重置 · / 搜索 · ESC 关闭', 'TOPOLOGY SNAPSHOT': '拓扑快照',
    'CONNECTING': '连接中', 'LIVE SNAPSHOT': '实时快照', 'WAITING FOR SERVER': '等待服务器',
    'READ ONLY': '只读', 'DISPATCH ENABLED': '调度已启用',
    'LINE SNAPSHOT UNAVAILABLE': '线路快照不可用', 'UNNAMED DEPOT': '未命名车库',
    'SEG': '段', 'LOADED': '已加载', 'UNLOADED': '未加载', 'ON': '开启', 'OFF': '关闭',
    'SENSOR': '传感器', 'NONE': '无', 'SAVED': '已保存', 'UNSAVED': '未保存',
    'COMMAND FAILED // SERVER UNAVAILABLE': '命令失败 // 服务器不可用',
    'CHECKING NODE CANDIDATE': '检查节点候选', 'NO GRAPH NODE SELECTED': '未选择轨道节点',
    'LINE PATH SAVED': '线路路径已保存', 'SELECT REPLACEMENT NODE': '选择替换节点',
    'SELECT NODE TO INSERT': '选择要插入的节点',
    'NODE CHANGE CONFIRMED // UNSAVED': '节点更换已确认 // 未保存',
    'NODE CHANGE ABANDONED': '已放弃节点更换', 'NODE DELETED // UNSAVED': '节点已删除 // 未保存',
    'NODE CHANGES CANCELED': '节点修改已取消',
    'NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE': '节点候选拒绝 // 预览不可用',
    'UNBIND TARGET FIRST': '请先解绑目标区段', 'BINDING CONFIRMED / UNSAVED': '绑定已确认 / 未保存',
    'SELECTING APPROACH': '正在选择接近区段', 'SELECTING TARGETS': '正在选择目标区段',
    'TARGETS MUST BE A SUBSET OF APPROACH': '目标区段必须属于接近区段',
    'TARGET MUST BELONG TO APPROACH': '目标必须属于接近区段',
    'CLEAR TARGETS FIRST': '请先清除目标区段',
    'PULSE LENGTH MUST BE 1-1200 TICKS': '脉冲长度必须为 1-1200 tick',
    'QUARANTINED VEHICLE REMOVED': '已移除隔离列车',
    'CONFIRM REJECTED // DELETE NOT PENDING': '确认拒绝 // 未处于待删除状态',
    'CONFIRM REJECTED // VEHICLE NOT FOUND': '确认拒绝 // 未找到列车',
    'LINE SNAPSHOT': '线路快照', 'SAVE FAILED': '保存失败'
  },
  'zh-Hant': {
    'SKIP TO MAP': '跳至地圖', 'DISMISS': '關閉', 'SIGNAL DESK': '信號調度台', 'NIGHT MODE': '切換夜間', 'DAY MODE': '切換白天', 'RAILROUTE MODE': '切換 Rail Route', 'DRAWER WIDTH': '側欄寬度', 'RIGHT DRAWER WIDTH': '右欄寬度', 'DIMENSION': '維度', 'REVISION': '修訂',
    'OVERVIEW': '總覽', 'SECTIONS': '區段', 'SIGNAL FACES': '信號面', 'VEHICLES': '列車',
    'FIT': '適應', 'RESET': '重設', 'DRAG HINT': '拖曳平移 · 滾輪縮放', 'SENSORS': '感應器',
    'Search sensors': '搜尋感應器', 'PLAYERS': '玩家', 'SETTINGS': '設定', 'UI SCALE': '介面縮放',
    'REQUESTS': '請求', 'REQUEST QUEUE': '請求佇列', 'LINE': '路線', 'REFRESH': '重新整理',
    'TOPOLOGY': '拓撲', 'DEPOT PATH': '車庫路徑', 'SIGNAL NAME': '信號名稱',
    'REDSTONE SENSOR': '紅石感應器', 'CLOSE': '關閉', 'Name': '名稱', 'Detection': '偵測方式',
    'NATIVE DETECTION': '原生偵測', 'BR AUTHORITY': 'BR 授權', 'Output': '輸出',
    'CONTINUOUS': '持續', 'PULSE': '脈衝', 'Pulse length (ticks)': '脈衝長度（tick）',
    'APPROACH SECTIONS': '接近區段', 'TARGETED SECTIONS': '目標區段',
    'BIND': '綁定', 'CONFIRM': '確認', 'UNBIND': '解除綁定', 'CLEAR ALL': '全部清除',
    'CANCEL': '取消', 'SAVE': '儲存', 'QUARANTINE': '隔離', 'RELEASE': '解除隔離', 'PATH EDIT': '路徑編輯', 'CHANGE': '更換', 'ADD': '新增',
    'ABANDON': '放棄', 'DELETE': '刪除', 'INDICATIONS': '圖例', 'TRACK': '軌道',
    'SIGNAL': '信號', 'PLATFORM': '月台', 'OCCUPIED': '佔用', 'LOCKED': '鎖閉',
    'RESERVED': '預留', 'STATUS': '狀態', 'SELECTED': '已選', 'APPROVE': '批准',
    'REVOKE': '撤銷', 'OVERRIDE': '越紅燈', 'SHORTCUTS': '快速鍵',
    'SHORTCUT HINT': 'F 適應 · R 重設 · / 搜尋 · ESC 關閉', 'TOPOLOGY SNAPSHOT': '拓撲快照',
    'CONNECTING': '連線中', 'LIVE SNAPSHOT': '即時快照', 'WAITING FOR SERVER': '等待伺服器',
    'READ ONLY': '唯讀', 'DISPATCH ENABLED': '調度已啟用',
    'LINE SNAPSHOT UNAVAILABLE': '路線快照不可用', 'UNNAMED DEPOT': '未命名車庫',
    'SEG': '段', 'LOADED': '已載入', 'UNLOADED': '未載入', 'ON': '開啟', 'OFF': '關閉',
    'SENSOR': '感應器', 'NONE': '無', 'SAVED': '已儲存', 'UNSAVED': '未儲存',
    'COMMAND FAILED // SERVER UNAVAILABLE': '命令失敗 // 伺服器不可用',
    'CHECKING NODE CANDIDATE': '檢查節點候選', 'NO GRAPH NODE SELECTED': '未選取軌道節點',
    'LINE PATH SAVED': '路線已儲存', 'SELECT REPLACEMENT NODE': '選取替換節點',
    'SELECT NODE TO INSERT': '選取要插入的節點',
    'NODE CHANGE CONFIRMED // UNSAVED': '節點更換已確認 // 未儲存',
    'NODE CHANGE ABANDONED': '已放棄節點更換', 'NODE DELETED // UNSAVED': '節點已刪除 // 未儲存',
    'NODE CHANGES CANCELED': '節點修改已取消',
    'NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE': '節點候選拒絕 // 預覽不可用',
    'UNBIND TARGET FIRST': '請先解除目標區段', 'BINDING CONFIRMED / UNSAVED': '綁定已確認 / 未儲存',
    'SELECTING APPROACH': '正在選取接近區段', 'SELECTING TARGETS': '正在選取目標區段',
    'TARGETS MUST BE A SUBSET OF APPROACH': '目標區段必須屬於接近區段',
    'TARGET MUST BELONG TO APPROACH': '目標必須屬於接近區段',
    'CLEAR TARGETS FIRST': '請先清除目標區段',
    'PULSE LENGTH MUST BE 1-1200 TICKS': '脈衝長度必須為 1-1200 tick',
    'QUARANTINED VEHICLE REMOVED': '已移除隔離列車',
    'CONFIRM REJECTED // DELETE NOT PENDING': '確認拒絕 // 未處於待刪除狀態',
    'CONFIRM REJECTED // VEHICLE NOT FOUND': '確認拒絕 // 找不到列車',
    'LINE SNAPSHOT': '路線快照', 'SAVE FAILED': '儲存失敗'
  },
  ja: {
    'SKIP TO MAP': 'マップへ移動', 'DISMISS': '閉じる', 'SIGNAL DESK': '信号指令卓', 'NIGHT MODE': '夜間へ切替', 'DAY MODE': '昼間へ切替', 'RAILROUTE MODE': 'Rail Route へ切替', 'DRAWER WIDTH': '左幅', 'RIGHT DRAWER WIDTH': '右幅', 'DIMENSION': 'ディメンション', 'REVISION': 'リビジョン',
    'OVERVIEW': '概要', 'SECTIONS': '区間', 'SIGNAL FACES': '信号面', 'VEHICLES': '車両',
    'FIT': '全体表示', 'RESET': 'リセット', 'DRAG HINT': 'ドラッグで移動 · スクロールでズーム', 'SENSORS': 'センサー',
    'Search sensors': 'センサーを検索', 'PLAYERS': 'プレイヤー', 'SETTINGS': '設定', 'UI SCALE': 'UI スケール',
    'REQUESTS': 'リクエスト', 'REQUEST QUEUE': 'リクエストキュー', 'LINE': '路線', 'REFRESH': '更新',
    'TOPOLOGY': 'トポロジー', 'DEPOT PATH': '車庫経路', 'SIGNAL NAME': '信号名',
    'REDSTONE SENSOR': 'レッドストーンセンサー', 'CLOSE': '閉じる', 'Name': '名前', 'Detection': '検知方式',
    'NATIVE DETECTION': '標準検知', 'BR AUTHORITY': 'BR 権限', 'Output': '出力',
    'CONTINUOUS': '連続', 'PULSE': 'パルス', 'Pulse length (ticks)': 'パルス長（tick）',
    'APPROACH SECTIONS': '接近区間', 'TARGETED SECTIONS': '対象区間',
    'BIND': '割り当て', 'CONFIRM': '確認', 'UNBIND': '解除', 'CLEAR ALL': 'すべて解除',
    'CANCEL': 'キャンセル', 'SAVE': '保存', 'QUARANTINE': '隔離', 'RELEASE': '隔離解除', 'PATH EDIT': '経路編集', 'CHANGE': '変更', 'ADD': '追加',
    'ABANDON': '破棄', 'DELETE': '削除', 'INDICATIONS': '凡例', 'TRACK': '線路',
    'SIGNAL': '信号', 'PLATFORM': 'ホーム', 'OCCUPIED': '占有', 'LOCKED': 'ロック',
    'RESERVED': '予約', 'STATUS': '状態', 'SELECTED': '選択中', 'APPROVE': '承認',
    'REVOKE': '取消', 'OVERRIDE': '信号無視', 'SHORTCUTS': 'ショートカット',
    'SHORTCUT HINT': 'F 全体表示 · R リセット · / 検索 · ESC 閉じる', 'TOPOLOGY SNAPSHOT': 'トポロジースナップショット',
    'CONNECTING': '接続中', 'LIVE SNAPSHOT': 'ライブスナップショット', 'WAITING FOR SERVER': 'サーバーを待機中',
    'READ ONLY': '読み取り専用', 'DISPATCH ENABLED': '運行管理有効',
    'LINE SNAPSHOT UNAVAILABLE': '路線スナップショットを取得できません', 'UNNAMED DEPOT': '名称なし車庫',
    'SEG': '区間', 'LOADED': '読み込み済み', 'UNLOADED': '未読み込み', 'ON': 'オン', 'OFF': 'オフ',
    'SENSOR': 'センサー', 'NONE': 'なし', 'SAVED': '保存しました', 'UNSAVED': '未保存',
    'COMMAND FAILED // SERVER UNAVAILABLE': 'コマンド失敗 // サーバー利用不可',
    'CHECKING NODE CANDIDATE': 'ノード候補を確認中', 'NO GRAPH NODE SELECTED': '線路ノード未選択',
    'LINE PATH SAVED': '路線を保存しました', 'SELECT REPLACEMENT NODE': '置換ノードを選択',
    'SELECT NODE TO INSERT': '挿入するノードを選択',
    'NODE CHANGE CONFIRMED // UNSAVED': 'ノード変更を確認 // 未保存',
    'NODE CHANGE ABANDONED': 'ノード変更を破棄', 'NODE DELETED // UNSAVED': 'ノードを削除 // 未保存',
    'NODE CHANGES CANCELED': 'ノード変更をキャンセル',
    'NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE': 'ノード候補拒否 // プレビュー不可',
    'UNBIND TARGET FIRST': '先に対象区間を解除してください', 'BINDING CONFIRMED / UNSAVED': '割り当て確認済み / 未保存',
    'SELECTING APPROACH': '接近区間を選択中', 'SELECTING TARGETS': '対象区間を選択中',
    'TARGETS MUST BE A SUBSET OF APPROACH': '対象区間は接近区間に含めてください',
    'TARGET MUST BELONG TO APPROACH': '対象は接近区間に含めてください',
    'CLEAR TARGETS FIRST': '対象区間を先に解除してください',
    'PULSE LENGTH MUST BE 1-1200 TICKS': 'パルス長は1-1200 tickです',
    'QUARANTINED VEHICLE REMOVED': '隔離車両を削除しました',
    'CONFIRM REJECTED // DELETE NOT PENDING': '確認拒否 // 削除待ちではありません',
    'CONFIRM REJECTED // VEHICLE NOT FOUND': '確認拒否 // 車両が見つかりません',
    'LINE SNAPSHOT': '路線スナップショット', 'SAVE FAILED': '保存失敗'
  }
};

let language = storage.getItem('mtrbr-language') || 'en';
let theme = storage.getItem('mtrbr-theme') || 'day';
function tr(value) {
  const table = I18N[language] || I18N.en;
  if (table[value]) return table[value];
  const prefix = Object.keys(table).filter(key => key.length > 3 && value.startsWith(key + ' // ')).sort((a, b) => b.length - a.length)[0];
  return prefix ? table[prefix] + value.slice(prefix.length) : value;
}

/* ── interface scale ─────────────────────────────────────── */
const uiScale = document.querySelector('#ui-scale');
const uiScaleValue = document.querySelector('#ui-scale-value');
function applyUiScale(value) {
  const clamped = Math.max(50, Math.min(200, Number(value) || 100));
  document.documentElement.style.setProperty('--ui-scale', clamped / 100);
  uiScale.value = clamped;
  uiScaleValue.textContent = `${clamped}%`;
  storage.setItem('mtrbr-ui-scale', clamped);
}
uiScale.addEventListener('input', () => applyUiScale(uiScale.value));
applyUiScale(storage.getItem('mtrbr-ui-scale') || '100');
function applyRailWidth(value) {
  const clamped = Math.max(240, Math.min(420, Number(value) || 288));
  document.documentElement.style.setProperty('--rail-w', `${clamped}px`);
  storage.setItem('mtrbr-rail-width', clamped);
}
applyRailWidth(storage.getItem('mtrbr-rail-width') || '288');
function applyInspectorWidth(value) {
  const clamped = Math.max(210, Math.min(360, Number(value) || 246));
  document.documentElement.style.setProperty('--inspector-w', `${clamped}px`);
  storage.setItem('mtrbr-inspector-width', clamped);
}
applyInspectorWidth(storage.getItem('mtrbr-inspector-width') || '246');
function resizeDrawer(side, event) {
  if (document.body.classList.contains('resizing-drawer')) return;
  event.preventDefault();
  event.stopPropagation();
  const handle = event.currentTarget;
  const startX = event.clientX;
  const rootStyles = getComputedStyle(document.documentElement);
  const startWidth = Number.parseFloat(rootStyles.getPropertyValue(side === 'left' ? '--rail-w' : '--inspector-w')) || (side === 'left' ? 288 : 246);
  const move = moveEvent => {
    const delta = moveEvent.clientX - startX;
    if (side === 'left') applyRailWidth(startWidth + delta);
    else applyInspectorWidth(startWidth - delta);
  };
  let active = true;
  const stop = stopEvent => {
    if (!active) return;
    active = false;
    document.body.classList.remove('resizing-drawer');
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', stop);
    document.removeEventListener('pointercancel', stop);
    document.removeEventListener('mousemove', move);
    document.removeEventListener('mouseup', stop);
  };
  document.body.classList.add('resizing-drawer');
  if (handle.setPointerCapture && event.pointerId !== undefined) handle.setPointerCapture(event.pointerId);
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', stop);
  document.addEventListener('pointercancel', stop);
  document.addEventListener('mousemove', move);
  document.addEventListener('mouseup', stop);
}
document.querySelector('#rail-resizer').addEventListener('pointerdown', event => resizeDrawer('left', event));
document.querySelector('#inspector-resizer').addEventListener('pointerdown', event => resizeDrawer('right', event));
document.querySelector('#rail-resizer').addEventListener('mousedown', event => resizeDrawer('left', event));
document.querySelector('#inspector-resizer').addEventListener('mousedown', event => resizeDrawer('right', event));

/* ── language ────────────────────────────────────────────── */
function applyLanguage() {
  document.documentElement.lang = language;
  document.body.dataset.language = language;
  document.querySelectorAll('#language-switcher button').forEach(button => {
    button.textContent = LANGUAGE_NAMES[button.dataset.language];
    button.classList.toggle('active', button.dataset.language === language);
  });
  document.querySelectorAll('[data-i18n]').forEach(element => {
    element.textContent = tr(element.dataset.i18n);
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(input => {
    const key = input.dataset.i18nPlaceholder;
    input.placeholder = tr(key);
    input.setAttribute('aria-label', tr(key));
  });
  const themeToggle = document.querySelector('#theme-toggle');
  themeToggle.textContent = tr(theme === 'day' ? 'NIGHT MODE' : theme === 'night' ? 'RAILROUTE MODE' : 'DAY MODE');
  themeToggle.setAttribute('aria-pressed', theme === 'day' ? 'false' : 'true');
  renderPanels();
  renderLines();
  if (typeof renderSensorList === 'function') renderSensorList();
  if (typeof renderSensorEditor === 'function') renderSensorEditor();
  draw();
}
document.querySelectorAll('#language-switcher button').forEach(button => button.addEventListener('click', () => {
  language = button.dataset.language;
  storage.setItem('mtrbr-language', language);
  location.reload();
}));
document.querySelector('#theme-toggle').addEventListener('click', () => {
  theme = theme === 'day' ? 'night' : theme === 'night' ? 'railroute' : 'day';
  storage.setItem('mtrbr-theme', theme);
  applyTheme(theme);
});

/* ── state ───────────────────────────────────────────────── */
const canvas = document.querySelector('#diagram');
const ctx = canvas.getContext('2d');
const SANS = '"Plex Sans", "SarasaSC", "Microsoft YaHei", system-ui, sans-serif';
const MONO = '"Plex Mono", "SarasaSC", ui-monospace, monospace';
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const view = { x: 0, y: 0, scale: 1, dragging: false, lastX: 0, lastY: 0, fitted: false };
let topology = null;
let state = null;
let lines = null;
const token = new URLSearchParams(window.location.search).get('token') || '';
const deviceStorageKey = 'mtrbr-web-device-id';
const deviceId = (() => {
  let value = storage.getItem(deviceStorageKey);
  if (!value) {
    value = window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    storage.setItem(deviceStorageKey, value);
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
const signalOffsets = JSON.parse(storage.getItem('mtrbr-signal-offsets') || '{}');

/* ── helpers ─────────────────────────────────────────────── */
async function api(path, options = {}) {
  const response = await fetch(path, {
    cache: 'no-store',
    ...requestOptions,
    ...options,
    headers: { ...(requestOptions.headers || {}), ...(options.headers || {}) }
  });
  if (response.status === 403) canDispatch = false;
  return response;
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
}
function setStatus(text, tone = '') {
  const element = document.querySelector('#status');
  element.textContent = tr(text);
  element.dataset.tone = tone;
}
function setConn(stateName, text) {
  document.querySelector('#conn').dataset.state = stateName;
  document.querySelector('#conn-text').textContent = tr(text);
}
let toastTimer = null;
function flash(text, tone = '') {
  const element = document.querySelector('#toast');
  element.textContent = tr(text);
  element.dataset.tone = tone;
  element.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { element.hidden = true; }, 2600);
}
function setMapTitle(key) {
  const element = document.querySelector('#map-title-text');
  element.dataset.i18n = key;
  element.textContent = tr(key);
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
  document.querySelector('#token-invalidation-close').focus();
}

/* ── geometry ────────────────────────────────────────────── */
function resize() {
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.max(1, Math.floor(rect.width * devicePixelRatio));
  canvas.height = Math.max(1, Math.floor(rect.height * devicePixelRatio));
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
  return data && lines && lines.dimensions && lines.dimensions.find(item => item.id === data.id);
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

/* ── map palette ─────────────────────────────────────────── */
const COLOR = {
  track: '#5b6b75',
  trackDim: '#3d4a52',
  occupied: '#d24a4a',
  locked: '#e8eef1',
  reserved: '#e0a33c',
  platform: '#e0a33c',
  signalStem: '#6b7d88',
  signalRing: '#05080a',
  repeater: 'rgba(90, 169, 230, .5)',
  vehicle: '#5aa9e6',
  vehicleBg: '#06090b',
  line: '#5aa9e6',
  linePlatform: '#e0a33c',
  lineBroken: '#e05252',
  node: '#9bd0f5',
  nodeSelected: '#e0a33c',
  candidateOk: '#f4e07a',
  candidateBad: '#e05252',
  highlight: '#e0a33c',
  label: '#dfe7ec',
  halo: '#06090b'
};
const ASPECT_COLOR = { GREEN: '#3ecf8e', RED: '#e05252', YELLOW: '#e0a33c', DOUBLE_YELLOW: '#f2cd6b', UNKNOWN: '#5b6b75' };const THEME_CANVAS = {
  night: { track: '#5b6b75', trackDim: '#3d4a52', occupied: '#d24a4a', locked: '#e8eef1', reserved: '#e0a33c', platform: '#e0a33c', signalStem: '#6b7d88', signalRing: '#05080a', repeater: 'rgba(90, 169, 230, .5)', vehicle: '#5aa9e6', vehicleBg: '#06090b', line: '#5aa9e6', linePlatform: '#e0a33c', lineBroken: '#e05252', node: '#9bd0f5', nodeSelected: '#e0a33c', candidateOk: '#f4e07a', candidateBad: '#e05252', highlight: '#e0a33c', label: '#dfe7ec', halo: '#06090b' },
  day: { track: '#8f8e88', trackDim: '#c6c5bf', occupied: '#f5572f', locked: '#1c1c1a', reserved: '#d4a017', platform: '#d4a017', signalStem: '#8f8e88', signalRing: '#f0efeb', repeater: 'rgba(31, 30, 28, .36)', vehicle: '#1c1c1a', vehicleBg: '#f0efeb', line: '#1c1c1a', linePlatform: '#d4a017', lineBroken: '#f5572f', node: '#6a6963', nodeSelected: '#f5572f', candidateOk: '#d4a017', candidateBad: '#f5572f', highlight: '#f5572f', label: '#1c1c1a', halo: '#f0efeb' },
  railroute: { track: '#cfcfcf', trackDim: '#767676', occupied: '#ff2523', locked: '#ffffff', reserved: '#fd7800', platform: '#fddc00', signalStem: '#767676', signalRing: '#01050f', repeater: 'rgba(12, 140, 211, .7)', vehicle: '#fddc00', vehicleBg: '#111111', line: '#fddc00', linePlatform: '#fddc00', lineBroken: '#ff2523', node: '#cfcfcf', nodeSelected: '#fddc00', candidateOk: '#42d800', candidateBad: '#ff2523', highlight: '#fddc00', label: '#ffffff', halo: '#01050f' }
};
const THEME_ASPECT = {
  night: { GREEN: '#3ecf8e', RED: '#e05252', YELLOW: '#e0a33c', DOUBLE_YELLOW: '#f2cd6b', UNKNOWN: '#5b6b75' },
  day: { GREEN: '#43593b', RED: '#f5572f', YELLOW: '#d4a017', DOUBLE_YELLOW: '#acada0', UNKNOWN: '#8f8e88' },
  railroute: { GREEN: '#42d800', RED: '#ff2523', YELLOW: '#fddc00', DOUBLE_YELLOW: '#fd7800', UNKNOWN: '#767676' }
};
function themeColor(name) { return THEME_CANVAS[theme][name]; }
function applyTheme(nextTheme) {
  theme = ['day', 'night', 'railroute'].includes(nextTheme) ? nextTheme : 'day';
  document.body.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme === 'day' ? 'light' : 'dark';
  Object.assign(COLOR, THEME_CANVAS[theme]);
  Object.assign(ASPECT_COLOR, THEME_ASPECT[theme]);
  const toggle = document.querySelector('#theme-toggle');
  if (toggle) {
    toggle.textContent = tr(theme === 'day' ? 'NIGHT MODE' : theme === 'night' ? 'RAILROUTE MODE' : 'DAY MODE');
    toggle.setAttribute('aria-pressed', theme === 'day' ? 'false' : 'true');
  }
  draw();
}

function sectionColor(section) {
  if (section && section.occupied) return COLOR.occupied;
  if (section && section.locked) return COLOR.locked;
  if (section && section.reserved) return COLOR.reserved;
  return COLOR.track;
}
function signalColor(aspect) { return ASPECT_COLOR[aspect] || ASPECT_COLOR.UNKNOWN; }
function signalOffset(signal) { return signalOffsets[signal.id] || { x: 0, z: 0 }; }
function signalPosition(signal) {
  const offset = signalOffset(signal);
  return { x: signal.x + offset.x, z: signal.z + offset.z };
}
function saveSignalOffsets() { storage.setItem('mtrbr-signal-offsets', JSON.stringify(signalOffsets)); }

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
      return { x: points[i - 1][0] + dx * ratio, z: points[i - 1][1] + dz * ratio, dx, dz };
    }
    remaining -= segments[i - 1];
  }
  const last = points.length - 1;
  return { x: points[last][0], z: points[last][1], dx: 1, dz: 0 };
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
function drawRail(points, trimDistance = .4312) {
  const visiblePoints = trimRail(points, trimDistance);
  if (visiblePoints.length < 2) return;
  ctx.beginPath();
  visiblePoints.forEach((point, index) => index ? ctx.lineTo(point[0], point[1]) : ctx.moveTo(point[0], point[1]));
  ctx.stroke();
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
function drawPlatform(platform) {
  if (theme === 'railroute') {
    ctx.save();
    ctx.strokeStyle = COLOR.platform;
    ctx.lineWidth = Math.max(.6, .75 / view.scale);
    ctx.setLineDash([.8, .55]);
    drawRail(offsetRail(platform.points, platform.side || 1, 2.6), 0);
    ctx.restore();
    return;
  }
  ctx.strokeStyle = COLOR.platform;
  ctx.lineWidth = Math.max(1.536, 1.088 / view.scale);
  drawRail(offsetRail(platform.points, platform.side || 1, 2.6), 0);
}
function railrouteIdentityColor(value) {
  const palette = ['#289a9a', '#0b5f8e', '#8d3da4', '#b02cb5', '#6c2bd9', '#d22b2b', '#c7b269', '#1a7000'];
  let hash = 0;
  for (const character of String(value || '')) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  return palette[hash % palette.length];
}
function drawRailroutePlaque(text, x, z, options = {}) {
  ctx.save();
  ctx.translate(x, z);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = options.font || `700 13px ${SANS}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const width = ctx.measureText(text).width + (options.padding || 14);
  const height = options.height || 22;
  ctx.fillStyle = options.fill || '#111111';
  ctx.fillRect(-width / 2, -height / 2, width, height);
  ctx.fillStyle = options.color || '#ffffff';
  ctx.fillText(text, 0, 0);
  ctx.restore();
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
  if (theme === 'railroute') {
    drawRailroutePlaque(platforms[0].name, labelX, labelZ, { fill: railrouteIdentityColor(platforms[0].name), height: 20, padding: 16 });
    return;
  }
  ctx.save();
  ctx.translate(labelX, labelZ);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = `600 17px ${SANS}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = COLOR.label;
  ctx.fillText(platforms[0].name, 0, 0);
  ctx.restore();
}
function drawPlatformNumber(platform) {
  if (!platform.number) return;
  const points = offsetRail(platform.points, platform.side || 1, 2.6);
  if (theme === 'railroute') {
    const inset = 7 / view.scale;
    const ends = [pointAlongRail(points, inset), pointAlongRail([...points].reverse(), inset)];
    ends.forEach(end => drawRailroutePlaque(platform.number, end[0], end[1], { fill: COLOR.platform, color: '#111111', font: `700 10px ${MONO}`, height: 16, padding: 10 }));
    return;
  }
  ctx.save();
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = `500 10px ${MONO}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#cfd8dd';
  const inset = ctx.measureText(platform.number).width / view.scale;
  const ends = [pointAlongRail(points, inset), pointAlongRail([...points].reverse(), inset)];
  for (const end of ends) ctx.fillText(platform.number, end[0] * view.scale, end[1] * view.scale);
  ctx.restore();
}
function drawVehicle(code, marker, index) {
  ctx.save();
  ctx.translate(marker.x, marker.z);
  if (Math.abs(marker.dz) > Math.abs(marker.dx)) ctx.rotate(Math.PI / 2);
  ctx.scale(1 / view.scale, 1 / view.scale);
  ctx.font = `600 12.5px ${MONO}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const width = ctx.measureText(code).width + 8;
  const y = index * -17;
  ctx.fillStyle = theme === 'railroute' ? '#111111' : COLOR.vehicleBg;
  ctx.globalAlpha = .92;
  if (theme === 'railroute') {
    ctx.beginPath();
    ctx.moveTo(-width / 2 + 4, y - 8);
    ctx.lineTo(width / 2 - 4, y - 8);
    ctx.lineTo(width / 2, y - 4);
    ctx.lineTo(width / 2, y + 4);
    ctx.lineTo(width / 2 - 4, y + 8);
    ctx.lineTo(-width / 2 + 4, y + 8);
    ctx.lineTo(-width / 2, y + 4);
    ctx.lineTo(-width / 2, y - 4);
    ctx.closePath();
    ctx.fill();
  } else {
    ctx.fillRect(-width / 2, y - 8, width, 16);
  }
  ctx.globalAlpha = 1;
  ctx.strokeStyle = theme === 'railroute' ? '#cfcfcf' : 'rgba(90, 169, 230, .55)';
  ctx.lineWidth = .8;
  ctx.strokeRect(-width / 2, y - 8, width, 16);
  ctx.fillStyle = theme === 'railroute' ? '#ffffff' : COLOR.vehicle;
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
function drawSelectedLine(line) {
  for (const segment of line.segments) {
    const color = segment.disconnected ? COLOR.lineBroken : segment.platform ? COLOR.linePlatform : COLOR.line;
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(.72, .544 / view.scale);
    drawRail(segment.points, 0);
    const marker = railMidpoint(segment.points);
    drawArrow(marker, marker.dx, marker.dz, color);
    if (segment.disconnected) {
      const start = segment.points[0];
      ctx.fillStyle = COLOR.lineBroken;
      ctx.beginPath();
      ctx.arc(start[0], start[1], Math.max(1.2, 2.8 / view.scale), 0, Math.PI * 2);
      ctx.fill();
    }
  }
}
function drawLineNodes(line) {
  const nodes = nodeDraft || line.nodes || [];
  const blinkYellow = reduceMotion || Math.floor(Date.now() / 420) % 2 === 0;
  const drawNode = (node, index, selected) => {
    const visual = lineNodePosition(line, index, node);
    ctx.save();
    ctx.fillStyle = selected ? (blinkYellow ? COLOR.nodeSelected : '#8a671b') : COLOR.node;
    ctx.strokeStyle = selected ? COLOR.nodeSelected : '#1d3846';
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
    ctx.strokeStyle = nodePreview?.ok === false ? COLOR.candidateBad : COLOR.candidateOk;
    ctx.lineWidth = Math.max(.7, 1.8 / view.scale);
    ctx.setLineDash([1, 1]);
    ctx.beginPath();
    ctx.arc(visual.x, visual.z, Math.max(2, 5 / view.scale), 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }
}

/* ── render ──────────────────────────────────────────────── */
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
  const blinkYellow = reduceMotion || Math.floor(Date.now() / 420) % 2 === 0;

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
    ctx.strokeStyle = line ? COLOR.trackDim : section?.occupied ? COLOR.occupied : sectionColor(section);
    ctx.lineWidth = Math.max(.72, .544 / view.scale);
    drawRail(rail.points);
    if (typeof sensorDraft !== 'undefined' && sensorDraft && (sensorSelection.has(rail.id) || sensorDraft.approach.includes(rail.id) || sensorDraft.targets.includes(rail.id))) {
      ctx.save();
      ctx.strokeStyle = sensorBinding && sensorSelection.has(rail.id) ? '#ffffff' : sensorDraft.targets.includes(rail.id) ? '#3ecf8e' : COLOR.highlight;
      ctx.lineWidth = Math.max(1.5, 3 / view.scale);
      drawRail(rail.points);
      ctx.restore();
    }
    if (!line && section && section.vehicles && section.vehicles.length) {
      const midpoint = railMidpoint(rail.points);
      section.vehicles.forEach((code, index) => {
        const vehicleId = section.vehicleIds?.[index] ?? currentRequests().find(entry => entry.code === code)?.vehicleId ?? null;
        vehicleMarkers.push({ code, vehicleId, marker: midpoint, index });
      });
    }
  }

  if (!line && highlightedSections.size) {
    for (const rail of data.rails) {
      if (!highlightedSections.has(rail.id)) continue;
      const section = sections.get(rail.id);
      if (section?.occupied) continue;
      ctx.strokeStyle = blinkYellow ? COLOR.highlight : COLOR.track;
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
  ctx.strokeStyle = COLOR.repeater;
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
    ctx.strokeStyle = COLOR.signalStem;
    ctx.lineWidth = .44;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(1.92, 0);
    ctx.stroke();
    ctx.fillStyle = signalColor(signalAspects[signal.id] || 'UNKNOWN');
    if (theme === 'railroute') {
      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.lineTo(2.175, -1.29);
      ctx.lineTo(2.175, 1.29);
      ctx.closePath();
      ctx.fill();
      ctx.strokeStyle = COLOR.signalRing;
      ctx.lineWidth = .18;
      ctx.stroke();
      ctx.strokeStyle = COLOR.signalStem;
      ctx.lineWidth = .32;
      ctx.beginPath();
      ctx.moveTo(2.175, 0);
      ctx.lineTo(2.75, 0);
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.arc(0, 0, .704, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = COLOR.signalRing;
      ctx.lineWidth = .18;
      ctx.stroke();
    }
    ctx.restore();
    const form = document.querySelector('#signal-name-form');
    if (signal.id === hoveredSignalId || signal.id === signalDrag?.id || (signal.id === form.dataset.signalId && !form.hidden)) {
      ctx.save();
      ctx.strokeStyle = 'rgba(107, 125, 136, .8)';
      ctx.lineWidth = .38;
      ctx.setLineDash([.6, .6]);
      ctx.beginPath();
      ctx.moveTo(position.x, position.z);
      ctx.lineTo(signal.nodeX, signal.nodeZ);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.scale(1 / view.scale, 1 / view.scale);
      ctx.font = `500 11px ${MONO}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.fillStyle = COLOR.label;
      ctx.fillText(signal.name || `${signal.signalX}, ${signal.signalY}, ${signal.signalZ}`, position.x * view.scale, position.z * view.scale - 8);
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

function currentRequests() { return (selectedState()?.requests || []).map(request => ({ ...request, vehicleId: String(request.vehicleId) })); }
function highlightedRequest() { return currentRequests().find(request => request.vehicleId === (hoveredVehicleId ?? selectedVehicleId)); }
function requestStateClass(stateName) {
  const value = String(stateName || '').toUpperCase();
  if (['ACTIVE', 'AUTHORIZED', 'APPROACHING', 'REQUESTED', 'CHECKING', 'PASSED'].includes(value)) return 'state-green';
  if (value === 'WAITING') return 'state-yellow';
  if (['DENIED', 'INVALID', 'REVOKED', 'CANCELED', 'RELEASED'].includes(value)) return 'state-red';
  if (['OVERRIDE', 'NONE'].includes(value)) return 'state-blue';
  return 'state-white';
}

function renderPanels() {
  const players = state?.players || [];
  document.querySelector('#player-list').innerHTML = players.map(player => `<div class="player"><img src="${escapeHtml(encodeURI(player.avatar))}" alt="" loading="lazy"><span class="player-name">${escapeHtml(player.name)}</span><b class="player-state ${player.dispatching ? 'on' : 'off'}">${player.dispatching ? tr('ON') : tr('OFF')}</b></div>`).join('');
  const requests = [...currentRequests()].sort((left, right) => shortCodeCompare(left.code, right.code));
  requests.forEach(request => vehicleQuarantineStates.set(request.vehicleId, request.quarantineState || 'NORMAL'));
  document.querySelector('#request-list').innerHTML = requests.map(request => `<div class="request ${request.vehicleId === selectedVehicleId ? 'selected' : ''}" role="button" tabindex="0" data-vehicle-id="${escapeHtml(request.vehicleId)}"><span class="request-code">${escapeHtml(request.code)}</span><span class="request-state ${requestStateClass(request.state)}">${escapeHtml(request.state)}</span><span class="request-detail">R: ${escapeHtml(request.route || '--')} | N: ${escapeHtml(request.next || '--')} | D: ${escapeHtml(request.destination || '--')}</span></div>`).join('');
  document.querySelectorAll('.request').forEach(row => {
    row.addEventListener('click', () => openVehicleDelete(row.dataset.vehicleId));
    row.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openVehicleDelete(row.dataset.vehicleId); }
    });
  });
  const selected = requests.find(request => request.vehicleId === selectedVehicleId);
  const actions = document.querySelector('#action-drawer');
  actions.hidden = !(canDispatch && selected);
  document.querySelectorAll('[data-action]').forEach(button => { button.disabled = !canDispatch; });
  document.querySelector('#action-code').textContent = selected?.code || '--';
  document.querySelector('#request-count').textContent = requests.length;
  document.querySelector('#vehicle-count').textContent = requests.length;
  const mode = document.querySelector('#mode');
  mode.textContent = tr(canDispatch ? 'DISPATCH ENABLED' : 'READ ONLY');
  mode.dataset.state = canDispatch ? 'dispatch' : 'readonly';
  renderLineEditPrompt();
}

function renderLineEditPrompt() {
  const prompt = document.querySelector('#line-edit-prompt');
  const line = selectedLine();
  if (!line || selectedNodeIndex === null) prompt.hidden = true;
  else prompt.hidden = false;
  const nodeCount = line ? lineNodes(line).length : 0;
  const internal = selectedNodeIndex !== null && selectedNodeIndex > 0 && selectedNodeIndex < nodeCount - 1;
  document.querySelector('#node-info').textContent = line && selectedNodeIndex !== null
    ? `NODE ${selectedNodeIndex} / ${nodeCount - 1}${nodeChangeMode ? (nodeAddMode ? ' · INSERT' : ' · REPLACE') : ''}`
    : '';
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
  document.querySelector('#line-list').innerHTML = entries.length
    ? entries.map(line => `<div class="line-row ${line.id === selectedLineId ? 'selected' : ''}" role="button" tabindex="0" data-line-id="${escapeHtml(line.id)}"><span class="line-name">${escapeHtml(line.name || tr('UNNAMED DEPOT'))}</span><span class="line-count">${line.segments.length} ${tr('SEG')}</span><span class="line-id">${escapeHtml(line.id)}</span></div>`).join('')
    : `<p class="bind-empty">${escapeHtml(tr('NONE'))}</p>`;
  document.querySelectorAll('.line-row').forEach(row => {
    row.addEventListener('click', () => selectLine(row.dataset.lineId));
    row.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectLine(row.dataset.lineId); }
    });
  });
  document.querySelector('#line-count').textContent = entries.length;
}

/* ── vehicles ────────────────────────────────────────────── */
function selectLine(lineId) {
  selectedLineId = selectedLineId === lineId ? null : lineId;
  selectedVehicleId = null;
  hoveredVehicleId = null;
  closeDrawer('vehicle');
  const line = selectedLine();
  nodeDraft = null;
  selectedNodeIndex = null;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.body.classList.toggle('line-mode', !!line);
  setMapTitle(line ? 'DEPOT PATH' : 'TOPOLOGY');
  setStatus(line ? `LINE // ${line.name || line.id}` : 'LIVE SNAPSHOT', line ? '' : 'ok');
  renderLines();
  renderPanels();
  draw();
  fit();
}
function exitLineMode() {
  if (selectedLineId === null) return;
  selectedLineId = null;
  nodeDraft = null;
  selectedNodeIndex = null;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  document.body.classList.remove('line-mode');
  setMapTitle('TOPOLOGY');
  renderLines();
}

async function loadLines(force = false) {
  if (lines && !force) return;
  const response = await api('api/lines');
  if (!response.ok) throw new Error('line snapshot unavailable');
  lines = await response.json();
  if (selectedLineId && !selectedLine()) {
    selectedLineId = null;
    document.body.classList.remove('line-mode');
    setMapTitle('TOPOLOGY');
  }
  renderLines();
  draw();
}

function selectVehicle(vehicleId) {
  vehicleId = String(vehicleId ?? '');
  const deselected = selectedVehicleId === vehicleId;
  selectedVehicleId = deselected ? null : vehicleId;
  if (!deselected) {
    openDrawer('vehicle');
    focusVehicle(vehicleId);
  }
  renderPanels();
  draw();
}
function vehicleMarker(vehicleId) { return vehicleMarkers.find(marker => marker.vehicleId === vehicleId) || null; }
function vehicleLabelPosition(vehicle) {
  const vertical = Math.abs(vehicle.marker.dz) > Math.abs(vehicle.marker.dx);
  const offset = vehicle.index * -17;
  return {
    x: view.x + vehicle.marker.x * view.scale + (vertical ? -offset : 0),
    z: view.y + vehicle.marker.z * view.scale + (vertical ? 0 : offset)
  };
}
function positionVehicleDeleteForm() {
  const form = document.querySelector('#vehicle-delete-form');
  if (form.hidden || deleteVehicleId === null) return;
  const marker = vehicleMarker(deleteVehicleId);
  if (!marker) { closeVehicleDelete(); return; }
  const position = vehicleLabelPosition(marker);
  form.style.left = `${position.x}px`;
  form.style.top = `${position.z - 14}px`;
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
  form.hidden = false;
  openVehicleDeleteControls(vehicleId);
  positionVehicleDeleteForm();
  draw();
}
function openVehicleDeleteControls(vehicleId) {
  const quarantineState = vehicleQuarantineStates.get(vehicleId) || 'NORMAL';
  const quarantine = document.querySelector('#vehicle-quarantine');
  const deleteButton = document.querySelector('#vehicle-delete');
  const confirm = document.querySelector('#vehicle-delete-confirm');
  quarantine.textContent = quarantineState === 'QUARANTINED' || quarantineState === 'DELETE_PENDING' ? tr('RELEASE') : tr('QUARANTINE');
  deleteButton.textContent = quarantineState === 'DELETE_PENDING' ? tr('CANCEL') : tr('DELETE');
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
    const response = await api('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, vehicleId }) });
    if (!response.ok) {
      setStatus(failureText, 'error');
      openVehicleDeleteControls(vehicleId);
      return;
    }
    if (nextState) vehicleQuarantineStates.set(vehicleId, nextState);
    setStatus(successText, 'ok');
    openVehicleDeleteControls(vehicleId);
  } catch (_) {
    setStatus(`${action.toUpperCase()} FAILED // SERVER UNAVAILABLE`, 'error');
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
    setStatus('CONFIRM REJECTED // DELETE NOT PENDING', 'error');
    return;
  }
  const vehicleId = deleteVehicleId;
  try {
    const response = await api('api/commands', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'confirm_quarantine_removal', vehicleId })
    });
    if (!response.ok) {
      setStatus('CONFIRM REJECTED // VEHICLE NOT FOUND', 'error');
      return;
    }
    if (selectedVehicleId === vehicleId) selectedVehicleId = null;
    closeVehicleDelete();
    setStatus('QUARANTINED VEHICLE REMOVED', 'ok');
    await refresh();
  } catch (_) {
    setStatus('COMMAND FAILED // SERVER UNAVAILABLE', 'error');
    openVehicleDeleteControls(vehicleId);
  }
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

/* ── signals ─────────────────────────────────────────────── */
function signalAt(event) {
  if (lineMode()) return null;
  const data = selectedDimension();
  if (!data) return null;
  const point = canvasPoint(event);
  const tolerance = 10 / view.scale;
  return data.signals.find(signal => {
    const position = signalPosition(signal);
    return Math.hypot(position.x - point.x, position.z - point.z) <= tolerance;
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
  form.style.top = `${view.y + position.z * view.scale - 26}px`;
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
  try {
    const response = await api('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'name_signal', signalId: form.dataset.signalId, name }) });
    if (response.ok) {
      form.hidden = true;
      await refresh();
    } else {
      setStatus('COMMAND REJECTED', 'error');
    }
  } catch (_) {
    setStatus('COMMAND FAILED // SERVER UNAVAILABLE', 'error');
  }
}
async function dispatch(action) {
  if (!canDispatch || selectedVehicleId === null) return;
  try {
    const response = await api('api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, vehicleId: String(selectedVehicleId) }) });
    if (!response.ok) {
      let reason = 'COMMAND REJECTED';
      try { reason = `COMMAND REJECTED // ${(await response.json()).reason || response.status}`; } catch (_) { reason += ` // ${response.status}`; }
      setStatus(reason, 'error');
      return;
    }
    setStatus(`${action.toUpperCase()} SUBMITTED`, 'ok');
    flash(`${action.toUpperCase()} SUBMITTED`, 'ok');
    await refresh();
  } catch (_) {
    setStatus('COMMAND FAILED // SERVER UNAVAILABLE', 'error');
  }
}

/* ── line node editing ───────────────────────────────────── */
function lineNodes(line = selectedLine()) { return nodeDraft || line?.nodes || []; }
function lineNodePosition(line, index, node) { return { x: node.displayX ?? node.x, z: node.displayZ ?? node.z }; }
function sameNode(left, right) { return !!left && !!right && left.x === right.x && left.y === right.y && left.z === right.z; }
function repeatedNodePair(nodes) {
  for (let index = 1; index < nodes.length; index++) if (sameNode(nodes[index - 1], nodes[index])) return [index - 1, index];
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
    setStatus(`NODE CANDIDATE REJECTED // ${previewFailureText(nodePreview)}`, 'error');
    renderPanels();
    draw();
    return;
  }
  const serial = ++nodePreviewSerial;
  nodePreview = null;
  setStatus('CHECKING NODE CANDIDATE', 'warn');
  renderPanels();
  draw();
  try {
    const response = await api('api/lines/preview-nodes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dimension: dimension.id, depotId: line.id, fingerprint: line.fingerprint, nodes: draft })
    });
    const result = await response.json();
    if (serial !== nodePreviewSerial) return;
    nodePreview = result;
    setStatus(result.ok ? 'NODE CANDIDATE VALID // CONFIRM TO APPLY' : `NODE CANDIDATE REJECTED // ${previewFailureText(result)}`, result.ok ? 'ok' : 'error');
  } catch (_) {
    if (serial !== nodePreviewSerial) return;
    nodePreview = { ok: false, error: 'PREVIEW_UNAVAILABLE' };
    setStatus('NODE CANDIDATE REJECTED // PREVIEW UNAVAILABLE', 'error');
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
function changeNode() {
  if (!canDispatch || selectedNodeIndex === null) return;
  nodeChangeMode = true;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  setStatus('SELECT REPLACEMENT NODE', 'warn');
  renderPanels();
  draw();
}
function confirmNodeChange() {
  if (!nodeChangeMode || !nodeCandidate || !nodePreview?.ok || selectedNodeIndex === null) return;
  const draft = candidateDraft();
  const repeated = repeatedNodePair(draft);
  if (repeated) {
    setStatus(`NODE CHANGE REJECTED // REPEATED NODE ${repeated[0]}-${repeated[1]}`, 'error');
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
  setStatus('NODE CHANGE CONFIRMED // UNSAVED', 'warn');
  renderPanels();
  draw();
}
function abandonNodeChange() {
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  nodePreview = null;
  nodePreviewSerial++;
  setStatus('NODE CHANGE ABANDONED', '');
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
  setStatus('NODE DELETED // UNSAVED', 'warn');
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
  setStatus('NODE CHANGES CANCELED', '');
  renderPanels();
  draw();
}
async function saveNodeDraft() {
  const line = selectedLine();
  const dimension = selectedDimension();
  if (!canDispatch || !line || !dimension || !nodeDraft) return;
  const repeated = repeatedNodePair(nodeDraft);
  if (repeated) {
    setStatus(`LINE SAVE REJECTED // REPEATED NODE ${repeated[0]}-${repeated[1]}`, 'error');
    return;
  }
  try {
    const response = await api('api/lines/save-nodes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dimension: dimension.id, depotId: line.id, fingerprint: line.fingerprint, nodes: nodeDraft })
    });
    const result = await response.json();
    if (!response.ok || !result.ok) {
      setStatus(`LINE SAVE FAILED // ${result.error || 'UNKNOWN'}`, 'error');
      return;
    }
    line.segments = result.segments;
    line.nodes = result.nodes;
    line.fingerprint = result.fingerprint;
  } catch (_) {
    setStatus('LINE SAVE FAILED // SERVER UNAVAILABLE', 'error');
    return;
  }
  nodeDraft = null;
  nodeChangeMode = false;
  nodeAddMode = false;
  nodeCandidate = null;
  selectedNodeIndex = null;
  setStatus('LINE PATH SAVED', 'ok');
  renderLines();
  renderPanels();
  fit();
}

/* ── refresh ─────────────────────────────────────────────── */
async function refresh() {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    const [topologyResponse, stateResponse, sessionResponse] = await Promise.all([
      api('api/topology'),
      api('api/state'),
      api('api/session')
    ]);
    if (!topologyResponse.ok || !stateResponse.ok) throw new Error('snapshot unavailable');
    topology = await topologyResponse.json();
    state = await stateResponse.json();
    const session = sessionResponse.ok ? await sessionResponse.json() : { canDispatch: false, invalidationReason: '' };
    canDispatch = session.canDispatch === true;
    if (typeof refreshSensors === 'function') await refreshSensors();
    updateInvalidation(session.invalidationReason);
    setConn('live', 'LIVE');
    const data = selectedDimension();
    if (!lineMode()) setStatus('LIVE SNAPSHOT', 'ok');
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
    setConn('error', 'OFFLINE');
    setStatus('WAITING FOR SERVER', 'error');
  } finally {
    refreshInFlight = false;
  }
}

/* ── drawers ─────────────────────────────────────────────── */
function openDrawer(kind) {
  const drawer = document.querySelector(`#${kind}-drawer`);
  const other = document.querySelector(kind === 'vehicle' ? '#line-drawer' : '#vehicle-drawer');
  const toggle = document.querySelector(`#${kind}-toggle`);
  const otherToggle = document.querySelector(kind === 'vehicle' ? '#line-toggle' : '#vehicle-toggle');
  other.classList.remove('open');
  other.setAttribute('aria-hidden', 'true');
  otherToggle.setAttribute('aria-expanded', 'false');
  drawer.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
  toggle.setAttribute('aria-expanded', 'true');
}
function closeDrawer(kind) {
  const drawer = document.querySelector(`#${kind}-drawer`);
  drawer.classList.remove('open');
  drawer.setAttribute('aria-hidden', 'true');
  document.querySelector(`#${kind}-toggle`).setAttribute('aria-expanded', 'false');
}
function toggleDrawer(kind) {
  const drawer = document.querySelector(`#${kind}-drawer`);
  if (drawer.classList.contains('open')) closeDrawer(kind);
  else openDrawer(kind);
}

/* ── input ───────────────────────────────────────────────── */
canvas.addEventListener('pointerdown', event => {
  if (event.button === 2) return;
  if (typeof sensorBinding !== 'undefined' && sensorBinding && event.button === 0 && sensorSectionAt(event)) return;
  if (lineMode()) {
    if (nodeChangeMode) {
      nodeCandidate = graphNodeAt(event);
      nodePreview = null;
      if (nodeCandidate) previewNodeCandidate();
      else setStatus('NO GRAPH NODE SELECTED', 'warn');
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
  if (lineMode()) return;
  if (hoveredVehicleId !== null || hoveredSignalId !== null) {
    hoveredVehicleId = null;
    if (document.querySelector('#signal-name-form').hidden) hoveredSignalId = null;
    draw();
  }
});
canvas.addEventListener('click', event => {
  if (typeof sensorBinding !== 'undefined' && sensorBinding) { selectSensorSection(event); return; }
  if (typeof sensorAt === 'function') {
    const sensor = sensorAt(event);
    if (sensor) { openSensor(sensor); return; }
  }
  if (lineMode()) return;
  if (signalAt(event)) return;
  const vehicle = vehicleAt(event);
  if (vehicle && vehicle.vehicleId !== null && vehicle.vehicleId !== undefined) openVehicleDelete(vehicle.vehicleId);
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
      setStatus(selectedNodeIndex === null ? 'NODE SELECTION CLEARED' : `NODE ${selectedNodeIndex} SELECTED`, '');
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
  view.scale = Math.max(.02, Math.min(8, view.scale * (event.deltaY < 0 ? 1.12 : .89)));
  view.x = event.clientX - rect.left - point.x * view.scale;
  view.y = event.clientY - rect.top - point.z * view.scale;
  view.fitted = true;
  draw();
}, { passive: false });

/* ── buttons ─────────────────────────────────────────────── */
document.querySelector('#fit').addEventListener('click', fit);
document.querySelector('#reset').addEventListener('click', () => { view.fitted = false; fit(); });
document.querySelector('#vehicle-toggle').addEventListener('click', () => toggleDrawer('vehicle'));
document.querySelector('#line-toggle').addEventListener('click', async () => {
  const opening = !document.querySelector('#line-drawer').classList.contains('open');
  toggleDrawer('line');
  if (opening) {
    try { await loadLines(); } catch (_) { setStatus('LINE SNAPSHOT UNAVAILABLE', 'error'); }
  }
});
document.querySelector('#line-refresh').addEventListener('click', async () => {
  try { await loadLines(true); setStatus('LINE SNAPSHOT', 'ok'); } catch (_) { setStatus('LINE SNAPSHOT UNAVAILABLE', 'error'); }
});
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => dispatch(button.dataset.action)));
document.querySelector('#node-change').addEventListener('click', changeNode);
document.querySelector('#node-add').addEventListener('click', () => {
  if (!canDispatch || selectedNodeIndex === null || selectedNodeIndex >= lineNodes().length - 1) return;
  nodeChangeMode = true;
  nodeAddMode = true;
  nodeCandidate = null;
  nodePreview = null;
  setStatus('SELECT NODE TO INSERT', 'warn');
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
  const target = event.target;
  const typing = target instanceof HTMLElement && (/^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName) || target.isContentEditable);
  if (event.key === 'Escape') {
    document.querySelector('#signal-name-form').hidden = true;
    closeVehicleDelete();
    if (typing) target.blur();
    draw();
    return;
  }
  if (typing || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.key === 'f' || event.key === 'F') { event.preventDefault(); fit(); }
  else if (event.key === 'r' || event.key === 'R') { event.preventDefault(); view.fitted = false; fit(); }
  else if (event.key === '/') { event.preventDefault(); document.querySelector('#sensor-search').focus(); }
});

document.querySelector('#token-invalidation-close').addEventListener('click', () => {
  dismissedInvalidationReason = invalidationReason;
  document.querySelector('#token-invalidation').hidden = true;
});

new ResizeObserver(resize).observe(document.querySelector('.stage'));
window.addEventListener('resize', resize);

function syncRailrouteHud() {
  const pairs = [
    ['#rr-sections', '#rail-count'],
    ['#rr-signals', '#signal-count'],
    ['#rr-vehicles', '#vehicle-count'],
    ['#rr-clock', '#updated']
  ];
  pairs.forEach(([mirror, source]) => {
    const target = document.querySelector(mirror);
    const value = document.querySelector(source);
    if (target && value) target.textContent = value.textContent;
  });
}

resize();
applyTheme(theme);
refresh();
applyLanguage();
syncRailrouteHud();
setInterval(refresh, 2000);
setInterval(draw, 420);
setInterval(syncRailrouteHud, 500);
