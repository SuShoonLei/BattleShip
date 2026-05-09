package battleship;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;

/**
 * In-memory game state and rules (ported from server.js). Sends JSON over each player's WebSocket.
 */

public final class GameEngine {
  private static final Gson GSON = new Gson();

  private final Object lock = new Object();
  private final ScheduledExecutorService scheduler;
  private GamePhase phase = GamePhase.LOBBY;
  private final LinkedHashMap<String, Player> players = new LinkedHashMap<>();
  private ScheduledFuture<?> lobbyTimer;
  private List<String> turnOrder = new ArrayList<>();
  private int currentTurnIndex;
  private final Map<String, Map<String, String>> shots = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> boardHits = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> boardMisses = new ConcurrentHashMap<>();
  private String winnerId;
  private JsonArray standingsCache;

  public GameEngine(ScheduledExecutorService scheduler) {
    this.scheduler = scheduler;
  }

  public void handleMessage(WebSocket ws, String raw) {
    synchronized (lock) {
      handleMessageLocked(ws, raw);
    }
  }

  private void handleMessageLocked(WebSocket ws, String raw) {
    JsonObject msg;
    try {
      msg = JsonParser.parseString(raw).getAsJsonObject();
    } catch (Exception e) {
      sendError(ws, "Invalid JSON.");
      return;
    }
    if (!msg.has("type") || msg.get("type").isJsonNull()) {
      sendError(ws, "Missing type.");
      return;
    }
    String type = msg.get("type").getAsString();
    if ("ping".equals(type)) {
      JsonObject pong = new JsonObject();
      pong.addProperty("type", "pong");
      if (msg.has("ts")) pong.add("ts", msg.get("ts"));
      if (ws.isOpen()) ws.send(GSON.toJson(pong));
      return;
    }
    if ("join".equals(type)) {
      handleJoin(ws, msg);
      return;
    }
    String playerId = ws.getAttachment() != null ? ws.getAttachment().toString() : null;
    if (playerId == null || !players.containsKey(playerId)) {
      sendError(ws, "Join first.");
      return;
    }
    Player player = players.get(playerId);
    switch (type) {
      case "place" -> handlePlace(ws, player, msg);
      case "fire" -> handleFire(ws, playerId, msg);
      case "ready_again" -> handleReadyAgain(ws, player);
      default -> sendError(ws, "Unknown type: " + type);
    }
  }

  public void onSocketClosed(WebSocket ws) {
    synchronized (lock) {
      onSocketClosedLocked(ws);
    }
  }

  private void onSocketClosedLocked(WebSocket ws) {
    String id = ws.getAttachment() != null ? ws.getAttachment().toString() : null;
    if (id == null || !players.containsKey(id)) return;
    Player p = players.get(id);
    p.ws = null;
    p.disconnectedAt = System.currentTimeMillis();
    if (phase == GamePhase.PLAYING || phase == GamePhase.PLACEMENT) {
      scheduleDisconnectElimination(p);
    } else if (phase == GamePhase.LOBBY) {
      cancelPlayerTimer(p);
      players.remove(id);
      if (players.size() < GameConstants.MIN_PLAYERS) clearLobbyTimer();
      broadcastLobby();
    }
  }

