/**
 * SERVER STATE (in-memory) — all authoritative game data lives here.
 *
 * phase: 'lobby' | 'placement' | 'playing' | 'ended'
 *
 * players: Map<playerId, {
 *   id, name,
 *   ws: WebSocket | null,
 *   disconnectedAt: number | null,
 *   disconnectTimer: Timeout | null,
 *   ships: Array<{ name: string, cells: string[] }> | null,
 *   placementDone: boolean,
 *   eliminated: boolean
 * }>
 *
 * turnOrder: string[]        — player ids at game start (fixed order)
 * currentTurnIndex: number   — index into turnOrder for round-robin
 *
 * shots: Map<string, Map<cell, 'hit'|'miss'>>
 *   — key `${shooterId}::${targetId}`: cells this shooter has fired at on that target
 *
 * boardHits: Map<playerId, Set<cell>>
 *   — defender cells that were hits (any shooter)
 *
 * boardMisses: Map<playerId, Set<cell>>
 *   — defender cells that were misses (any shooter)
 *
 * winnerId: string | null
 * standings: Array<{ id, name, rank, eliminated }> | null
 *
 * Client receives only what each role should see; ship coordinates never leak to non-owners.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const MIN_PLAYERS = 3;
const MAX_PLAYERS = 4;
const RECONNECT_MS = 30_000;
const ROWS = 'ABCDEFGHIJ';
const COLS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

const FLEET = [
  { name: 'Carrier', len: 5 },
  { name: 'Battleship', len: 4 },
  { name: 'Cruiser', len: 3 },
  { name: 'Submarine', len: 3 },
  { name: 'Destroyer', len: 2 },
];

function parseArgs(argv) {
  let port = 3000;
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--port' && argv[i + 1]) {
      port = parseInt(argv[i + 1], 10);
      i++;
    }
  }
  if (!Number.isFinite(port) || port < 1 || port > 65535) port = 3000;
  return { port };
}

function randomId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function allCells() {
  const out = [];
  for (const r of ROWS) for (const c of COLS) out.push(`${r}${c}`);
  return out;
}

const ALL_CELLS = new Set(allCells());

function parseCell(cell) {
  if (typeof cell !== 'string' || cell.length < 2) return null;
  const row = cell[0].toUpperCase();
  const col = parseInt(cell.slice(1), 10);
  if (!ROWS.includes(row) || !COLS.includes(col)) return null;
  return { row, col, key: `${row}${col}` };
}

function shotKey(shooter, target) {
  return `${shooter}::${target}`;
}

function createPlayer(id, name) {
  return {
    id,
    name,
    ws: null,
    disconnectedAt: null,
    disconnectTimer: null,
    ships: null,
    placementDone: false,
    eliminated: false,
  };
}

let phase = 'lobby';
/** @type {Map<string, ReturnType<typeof createPlayer>>} */
const players = new Map();
/** @type {NodeJS.Timeout | null} */
let lobbyTimer = null;
const LOBBY_DEBOUNCE_MS = 2000;
let turnOrder = [];
let currentTurnIndex = 0;
/** @type {Map<string, Map<string, 'hit'|'miss'>>} */
const shots = new Map();
/** @type {Map<string, Set<string>>} */
const boardHits = new Map();
/** @type {Map<string, Set<string>>} */
const boardMisses = new Map();
let winnerId = null;
let standings = null;

function clearLobbyTimer() {
  if (lobbyTimer) {
    clearTimeout(lobbyTimer);
    lobbyTimer = null;
  }
}

function isDisconnected(p) {
  return p.ws == null || p.ws.readyState !== 1; // 1 = OPEN
}

function broadcast(fn) {
  for (const p of players.values()) {
    if (p.ws && p.ws.readyState === 1) fn(p);
  }
}

function send(ws, obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}

function sendError(ws, message) {
  send(ws, { type: 'error', message });
}

function lobbyPayload() {
  return {
    type: 'lobby_update',
    phase,
    players: [...players.values()].map((p) => ({
      id: p.id,
      name: p.name,
      ready: p.placementDone,
    })),
    waiting: phase === 'lobby',
  };
}

