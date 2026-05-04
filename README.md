# Multiplayer Battleship (3–4 players)

Minimal Battleship implementation used as a **WebSocket network testing tool**: vanilla browser client, Node.js server with the [`ws`](https://github.com/websockets/ws) package, no database.

## Run

```bash
cd /path/to/BattleShip
npm install
node server.js --port 3000
```

Or:

```bash
npm start -- --port 3000
```

- **HTTP + WebSocket** share the same port. Open `http://localhost:3000` in each browser tab or machine.
- Default port is **3000** if you omit `--port`.

## Java Swing client (optional)

The Maven project lives in the **`java/`** submodule. From the **repository root** (`BattleShip/`), target that module with **`-pl java`**:

```bash
mvn -q -pl java compile
mvn -q -pl java exec:java -Dexec.args="server --port 3000"
mvn -q -pl java exec:java -Dexec.args="client --host 127.0.0.1 --port 3000"
```

Or `cd java` and run the same `mvn` commands **without** `-pl java`. Running `mvn exec:java` only from the root **without** `-pl java` fails because the root POM is an aggregator (no Java sources).

## Multiple players (local and LAN)

1. Start the server on a machine with `--port` set as you like.
2. **Same computer:** open several browser windows or profiles to `http://localhost:<PORT>` and choose different usernames.
3. **LAN:** find the host machine’s LAN IP (e.g. `192.168.1.10`) and on other devices open `http://192.168.1.10:<PORT>`. Ensure firewalls allow inbound TCP on that port.

Lobby behavior: the game moves from lobby to ship placement when there are **four** players, or **three** players after a **2 second** debounce (so a fourth player can join quickly if desired).

## WebSocket message protocol

All messages are JSON with a `type` field.

### Client → server

| type | fields | notes |
|------|--------|--------|
| `join` | `name`, optional `playerId` | First join omits `playerId`. After disconnect, send the same `name` and stored `playerId` to reattach. |
| `place` | `ships`: `[{ name, cells: ["A1", ...] }, ...]` | Standard fleet; must match server validation. |
| `fire` | `target` (player id string), `cell` (e.g. `"B5"`) | Only on your turn. |
| `ready_again` | — | After `game_over`, resets lobby for a new round (same players, new placement). |
| `ping` | `ts` (e.g. `Date.now()`) | Server answers with `pong` for RTT. |

### Server → client

| type | purpose |
|------|--------|
| `joined` | Confirms identity: `playerId`, `name`, `phase`. Client should persist `playerId` for reconnection. |
| `lobby_update` | `phase`, `players` (`id`, `name`, `ready`), `waiting`. |
| `game_start` | `players`, `turnOrder`, `you` (private snapshot: your ships, your board hits/misses, per-target shot maps, ship counts). |
| `state` | `you` — same shape as `game_start.you`; sent after actions to refresh UI. |
| `your_turn` | `playerId` whose turn it is. |
| `fire_result` | `shooter`, `target` (objects with `id`, `name`), `cell`, `result` (`hit` \| `miss`), `shipSunk` (name or `null`). |
| `player_eliminated` | `playerId` |
| `game_over` | `winner` (`id`, `name` or `null`), `standings` |
| `pong` | `ts` — echo of client ping timestamp |
| `error` | `message` |

## Network stats panel

Use the **Network stats** panel (bottom-right, collapsible) while playing:

- **RTT** — client sends `ping` every **5 seconds**; RTT = `Date.now() - ts` when `pong` returns.
- **Sent / recv counts** and **approximate bytes** (length of JSON strings) for quick traffic volume checks.
- **Last message** time and **WebSocket** state (`OPEN`, `CLOSED`, `RECONNECTING`).

Open **DevTools → Console**: every WebSocket send is logged as **↑** and every receive as **↓** with an ISO-like timestamp prefix for correlation with the Network tab.

## Reconnection

If the socket drops, the client reconnects every **3 seconds**, up to **10** attempts, and shows a **Reconnecting…** banner. Rejoin uses `localStorage` (`battleship_player`) to send `playerId` with `join`.

On the server, a disconnected player is skipped in turn order; if they reconnect within **30 seconds**, their slot is restored. After **30 seconds** without a connection during a match, they are **eliminated**.