  private static String randomId() {
    return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static boolean isDisconnected(Player p) {
    return p.ws == null || !p.ws.isOpen();
  }

  private void send(Player p, JsonObject o) {
    if (p.ws != null && p.ws.isOpen()) p.ws.send(GSON.toJson(o));
  }

  private void sendError(WebSocket ws, String message) {
    JsonObject o = new JsonObject();
    o.addProperty("type", "error");
    o.addProperty("message", message);
    if (ws.isOpen()) ws.send(GSON.toJson(o));
  }

  private void broadcastLobby() {
    JsonObject payload = lobbyPayload();
    String json = GSON.toJson(payload);
    for (Player p : players.values()) sendRaw(p, json);
  }

  private void sendRaw(Player p, String json) {
    if (p.ws != null && p.ws.isOpen()) p.ws.send(json);
  }

  private JsonObject lobbyPayload() {
    JsonObject o = new JsonObject();
    o.addProperty("type", "lobby_update");
    o.addProperty("phase", phase.name().toLowerCase());
    o.addProperty("waiting", phase == GamePhase.LOBBY);
    JsonArray arr = new JsonArray();
    for (Player p : players.values()) {
      JsonObject row = new JsonObject();
      row.addProperty("id", p.id);
      row.addProperty("name", p.name);
      row.addProperty("ready", p.placementDone);
      arr.add(row);
    }
    o.add("players", arr);
    return o;
  }

  private void clearLobbyTimer() {
    if (lobbyTimer != null) {
      lobbyTimer.cancel(false);
      lobbyTimer = null;
    }
  }

  private Set<String> ensureBoardHits(String pid) {
    return boardHits.computeIfAbsent(pid, k -> new HashSet<>());
  }

  private Set<String> ensureBoardMisses(String pid) {
    return boardMisses.computeIfAbsent(pid, k -> new HashSet<>());
  }

  private Set<String> shipCellsSet(Player player) {
    Set<String> s = new HashSet<>();
    if (player.ships == null) return s;
    for (Player.Ship sh : player.ships) s.addAll(sh.cells);
    return s;
  }

  private String cellToShipName(Player player, String cell) {
    if (player.ships == null) return null;
    for (Player.Ship sh : player.ships) {
      if (sh.cells.contains(cell)) return sh.name;
    }
    return null;
  }

  private boolean isShipFullyHit(Player player, String shipName) {
    Player.Ship sh = null;
    for (Player.Ship x : player.ships) {
      if (Objects.equals(x.name, shipName)) {
        sh = x;
        break;
      }
    }
    if (sh == null) return false;
    Set<String> hits = ensureBoardHits(player.id);
    return sh.cells.stream().allMatch(hits::contains);
  }

  private boolean allShipsSunk(Player player) {
    if (player.ships == null) return true;
    Set<String> hits = ensureBoardHits(player.id);
    Set<String> cells = shipCellsSet(player);
    return !cells.isEmpty() && cells.stream().allMatch(hits::contains);
  }

  private void advanceTurnFrom(int fromIndex) {
    int n = turnOrder.size();
    if (n == 0) return;
    for (int k = 0; k < n; k++) {
      int i = (fromIndex + 1 + k) % n;
      String pid = turnOrder.get(i);
      Player p = players.get(pid);
      if (p != null && !p.eliminated && !isDisconnected(p)) {
        currentTurnIndex = i;
        notifyTurn();
        return;
      }
    }
    checkGameOver();
  }

  private void notifyTurn() {
    if (turnOrder.isEmpty()) return;
    String pid = turnOrder.get(currentTurnIndex);
    Player p = players.get(pid);
    if (p == null || p.eliminated || isDisconnected(p)) {
      advanceTurnFrom(currentTurnIndex);
      return;
    }
    JsonObject msg = new JsonObject();
    msg.addProperty("type", "your_turn");
    msg.addProperty("playerId", pid);
    String json = GSON.toJson(msg);
    for (Player pl : players.values()) sendRaw(pl, json);
  }

  private void checkGameOver() {
    if (phase == GamePhase.ENDED) return;
    List<Player> alive = players.values().stream().filter(p -> !p.eliminated).toList();
    if (alive.size() > 1) return;
    phase = GamePhase.ENDED;
    Player winner = alive.isEmpty() ? null : alive.get(0);
    winnerId = winner != null ? winner.id : null;
    List<Player> losers =
        players.values().stream()
            .filter(p -> p.eliminated)
            .sorted(Comparator.comparing(p -> p.name))
            .toList();
    JsonArray standings = new JsonArray();
    if (winner != null) {
      JsonObject w = new JsonObject();
      w.addProperty("id", winner.id);
      w.addProperty("name", winner.name);
      w.addProperty("rank", 1);
      w.addProperty("eliminated", false);
      standings.add(w);
    }
    int r = 2;
    for (Player p : losers) {
      JsonObject row = new JsonObject();
      row.addProperty("id", p.id);
      row.addProperty("name", p.name);
      row.addProperty("rank", r++);
      row.addProperty("eliminated", true);
      standings.add(row);
    }
    standingsCache = standings;
    Player wObj = winnerId != null ? players.get(winnerId) : null;
    JsonObject go = new JsonObject();
    go.addProperty("type", "game_over");
    if (wObj != null) {
      JsonObject win = new JsonObject();
      win.addProperty("id", wObj.id);
      win.addProperty("name", wObj.name);
      go.add("winner", win);
    } else {
      go.add("winner", JsonNull.INSTANCE);
    }
    go.add("standings", standings);
    String json = GSON.toJson(go);
    for (Player pl : players.values()) sendRaw(pl, json);
  }

  private void eliminatePlayer(String playerId) {
    Player p = players.get(playerId);
    if (p == null || p.eliminated) return;
    p.eliminated = true;
    JsonObject ev = new JsonObject();
    ev.addProperty("type", "player_eliminated");
    ev.addProperty("playerId", playerId);
    String j = GSON.toJson(ev);
    for (Player pl : players.values()) sendRaw(pl, j);
    checkGameOver();
    if (phase != GamePhase.PLAYING) return;
    String currentId = turnOrder.get(currentTurnIndex);
    Player cur = players.get(currentId);
    if (Objects.equals(currentId, playerId)
        || cur == null
        || cur.eliminated
        || isDisconnected(cur)) {
      advanceTurnFrom(currentTurnIndex);
    }
  }

  private void cancelPlayerTimer(Player p) {
    if (p.disconnectTimer != null) {
      p.disconnectTimer.cancel(false);
      p.disconnectTimer = null;
    }
    p.disconnectedAt = null;
  }

  private void scheduleDisconnectElimination(Player p) {
    cancelPlayerTimer(p);
    p.disconnectTimer =
        scheduler.schedule(
            () -> {
              synchronized (lock) {
                p.disconnectTimer = null;
                if (phase != GamePhase.PLAYING && phase != GamePhase.PLACEMENT) return;
                if (p.ws != null && p.ws.isOpen()) return;
                p.disconnectedAt = null;
                if (phase == GamePhase.PLAYING) {
                  eliminatePlayer(p.id);
                } else {
                  players.remove(p.id);
                  if (players.size() < GameConstants.MIN_PLAYERS && phase == GamePhase.PLACEMENT) {
                    clearLobbyTimer();
                    phase = GamePhase.LOBBY;
                    for (Player pl : players.values()) {
                      pl.placementDone = false;
                      pl.ships = null;
                    }
                  }
                  broadcastLobby();
                }
              }
            },
            GameConstants.RECONNECT_MS,
            TimeUnit.MILLISECONDS);
  }

  private String validatePlacement(JsonArray shipsPayload) {
    if (shipsPayload == null || shipsPayload.size() != GameConstants.FLEET.size()) {
      return "Wrong number of ships.";
    }
    Set<String> seenNames = new HashSet<>();
    for (JsonElement el : shipsPayload) {
      if (!el.isJsonObject()) return "Invalid ship entry.";
      JsonObject s = el.getAsJsonObject();
      if (!s.has("name")) return "Each ship name must appear once.";
      String n = s.get("name").getAsString();
      if (seenNames.contains(n)) return "Each ship name must appear once.";
      seenNames.add(n);
    }
    Set<String> used = new HashSet<>();
    for (GameConstants.FleetSpec spec : GameConstants.FLEET) {
      JsonObject s = null;
      for (JsonElement el : shipsPayload) {
        JsonObject o = el.getAsJsonObject();
        if (spec.name().equals(o.get("name").getAsString())) {
          s = o;
          break;
        }
      }
      if (s == null || !s.has("cells") || !s.get("cells").isJsonArray()) {
        return "Missing or invalid " + spec.name() + ".";
      }
      JsonArray cells = s.getAsJsonArray("cells");
      if (cells.size() != spec.len()) return spec.name() + " must span " + spec.len() + " cells.";
      List<String> keys = new ArrayList<>();
      for (JsonElement c : cells) {
        Optional<CellUtil.ParsedCell> pc = CellUtil.parseCell(c.getAsString());
        if (pc.isEmpty() || !CellUtil.ALL_CELLS.contains(pc.get().key())) {
          return spec.name() + " has invalid cells.";
        }
        keys.add(pc.get().key());
      }
      for (String k : keys) {
        if (used.contains(k)) return "Overlapping ships.";
        used.add(k);
      }
      List<Character> rows = keys.stream().map(k -> k.charAt(0)).toList();
      List<Integer> cols = keys.stream().map(k -> Integer.parseInt(k.substring(1))).toList();
      boolean sameRow = rows.stream().allMatch(r -> r.equals(rows.get(0)));
      boolean sameCol = cols.stream().allMatch(c -> c.equals(cols.get(0)));
      if (!sameRow && !sameCol) return spec.name() + " must be straight horizontal or vertical.";
      if (sameRow) {
        List<Integer> sorted = new ArrayList<>(cols);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
          if (sorted.get(i) != sorted.get(i - 1) + 1) return spec.name() + " must be contiguous.";
        }
      } else {
        List<Integer> ord =
            keys.stream()
                .map(k -> GameConstants.ROWS.indexOf(k.charAt(0)))
                .sorted()
                .toList();
        for (int i = 1; i < ord.size(); i++) {
          if (ord.get(i) != ord.get(i - 1) + 1) return spec.name() + " must be contiguous.";
        }
      }
    }
    return null;
  }