function broadcastLobby() {
  const payload = lobbyPayload();
  broadcast((p) => send(p.ws, payload));
}

function ensureBoardHits(pid) {
  if (!boardHits.has(pid)) boardHits.set(pid, new Set());
  return boardHits.get(pid);
}

function ensureBoardMisses(pid) {
  if (!boardMisses.has(pid)) boardMisses.set(pid, new Set());
  return boardMisses.get(pid);
}

function shipCellsSet(player) {
  const s = new Set();
  if (!player.ships) return s;
  for (const sh of player.ships) for (const c of sh.cells) s.add(c);
  return s;
}

function cellToShipName(player, cell) {
  if (!player.ships) return null;
  for (const sh of player.ships) if (sh.cells.includes(cell)) return sh.name;
  return null;
}

function isShipFullyHit(player, shipName) {
  const sh = player.ships?.find((x) => x.name === shipName);
  if (!sh) return false;
  const hits = ensureBoardHits(player.id);
  return sh.cells.every((c) => hits.has(c));
}

function allShipsSunk(player) {
  if (!player.ships) return true;
  const hits = ensureBoardHits(player.id);
  return shipCellsSet(player).size > 0 && [...shipCellsSet(player)].every((c) => hits.has(c));
}

function advanceTurnFrom(fromIndex) {
  const n = turnOrder.length;
  if (n === 0) return;
  for (let k = 0; k < n; k++) {
    const i = (fromIndex + 1 + k) % n;
    const pid = turnOrder[i];
    const p = players.get(pid);
    if (p && !p.eliminated && !isDisconnected(p)) {
      currentTurnIndex = i;
      notifyTurn();
      return;
    }
  }
  checkGameOver();
}

function notifyTurn() {
  const pid = turnOrder[currentTurnIndex];
  const p = players.get(pid);
  if (!p || p.eliminated || isDisconnected(p)) {
    advanceTurnFrom(currentTurnIndex);
    return;
  }
  broadcast((pl) => {
    send(pl.ws, { type: 'your_turn', playerId: pid });
  });
}

function checkGameOver() {
  if (phase === 'ended') return;
  const alive = [...players.values()].filter((p) => !p.eliminated);
  if (alive.length > 1) return;
  phase = 'ended';
  const winner = alive[0] || null;
  winnerId = winner?.id ?? null;
  const losers = [...players.values()]
    .filter((p) => p.eliminated)
    .sort((a, b) => a.name.localeCompare(b.name));
  standings = [];
  if (winner) standings.push({ id: winner.id, name: winner.name, rank: 1, eliminated: false });
  let r = 2;
  for (const p of losers) {
    standings.push({ id: p.id, name: p.name, rank: r++, eliminated: true });
  }
  const w = winnerId ? players.get(winnerId) : null;
  broadcast((p) => {
    send(p.ws, {
      type: 'game_over',
      winner: w ? { id: w.id, name: w.name } : null,
      standings,
    });
  });
}

function eliminatePlayer(playerId, reason) {
  const p = players.get(playerId);
  if (!p || p.eliminated) return;
  p.eliminated = true;
  broadcast((pl) => {
    send(pl.ws, { type: 'player_eliminated', playerId });
  });
  checkGameOver();
  if (phase !== 'playing') return;
  const currentId = turnOrder[currentTurnIndex];
  if (currentId === playerId || players.get(currentId)?.eliminated || isDisconnected(players.get(currentId))) {
    advanceTurnFrom(currentTurnIndex);
  }
}

function scheduleDisconnectElimination(p) {
  if (p.disconnectTimer) clearTimeout(p.disconnectTimer);
  p.disconnectTimer = setTimeout(() => {
    p.disconnectTimer = null;
    if (phase !== 'playing' && phase !== 'placement') return;
    if (p.ws && p.ws.readyState === 1) return;
    p.disconnectedAt = null;
    if (phase === 'playing') eliminatePlayer(p.id, 'timeout');
    else {
      players.delete(p.id);
      if (players.size < MIN_PLAYERS && phase === 'placement') {
        clearLobbyTimer();
        phase = 'lobby';
        for (const pl of players.values()) {
          pl.placementDone = false;
          pl.ships = null;
        }
      }
      broadcastLobby();
    }
  }, RECONNECT_MS);
}