  private void beginPlacement() {
    clearLobbyTimer();
    if (phase != GamePhase.LOBBY) return;
    if (players.size() < GameConstants.MIN_PLAYERS || players.size() > GameConstants.MAX_PLAYERS)
      return;
    phase = GamePhase.PLACEMENT;
    for (Player p : players.values()) {
      p.placementDone = false;
      p.ships = null;
    }
    broadcastLobby();
  }

  private void scheduleLobbyStart() {
    if (phase != GamePhase.LOBBY) return;
    if (players.size() < GameConstants.MIN_PLAYERS) return;
    if (players.size() >= GameConstants.MAX_PLAYERS) {
      beginPlacement();
      return;
    }
    clearLobbyTimer();
    lobbyTimer =
        scheduler.schedule(
            () -> {
              synchronized (lock) {
                lobbyTimer = null;
                if (phase == GamePhase.LOBBY && players.size() >= GameConstants.MIN_PLAYERS) {
                  beginPlacement();
                }
              }
            },
            GameConstants.LOBBY_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS);
  }

  private void maybeStartPlaying() {
    if (phase != GamePhase.PLACEMENT) return;
    if (!players.values().stream().allMatch(p -> p.placementDone && p.ships != null)) return;
    phase = GamePhase.PLAYING;
    shots.clear();
    boardHits.clear();
    boardMisses.clear();
    for (Player p : players.values()) {
      ensureBoardHits(p.id);
      ensureBoardMisses(p.id);
    }
    turnOrder = new ArrayList<>(players.keySet());
    currentTurnIndex = 0;
    while (currentTurnIndex < turnOrder.size()) {
      String pid = turnOrder.get(currentTurnIndex);
      Player p = players.get(pid);
      if (p != null && !p.eliminated && !isDisconnected(p)) break;
      currentTurnIndex++;
    }
    for (Player p : players.values()) {
      JsonObject msg = new JsonObject();
      msg.addProperty("type", "game_start");
      msg.add("players", playersJsonBrief());
      msg.add("turnOrder", GSON.toJsonTree(turnOrder));
      msg.add("you", buildPrivateState(p));
      send(p, msg);
    }
    notifyTurn();
  }

  private JsonArray playersJsonBrief() {
    JsonArray arr = new JsonArray();
    for (Player p : players.values()) {
      JsonObject o = new JsonObject();
      o.addProperty("id", p.id);
      o.addProperty("name", p.name);
      arr.add(o);
    }
    return arr;
  }

  private int countRemainingShipCells(Player p) {
    if (p.ships == null) return 0;
    Set<String> hits = ensureBoardHits(p.id);
    int n = 0;
    for (Player.Ship sh : p.ships) {
      for (String c : sh.cells) {
        if (!hits.contains(c)) n++;
      }
    }
    return n;
  }

  private JsonObject buildPrivateState(Player forPlayer) {
    String pid = forPlayer.id;
    Set<String> ownHits = ensureBoardHits(pid);
    Set<String> ownMisses = ensureBoardMisses(pid);
    JsonObject you = new JsonObject();
    if (forPlayer.ships != null) {
      JsonArray ships = new JsonArray();
      for (Player.Ship sh : forPlayer.ships) {
        JsonObject so = new JsonObject();
        so.addProperty("name", sh.name);
        so.add("cells", GSON.toJsonTree(sh.cells));
        ships.add(so);
      }
      you.add("ownShips", ships);
    } else {
      you.add("ownShips", JsonNull.INSTANCE);
    }
    JsonObject ownBoard = new JsonObject();
    ownBoard.add("hits", GSON.toJsonTree(new ArrayList<>(ownHits)));
    ownBoard.add("misses", GSON.toJsonTree(new ArrayList<>(ownMisses)));
    you.add("ownBoard", ownBoard);
    JsonArray attacks = new JsonArray();
    for (Player o : players.values()) {
      if (o.id.equals(pid) || o.eliminated) continue;
      JsonObject a = new JsonObject();
      a.addProperty("targetId", o.id);
      a.addProperty("targetName", o.name);
      String sk = CellUtil.shotKey(pid, o.id);
      Map<String, String> m = shots.getOrDefault(sk, Map.of());
      JsonArray cells = new JsonArray();
      for (Map.Entry<String, String> e : m.entrySet()) {
        JsonObject c = new JsonObject();
        c.addProperty("cell", e.getKey());
        c.addProperty("result", e.getValue());
        cells.add(c);
      }
      a.add("cells", cells);
      attacks.add(a);
    }
    you.add("attacks", attacks);
    JsonArray living = new JsonArray();
    for (Player o : players.values()) {
      if (o.id.equals(pid) || o.eliminated) continue;
      JsonObject lo = new JsonObject();
      lo.addProperty("id", o.id);
      lo.addProperty("name", o.name);
      living.add(lo);
    }
    you.add("livingOpponents", living);
    JsonArray counts = new JsonArray();
    for (Player o : players.values()) {
      JsonObject sc = new JsonObject();
      sc.addProperty("id", o.id);
      sc.addProperty("name", o.name);
      sc.addProperty("remaining", countRemainingShipCells(o));
      sc.addProperty("eliminated", o.eliminated);
      sc.addProperty("disconnected", isDisconnected(o));
      counts.add(sc);
    }
    you.add("shipCounts", counts);
    return you;
  }