function clearDisconnectTimer(p) {
  if (p.disconnectTimer) {
    clearTimeout(p.disconnectTimer);
    p.disconnectTimer = null;
  }
  p.disconnectedAt = null;
}

function validatePlacement(shipsPayload) {
  if (!Array.isArray(shipsPayload) || shipsPayload.length !== FLEET.length) return 'Wrong number of ships.';
  const seenNames = new Set();
  for (const s of shipsPayload) {
    if (!s || typeof s.name !== 'string' || seenNames.has(s.name)) return 'Each ship name must appear once.';
    seenNames.add(s.name);
  }
  const used = new Set();
  for (const spec of FLEET) {
    const s = shipsPayload.find((x) => x && x.name === spec.name);
    if (!s || !Array.isArray(s.cells)) return `Missing or invalid ${spec.name}.`;
    if (s.cells.length !== spec.len) return `${spec.name} must span ${spec.len} cells.`;
    const parsed = s.cells.map(parseCell);
    if (parsed.some((x) => !x || !ALL_CELLS.has(x.key))) return `${spec.name} has invalid cells.`;
    const keys = s.cells.map((c) => parseCell(c).key);
    for (const k of keys) {
      if (used.has(k)) return 'Overlapping ships.';
      used.add(k);
    }
    const rows = keys.map((k) => k[0]);
    const cols = keys.map((k) => parseInt(k.slice(1), 10));
    const sameRow = rows.every((r) => r === rows[0]);
    const sameCol = cols.every((c) => c === cols[0]);
    if (!sameRow && !sameCol) return `${spec.name} must be straight horizontal or vertical.`;
    if (sameRow) {
      cols.sort((a, b) => a - b);
      for (let i = 1; i < cols.length; i++) if (cols[i] !== cols[i - 1] + 1) return `${spec.name} must be contiguous.`;
    } else {
      const ord = rows.map((r) => ROWS.indexOf(r)).sort((a, b) => a - b);
      for (let i = 1; i < ord.length; i++) if (ord[i] !== ord[i - 1] + 1) return `${spec.name} must be contiguous.`;
    }
  }
  return null;
}

function beginPlacement() {
  clearLobbyTimer();
  if (phase !== 'lobby') return;
  if (players.size < MIN_PLAYERS || players.size > MAX_PLAYERS) return;
  phase = 'placement';
  for (const p of players.values()) {
    p.placementDone = false;
    p.ships = null;
  }
  broadcastLobby();
}

function scheduleLobbyStart() {
  if (phase !== 'lobby') return;
  if (players.size < MIN_PLAYERS) return;
  if (players.size >= MAX_PLAYERS) {
    beginPlacement();
    return;
  }
  clearLobbyTimer();
  lobbyTimer = setTimeout(() => {
    lobbyTimer = null;
    if (phase === 'lobby' && players.size >= MIN_PLAYERS) beginPlacement();
  }, LOBBY_DEBOUNCE_MS);
}

function maybeStartPlaying() {
  if (phase !== 'placement') return;
  if (![...players.values()].every((p) => p.placementDone && p.ships)) return;
  phase = 'playing';
  shots.clear();
  boardHits.clear();
  boardMisses.clear();
  for (const p of players.values()) {
    ensureBoardHits(p.id);
    ensureBoardMisses(p.id);
  }
  turnOrder = [...players.keys()];
  currentTurnIndex = 0;
  while (currentTurnIndex < turnOrder.length) {
    const pid = turnOrder[currentTurnIndex];
    const p = players.get(pid);
    if (!p.eliminated && !isDisconnected(p)) break;
    currentTurnIndex++;
  }
  for (const p of players.values()) {
    send(p.ws, {
      type: 'game_start',
      players: [...players.values()].map((x) => ({ id: x.id, name: x.name })),
      turnOrder: [...turnOrder],
      you: buildPrivateState(p),
    });
  }
  notifyTurn();
}