  private void pushStateTo(Player p) {
    if (phase == GamePhase.PLAYING && p.ws != null && p.ws.isOpen()) {
      JsonObject o = new JsonObject();
      o.addProperty("type", "state");
      o.add("you", buildPrivateState(p));
      send(p, o);
    }
  }

  private void handleFire(WebSocket ws, String playerId, JsonObject msg) {
    if (phase != GamePhase.PLAYING) {
      sendError(ws, "Game not in progress.");
      return;
    }
    Player shooter = players.get(playerId);
    if (shooter == null || shooter.eliminated || isDisconnected(shooter)) {
      sendError(ws, "Invalid shooter.");
      return;
    }
    String currentId = turnOrder.get(currentTurnIndex);
    if (!playerId.equals(currentId)) {
      sendError(ws, "Not your turn.");
      return;
    }
    if (!msg.has("target") || !msg.has("cell")) {
      sendError(ws, "Invalid fire message.");
      return;
    }
    String targetId = msg.get("target").getAsString();
    Player target = players.get(targetId);
    if (target == null || target.id.equals(playerId) || target.eliminated) {
      sendError(ws, "Invalid target.");
      return;
    }
    String cell =
        CellUtil.parseCell(msg.get("cell").getAsString())
            .map(CellUtil.ParsedCell::key)
            .orElse(null);
    if (cell == null) {
      sendError(ws, "Invalid cell.");
      return;
    }
    String sk = CellUtil.shotKey(playerId, target.id);
    Map<String, String> grid = shots.computeIfAbsent(sk, k -> new HashMap<>());
    if (grid.containsKey(cell)) {
      sendError(ws, "Already fired at that cell.");
      return;
    }
    String shipName = cellToShipName(target, cell);
    boolean hit = shipName != null;
    String result = hit ? "hit" : "miss";
    grid.put(cell, result);
    String shipSunk = null;
    if (hit) {
      ensureBoardHits(target.id).add(cell);
      if (shipName != null && isShipFullyHit(target, shipName)) shipSunk = shipName;
    } else {
      ensureBoardMisses(target.id).add(cell);
    }
    JsonObject fr = new JsonObject();
    fr.addProperty("type", "fire_result");
    JsonObject sh = new JsonObject();
    sh.addProperty("id", shooter.id);
    sh.addProperty("name", shooter.name);
    fr.add("shooter", sh);
    JsonObject tg = new JsonObject();
    tg.addProperty("id", target.id);
    tg.addProperty("name", target.name);
    fr.add("target", tg);
    fr.addProperty("cell", cell);
    fr.addProperty("result", result);
    if (shipSunk != null) fr.addProperty("shipSunk", shipSunk);
    else fr.add("shipSunk", JsonNull.INSTANCE);
    String frJson = GSON.toJson(fr);
    for (Player pl : players.values()) sendRaw(pl, frJson);
    if (hit && allShipsSunk(target)) {
      eliminatePlayer(target.id);
    }
    if (phase != GamePhase.PLAYING) {
      for (Player p : players.values()) pushStateTo(p);
      return;
    }
    if (hit) {
      notifyTurn();
    } else {
      advanceTurnFrom(currentTurnIndex);
    }
    for (Player p : players.values()) pushStateTo(p);
  }