function buildPrivateState(forPlayer) {
  const pid = forPlayer.id;
  const ownHits = ensureBoardHits(pid);
  const ownMisses = ensureBoardMisses(pid);
  return {
    ownShips: forPlayer.ships,
    ownBoard: {
      hits: [...ownHits],
      misses: [...ownMisses],
    },
    attacks: [...players.values()]
      .filter((o) => o.id !== pid && !o.eliminated)
      .map((o) => {
        const key = shotKey(pid, o.id);
        const m = shots.get(key) || new Map();
        return {
          targetId: o.id,
          targetName: o.name,
          cells: [...m.entries()].map(([cell, result]) => ({ cell, result })),
        };
      }),
    livingOpponents: [...players.values()]
      .filter((o) => o.id !== pid && !o.eliminated)
      .map((o) => ({ id: o.id, name: o.name })),
    shipCounts: [...players.values()].map((o) => ({
      id: o.id,
      name: o.name,
      remaining: countRemainingShipCells(o),
      eliminated: o.eliminated,
      disconnected: isDisconnected(o),
    })),
  };
}

function countRemainingShipCells(p) {
  if (!p.ships) return 0;
  const hits = ensureBoardHits(p.id);
  let n = 0;
  for (const sh of p.ships) for (const c of sh.cells) if (!hits.has(c)) n++;
  return n;
}

function pushStateTo(p) {
  if (phase === 'playing' && p.ws)
    send(p.ws, { type: 'state', you: buildPrivateState(p) });
}

function handleFire(ws, playerId, msg) {
  if (phase !== 'playing') {
    sendError(ws, 'Game not in progress.');
    return;
  }
  const shooter = players.get(playerId);
  if (!shooter || shooter.eliminated || isDisconnected(shooter)) {
    sendError(ws, 'Invalid shooter.');
    return;
  }
  const currentId = turnOrder[currentTurnIndex];
  if (currentId !== playerId) {
    sendError(ws, 'Not your turn.');
    return;
  }
  const target = players.get(msg.target);
  if (!target || target.id === playerId || target.eliminated) {
    sendError(ws, 'Invalid target.');
    return;
  }
  const cellParsed = parseCell(msg.cell);
  if (!cellParsed) {
    sendError(ws, 'Invalid cell.');
    return;
  }
  const cell = cellParsed.key;
  const sk = shotKey(playerId, target.id);
  if (!shots.has(sk)) shots.set(sk, new Map());
  const grid = shots.get(sk);
  if (grid.has(cell)) {
    sendError(ws, 'Already fired at that cell.');
    return;
  }
  const shipName = cellToShipName(target, cell);
  const hit = !!shipName;
  const result = hit ? 'hit' : 'miss';
  grid.set(cell, result);
  let shipSunk = null;
  if (hit) {
    ensureBoardHits(target.id).add(cell);
    if (shipName && isShipFullyHit(target, shipName)) shipSunk = shipName;
  } else {
    ensureBoardMisses(target.id).add(cell);
  }
  broadcast((p) => {
    send(p.ws, {
      type: 'fire_result',
      shooter: { id: shooter.id, name: shooter.name },
      target: { id: target.id, name: target.name },
      cell,
      result,
      shipSunk,
    });
  });
  if (hit && allShipsSunk(target)) {
    eliminatePlayer(target.id, 'sunk');
  }
  if (phase !== 'playing') {
    for (const p of players.values()) pushStateTo(p);
    return;
  }
  if (hit) {
    notifyTurn();
  } else {
    advanceTurnFrom(currentTurnIndex);
  }
  for (const p of players.values()) pushStateTo(p);
}

function attachWs(player, ws) {
  player.ws = ws;
  ws.playerId = player.id;
}