  private void handleJoin(WebSocket ws, JsonObject msg) {
    if (!msg.has("name")) {
      sendError(ws, "Name required.");
      return;
    }
    String name = msg.get("name").getAsString().trim();
    if (name.length() > 32) name = name.substring(0, 32);
    if (name.isEmpty()) {
      sendError(ws, "Name required.");
      return;
    }
    String existingId = msg.has("playerId") && !msg.get("playerId").isJsonNull()
        ? msg.get("playerId").getAsString()
        : null;
    if (existingId != null && players.containsKey(existingId)) {
      Player p = players.get(existingId);
      if (!p.name.equals(name)) {
        sendError(ws, "Name mismatch for reconnect.");
        return;
      }
      cancelPlayerTimer(p);
      p.ws = ws;
      ws.setAttachment(p.id);
      JsonObject joined = new JsonObject();
      joined.addProperty("type", "joined");
      joined.addProperty("playerId", p.id);
      joined.addProperty("name", p.name);
      joined.addProperty("phase", phase.name().toLowerCase());
      if (ws.isOpen()) ws.send(GSON.toJson(joined));
      if (phase == GamePhase.LOBBY || phase == GamePhase.PLACEMENT) {
        broadcastLobby();
      } else if (phase == GamePhase.PLAYING) {
        JsonObject gs = new JsonObject();
        gs.addProperty("type", "game_start");
        gs.add("players", playersJsonBrief());
        gs.add("turnOrder", GSON.toJsonTree(turnOrder));
        gs.add("you", buildPrivateState(p));
        if (ws.isOpen()) ws.send(GSON.toJson(gs));
        String cur = turnOrder.get(currentTurnIndex);
        JsonObject yt = new JsonObject();
        yt.addProperty("type", "your_turn");
        yt.addProperty("playerId", cur);
        if (ws.isOpen()) ws.send(GSON.toJson(yt));
        pushStateTo(p);
      } else if (phase == GamePhase.ENDED && standingsCache != null) {
        Player wObj = winnerId != null ? players.get(winnerId) : null;
        JsonObject go = new JsonObject();
        go.addProperty("type", "game_over");
        if (wObj != null) {
          JsonObject win = new JsonObject();
          win.addProperty("id", wObj.id);
          win.addProperty("name", wObj.name);
          go.add("winner", win);
        } else go.add("winner", JsonNull.INSTANCE);
        go.add("standings", standingsCache);
        if (ws.isOpen()) ws.send(GSON.toJson(go));
      }
      return;
    }
    if (players.size() >= GameConstants.MAX_PLAYERS && existingId == null) {
      sendError(ws, "Lobby full.");
      return;
    }
    if (phase != GamePhase.LOBBY) {
      sendError(ws, "Game already started.");
      return;
    }
    String id = randomId();
    Player p = new Player(id, name);
    p.ws = ws;
    ws.setAttachment(id);
    players.put(id, p);
    JsonObject joined = new JsonObject();
    joined.addProperty("type", "joined");
    joined.addProperty("playerId", id);
    joined.addProperty("name", name);
    joined.addProperty("phase", phase.name().toLowerCase());
    if (ws.isOpen()) ws.send(GSON.toJson(joined));
    scheduleLobbyStart();
    broadcastLobby();
  }