function handleMessage(ws, raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    sendError(ws, 'Invalid JSON.');
    return;
  }
  if (!msg || typeof msg.type !== 'string') {
    sendError(ws, 'Missing type.');
    return;
  }
  if (msg.type === 'ping') {
    send(ws, { type: 'pong', ts: msg.ts });
    return;
  }
  if (msg.type === 'join') {
    const name = typeof msg.name === 'string' ? msg.name.trim().slice(0, 32) : '';
    if (!name) {
      sendError(ws, 'Name required.');
      return;
    }
    const existingId = typeof msg.playerId === 'string' ? msg.playerId : null;
    if (existingId && players.has(existingId)) {
      const p = players.get(existingId);
      if (p.name !== name) {
        sendError(ws, 'Name mismatch for reconnect.');
        return;
      }
      clearDisconnectTimer(p);
      attachWs(p, ws);
      send(ws, { type: 'joined', playerId: p.id, name: p.name, phase });
      if (phase === 'lobby' || phase === 'placement') broadcastLobby();
      else if (phase === 'playing') {
        send(ws, {
          type: 'game_start',
          players: [...players.values()].map((x) => ({ id: x.id, name: x.name })),
          turnOrder: [...turnOrder],
          you: buildPrivateState(p),
        });
        const cur = turnOrder[currentTurnIndex];
        send(ws, { type: 'your_turn', playerId: cur });
        pushStateTo(p);
      } else if (phase === 'ended' && standings) {
        const w = players.get(winnerId);
        send(ws, {
          type: 'game_over',
          winner: w ? { id: w.id, name: w.name } : null,
          standings,
        });
      }
      return;
    }
    if (players.size >= MAX_PLAYERS && !existingId) {
      sendError(ws, 'Lobby full.');
      return;
    }
    if (phase !== 'lobby') {
      sendError(ws, 'Game already started.');
      return;
    }
    const id = randomId();
    const p = createPlayer(id, name);
    attachWs(p, ws);
    players.set(id, p);
    send(ws, { type: 'joined', playerId: id, name, phase });
    scheduleLobbyStart();
    broadcastLobby();
    return;
  }
  const playerId = ws.playerId;
  if (!playerId || !players.has(playerId)) {
    sendError(ws, 'Join first.');
    return;
  }
  const player = players.get(playerId);
  if (msg.type === 'place') {
    if (phase !== 'placement') {
      sendError(ws, 'Not in placement phase.');
      return;
    }
    const err = validatePlacement(msg.ships);
    if (err) {
      sendError(ws, err);
      return;
    }
    player.ships = msg.ships.map((s) => ({ name: s.name, cells: [...s.cells] }));
    player.placementDone = true;
    broadcastLobby();
    maybeStartPlaying();
    return;
  }
  if (msg.type === 'fire') {
    handleFire(ws, playerId, msg);
    return;
  }
  if (msg.type === 'ready_again') {
    if (phase !== 'ended') {
      sendError(ws, 'Game not over.');
      return;
    }
    phase = 'lobby';
    turnOrder = [];
    currentTurnIndex = 0;
    shots.clear();
    boardHits.clear();
    boardMisses.clear();
    winnerId = null;
    standings = null;
    for (const p of players.values()) {
      p.ships = null;
      p.placementDone = false;
      p.eliminated = false;
      clearDisconnectTimer(p);
    }
    scheduleLobbyStart();
    broadcastLobby();
    return;
  }
  sendError(ws, `Unknown type: ${msg.type}`);
}

function onClose(ws) {
  const id = ws.playerId;
  if (!id || !players.has(id)) return;
  const p = players.get(id);
  p.ws = null;
  p.disconnectedAt = Date.now();
  if (phase === 'playing' || phase === 'placement') scheduleDisconnectElimination(p);
  else if (phase === 'lobby') {
    players.delete(id);
    if (players.size < MIN_PLAYERS) clearLobbyTimer();
    broadcastLobby();
  }
}

const { port } = parseArgs(process.argv);

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/index.html') {
    const fp = path.join(__dirname, 'index.html');
    fs.readFile(fp, (err, data) => {
      if (err) {
        res.writeHead(500);
        res.end('Missing index.html');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(data);
    });
  } else {
    res.writeHead(404);
    res.end();
  }
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
  ws.on('message', (data) => handleMessage(ws, data.toString()));
  ws.on('close', () => onClose(ws));
});

server.listen(port, () => {
  console.log(`Battleship server listening on http://localhost:${port} (ws on same port)`);
});