  private void handlePlace(WebSocket ws, Player player, JsonObject msg) {
    if (phase != GamePhase.PLACEMENT) {
      sendError(ws, "Not in placement phase.");
      return;
    }
    if (!msg.has("ships") || !msg.get("ships").isJsonArray()) {
      sendError(ws, "Invalid place message.");
      return;
    }
    String err = validatePlacement(msg.getAsJsonArray("ships"));
    if (err != null) {
      sendError(ws, err);
      return;
    }
    List<Player.Ship> list = new ArrayList<>();
    for (JsonElement el : msg.getAsJsonArray("ships")) {
      JsonObject o = el.getAsJsonObject();
      Player.Ship sh = new Player.Ship();
      sh.name = o.get("name").getAsString();
      for (JsonElement c : o.getAsJsonArray("cells")) {
        sh.cells.add(c.getAsString());
      }
      list.add(sh);
    }
    player.ships = list;
    player.placementDone = true;
    broadcastLobby();
    maybeStartPlaying();
  }

  private void handleReadyAgain(WebSocket ws, Player player) {
    if (phase != GamePhase.ENDED) {
      sendError(ws, "Game not over.");
      return;
    }
    phase = GamePhase.LOBBY;
    turnOrder = new ArrayList<>();
    currentTurnIndex = 0;
    shots.clear();
    boardHits.clear();
    boardMisses.clear();
    winnerId = null;
    standingsCache = null;
    for (Player p : players.values()) {
      p.ships = null;
      p.placementDone = false;
      p.eliminated = false;
      cancelPlayerTimer(p);
    }
    scheduleLobbyStart();
    broadcastLobby();
  }
}
