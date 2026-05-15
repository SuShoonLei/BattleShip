package battleship;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

/**
 * Swing UI + TCP client (newline-delimited JSON, same protocol as the NIO server).
 */

public final class BattleShipClientFrame extends JFrame {
  private static final Gson GSON = new Gson();
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final Preferences PREFS = Preferences.userRoot().node("battleship_java");

  private final String defaultHost;
  private final int defaultPort;

  private final CardLayout cardLayout = new CardLayout();
  private final JPanel cards = new JPanel(cardLayout);

  private final JTextField joinName = new JTextField(16);
  private final JTextField joinHost = new JTextField(12);
  private final JTextField joinPort = new JTextField(6);
  private final JTextArea lobbyList = new JTextArea(8, 28);
  private final JTextArea gameLog = new JTextArea(8, 60);
  private final JTextArea statusBar = new JTextArea(2, 60);
  private final JLabel reconnectLabel = new JLabel(" ");
  private final JLabel netWs = new JLabel("CLOSED");
  private final JLabel netRtt = new JLabel("—");
  private final JLabel netSent = new JLabel("0");
  private final JLabel netRecv = new JLabel("0");
  private final JLabel netUp = new JLabel("0");
  private final JLabel netDown = new JLabel("0");
  private final JLabel netLast = new JLabel("—");

  private final JPanel placeGridPanel = new JPanel(new GridLayout(11, 11, 1, 1));
  private final JButton[][] placeCells = new JButton[10][10];
  private final JToggleButton orientToggle = new JToggleButton("Horizontal");
  private final JButton btnPlaceShip = new JButton("Place");
  private final JButton btnRandom = new JButton("Randomize");
  private final JButton btnReady = new JButton("Ready");
  private final JLabel placeHint = new JLabel(" ");

  private final JPanel ownGridPanel = new JPanel(new GridLayout(11, 11, 1, 1));
  private final JLabel[][] ownLabels = new JLabel[10][10];
  private final JPanel atkGridPanel = new JPanel(new GridLayout(11, 11, 1, 1));
  private final JButton[][] atkCells = new JButton[10][10];
  private final JComboBox<String> targetCombo = new JComboBox<>();
  private final Map<String, String> targetIdToName = new java.util.HashMap<>();

  private final JTextArea endText = new JTextArea(6, 40);
  private final JButton btnPlayAgain = new JButton("Play again");

  private TcpClient tcpClient;
  private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "battleship-client");
    t.setDaemon(true);
    return t;
  });
  private ScheduledFuture<?> pingFuture;

  private String currentCard = "JOIN";
  private boolean gameStarted;
  private boolean freshJoin;   // true = user clicked Connect (don't send saved ID)
  private String myId;
  private String myName = "";
  private String currentTurnId;
  private JsonObject lastYou;
  private String pendingStartCell;
  private final List<Player.Ship> placed = new ArrayList<>();
  private int reconnectAttempts;
  private javax.swing.Timer reconnectTimer;

  private long netBytesUp;
  private long netBytesDown;
  private int netSentCount;
  private int netRecvCount;

  public BattleShipClientFrame(String host, int port) {
    super("Battleship (Java client)");
    this.defaultHost = host;
    this.defaultPort = port;
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(920, 720);
    setLocationByPlatform(true);

    joinHost.setText(host);
    joinPort.setText(String.valueOf(port));

    cards.add(buildJoinPanel(), "JOIN");
    cards.add(buildLobbyPanel(), "LOBBY");
    cards.add(buildPlacePanel(), "PLACE");
    cards.add(buildGamePanel(), "GAME");
    cards.add(buildEndPanel(), "END");
    add(cards, BorderLayout.CENTER);
    add(buildNetBar(), BorderLayout.SOUTH);
    showCard("JOIN");
  }

  private void showCard(String name) {
    cardLayout.show(cards, name);
    currentCard = name;
  }

  private JPanel buildNetBar() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
    p.setBorder(BorderFactory.createTitledBorder("Network stats"));
    p.add(new JLabel("TCP:"));
    p.add(netWs);
    p.add(new JLabel(" RTT ms:"));
    p.add(netRtt);
    p.add(new JLabel(" sent/recv:"));
    p.add(netSent);
    p.add(netRecv);
    p.add(new JLabel(" bytes↑/↓:"));
    p.add(netUp);
    p.add(netDown);
    p.add(new JLabel(" last:"));
    p.add(netLast);
    return p;
  }

  private JPanel buildJoinPanel() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    p.setBorder(new TitledBorder("Join"));
    JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT));
    f.add(new JLabel("Name"));
    f.add(joinName);
    f.add(new JLabel("Host"));
    f.add(joinHost);
    f.add(new JLabel("Port"));
    f.add(joinPort);
    JButton go = new JButton("Connect");
    go.addActionListener(e -> doJoin());
    f.add(go);
    p.add(f, BorderLayout.NORTH);
    p.add(reconnectLabel, BorderLayout.SOUTH);
    return p;
  }

  private JPanel buildLobbyPanel() {
    JPanel p = new JPanel(new BorderLayout());
    p.setBorder(new TitledBorder("Lobby"));
    lobbyList.setEditable(false);
    p.add(new JScrollPane(lobbyList), BorderLayout.CENTER);
    return p;
  }

  private JPanel buildPlacePanel() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    p.setBorder(new TitledBorder("Place ships"));
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
    orientToggle.setSelected(false);
    orientToggle.setText("Horizontal");
    orientToggle.addActionListener(
        e -> orientToggle.setText(orientToggle.isSelected() ? "Vertical" : "Horizontal"));
    top.add(orientToggle);
    top.add(btnPlaceShip);
    btnPlaceShip.addActionListener(e -> confirmPlace());
    top.add(btnRandom);
    btnRandom.addActionListener(
        e -> {
          randomizeFleet();
          pendingStartCell = null;
          refreshPlaceGrid();
        });
    top.add(btnReady);
    btnReady.setEnabled(false);
    btnReady.addActionListener(
        e -> {
          JsonArray ships = new JsonArray();
          for (Player.Ship sh : placed) {
            JsonObject o = new JsonObject();
            o.addProperty("name", sh.name);
            o.add("cells", GSON.toJsonTree(sh.cells));
            ships.add(o);
          }
          JsonObject msg = new JsonObject();
          msg.addProperty("type", "place");
          msg.add("ships", ships);
          sendJson(msg);
        });
    p.add(top, BorderLayout.NORTH);
    p.add(placeHint, BorderLayout.CENTER);
    buildPlaceGrid();
    p.add(placeGridPanel, BorderLayout.SOUTH);
    return p;
  }

  private void buildPlaceGrid() {
    placeGridPanel.removeAll();
    placeGridPanel.add(new JLabel(""));
    for (int c = 1; c <= 10; c++) placeGridPanel.add(new JLabel(String.valueOf(c), JLabel.CENTER));
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      placeGridPanel.add(new JLabel(String.valueOf(rows.charAt(r)), JLabel.CENTER));
      for (int c = 0; c < 10; c++) {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(26, 26));
        int rr = r;
        int cc = c;
        b.addActionListener(e -> onPlaceCellClick(rr, cc));
        placeCells[r][c] = b;
        placeGridPanel.add(b);
      }
    }
  }

  private void onPlaceCellClick(int r, int c) {
    char row = GameConstants.ROWS.charAt(r);
    pendingStartCell = "" + row + (c + 1);
    refreshPlaceGrid();
  }

  private void confirmPlace() {
    GameConstants.FleetSpec cur = nextToPlace();
    if (cur == null || pendingStartCell == null) return;
    Set<String> occ = new HashSet<>();
    for (Player.Ship sh : placed) occ.addAll(sh.cells);
    List<String> line = lineFrom(pendingStartCell, cur.len(), orientToggle.isSelected());
    if (!isLineValid(line, occ, cur.len())) return;
    Player.Ship sh = new Player.Ship();
    sh.name = cur.name();
    sh.cells = new ArrayList<>(line);
    placed.add(sh);
    pendingStartCell = null;
    updatePlaceHint();
    refreshPlaceGrid();
  }

  private GameConstants.FleetSpec nextToPlace() {
    for (GameConstants.FleetSpec f : GameConstants.FLEET) {
      if (placed.stream().noneMatch(p -> p.name.equals(f.name()))) return f;
    }
    return null;
  }

  private void updatePlaceHint() {
    GameConstants.FleetSpec n = nextToPlace();
    placeHint.setText(
        n != null
            ? "Placing " + n.name() + " (" + n.len() + "). Pick start cell, then Place."
            : "All ships placed — press Ready.");
    btnReady.setEnabled(n == null);
  }

  private void refreshPlaceGrid() {
    Set<String> occ = new HashSet<>();
    for (Player.Ship sh : placed) occ.addAll(sh.cells);
    Set<String> shipSet = new HashSet<>(occ);
    GameConstants.FleetSpec cur = nextToPlace();
    List<String> preview = List.of();
    boolean previewOk = false;
    if (cur != null && pendingStartCell != null) {
      preview = lineFrom(pendingStartCell, cur.len(), orientToggle.isSelected());
      previewOk = isLineValid(preview, occ, cur.len());
    }
    Set<String> prevSet = new HashSet<>(preview);
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      for (int c = 0; c < 10; c++) {
        String key = "" + rows.charAt(r) + (c + 1);
        JButton b = placeCells[r][c];
        b.setBackground(null);
        if (shipSet.contains(key)) b.setBackground(new Color(40, 120, 60));
        else if (prevSet.contains(key)) b.setBackground(previewOk ? new Color(80, 160, 100) : new Color(180, 80, 80));
        b.setText("");
      }
    }
  }

  private static List<String> lineFrom(String start, int len, boolean vertical) {
    Optional<CellUtil.ParsedCell> pc = CellUtil.parseCell(start);
    if (pc.isEmpty()) return List.of();
    char row = pc.get().row();
    int col = pc.get().col();
    List<String> out = new ArrayList<>();
    if (!vertical) {
      for (int i = 0; i < len; i++) {
        if (col + i > 10) return List.of();
        out.add("" + row + (col + i));
      }
    } else {
      int ri = GameConstants.ROWS.indexOf(row);
      for (int i = 0; i < len; i++) {
        if (ri + i >= 10) return List.of();
        out.add("" + GameConstants.ROWS.charAt(ri + i) + col);
      }
    }
    return out;
  }

  private static boolean isLineValid(List<String> cells, Set<String> occ, int len) {
    if (cells == null || cells.size() != len) return false;
    for (String c : cells) {
      if (!CellUtil.ALL_CELLS.contains(c) || occ.contains(c)) return false;
    }
    return true;
  }

  private void randomizeFleet() {
    for (int attempt = 0; attempt < 500; attempt++) {
      placed.clear();
      Set<String> occ = new HashSet<>();
      boolean ok = true;
      for (GameConstants.FleetSpec f : GameConstants.FLEET) {
        boolean placedOne = false;
        for (int t = 0; t < 400; t++) {
          int ri = (int) (Math.random() * 10);
          int ci = (int) (Math.random() * 10);
          String start = "" + GameConstants.ROWS.charAt(ri) + (ci + 1);
          boolean vert = Math.random() < 0.5;
          List<String> line = lineFrom(start, f.len(), vert);
          if (!isLineValid(line, occ, f.len())) line = lineFrom(start, f.len(), !vert);
          if (isLineValid(line, occ, f.len())) {
            Player.Ship sh = new Player.Ship();
            sh.name = f.name();
            sh.cells = new ArrayList<>(line);
            placed.add(sh);
            occ.addAll(line);
            placedOne = true;
            break;
          }
        }
        if (!placedOne) {
          ok = false;
          break;
        }
      }
      if (ok && placed.size() == GameConstants.FLEET.size()) {
        updatePlaceHint();
        return;
      }
    }
  }

  private JPanel buildGamePanel() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    JPanel mid = new JPanel(new GridLayout(1, 2, 12, 0));
    JPanel left = new JPanel(new BorderLayout());
    left.setBorder(new TitledBorder("Your board"));
    buildStaticGrid(ownGridPanel, ownLabels, null, false);
    left.add(ownGridPanel, BorderLayout.CENTER);
    JPanel right = new JPanel(new BorderLayout());
    right.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
    JPanel atkTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
    atkTop.add(new JLabel("Target"));
    atkTop.add(targetCombo);
    targetCombo.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value != null) {
              String id = value.toString();
              setText(targetIdToName.getOrDefault(id, id));
            }
            return this;
          }
        });
    right.add(atkTop, BorderLayout.NORTH);
    buildAttackGrid();
    right.add(atkGridPanel, BorderLayout.CENTER);
    mid.add(left);
    mid.add(right);
    p.add(mid, BorderLayout.CENTER);
    gameLog.setEditable(false);
    statusBar.setEditable(false);
    JPanel south = new JPanel(new BorderLayout());
    south.add(new JLabel("Log"), BorderLayout.NORTH);
    south.add(new JScrollPane(gameLog), BorderLayout.CENTER);
    south.add(new JLabel("Fleet health"), BorderLayout.SOUTH);
    JPanel south2 = new JPanel(new BorderLayout());
    south2.add(south, BorderLayout.CENTER);
    south2.add(new JScrollPane(statusBar), BorderLayout.SOUTH);
    p.add(south2, BorderLayout.SOUTH);
    targetCombo.addActionListener(e -> renderAttackFromState());
    return p;
  }

  private void buildStaticGrid(JPanel panel, JLabel[][] cells, JButton[][] buttons, boolean asButtons) {
    panel.removeAll();
    panel.setLayout(new GridLayout(11, 11, 1, 1));
    panel.add(new JLabel(""));
    for (int c = 1; c <= 10; c++) panel.add(new JLabel(String.valueOf(c), JLabel.CENTER));
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      panel.add(new JLabel(String.valueOf(rows.charAt(r)), JLabel.CENTER));
      for (int c = 0; c < 10; c++) {
        if (asButtons) {
          JButton b = new JButton();
          b.setPreferredSize(new Dimension(24, 24));
          int rr = r;
          int cc = c;
          b.addActionListener(e -> fireAt(rr, cc));
          buttons[r][c] = b;
          panel.add(b);
        } else {
          JLabel lb = new JLabel("", JLabel.CENTER);
          lb.setOpaque(true);
          lb.setBackground(new Color(30, 40, 55));
          cells[r][c] = lb;
          panel.add(lb);
        }
      }
    }
  }

  private void buildAttackGrid() {
    atkGridPanel.removeAll();
    atkGridPanel.setLayout(new GridLayout(11, 11, 1, 1));
    atkGridPanel.add(new JLabel(""));
    for (int c = 1; c <= 10; c++) atkGridPanel.add(new JLabel(String.valueOf(c), JLabel.CENTER));
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      atkGridPanel.add(new JLabel(String.valueOf(rows.charAt(r)), JLabel.CENTER));
      for (int c = 0; c < 10; c++) {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(24, 24));
        int rr = r;
        int cc = c;
        b.addActionListener(e -> fireAt(rr, cc));
        atkCells[r][c] = b;
        atkGridPanel.add(b);
      }
    }
  }

  private void fireAt(int r, int c) {
    if (myId == null || !myId.equals(currentTurnId)) return;
    String tid = (String) targetCombo.getSelectedItem();
    if (tid == null) return;
    String cell = "" + GameConstants.ROWS.charAt(r) + (c + 1);
    JsonObject m = new JsonObject();
    m.addProperty("type", "fire");
    m.addProperty("target", tid);
    m.addProperty("cell", cell);
    sendJson(m);
  }

  private JPanel buildEndPanel() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    p.setBorder(new TitledBorder("Game over"));
    endText.setEditable(false);
    p.add(new JScrollPane(endText), BorderLayout.CENTER);
    JPanel f = new JPanel();
    f.add(btnPlayAgain);
    btnPlayAgain.addActionListener(
        e -> {
          JsonObject m = new JsonObject();
          m.addProperty("type", "ready_again");
          sendJson(m);
        });
    p.add(f, BorderLayout.SOUTH);
    return p;
  }

  private void doJoin() {
    myName = joinName.getText().trim();
    if (myName.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Enter a name");
      return;
    }
    if (myName.length() > 32) myName = myName.substring(0, 32);
    freshJoin = true;
    reconnectAttempts = 0;
    connectSocket();
    showCard("LOBBY");
  }

  private void connectSocket() {
    if (pingFuture != null) {
      pingFuture.cancel(false);
      pingFuture = null;
    }
    if (tcpClient != null) {
      tcpClient.close();
      tcpClient = null;
    }
    if (reconnectTimer != null) {
      reconnectTimer.stop();
      reconnectTimer = null;
    }
    String host = joinHost.getText().trim();
    int port = defaultPort;
    try {
      port = Integer.parseInt(joinPort.getText().trim());
    } catch (NumberFormatException ignored) {
    }
    netWs.setText(reconnectAttempts > 0 ? "RECONNECTING" : "CLOSED");
    tcpClient = new TcpClient(host, port);
    tcpClient.connect();
    pingFuture =
        sched.scheduleAtFixedRate(
            () -> {
              if (tcpClient != null && tcpClient.isOpen()) {
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                ping.addProperty("ts", System.currentTimeMillis());
                sendJson(ping);
              }
            },
            2,
            5,
            TimeUnit.SECONDS);
  }

  private void sendJson(JsonObject o) {
    if (tcpClient == null || !tcpClient.isOpen()) return;
    String raw = GSON.toJson(o);
    netSentCount++;
    netBytesUp += raw.length();
    netSent.setText(String.valueOf(netSentCount));
    netUp.setText(String.valueOf(netBytesUp));
    touchLast();
    System.out.println("↑ " + LocalTime.now().format(TIME_FMT) + " " + raw);
    tcpClient.send(raw);
  }

  private void touchLast() {
    netLast.setText(LocalTime.now().format(TIME_FMT));
  }

  private void scheduleReconnect() {
    if (reconnectAttempts >= 10) {
      reconnectLabel.setText("Disconnected — use Join again.");
      netWs.setText("CLOSED");
      showCard("JOIN");
      return;
    }
    reconnectAttempts++;
    // Exponential backoff: 1s, 2s, 4s, 8s, 16s, then cap at 30s.
    int delayMs = Math.min(30_000, 1_000 << Math.min(reconnectAttempts - 1, 5));
    reconnectLabel.setText(
        "Reconnecting in " + (delayMs / 1000) + "s… (attempt " + reconnectAttempts + "/10)");
    netWs.setText("RECONNECTING");
    reconnectTimer =
        new javax.swing.Timer(delayMs, ev -> { freshJoin = false; connectSocket(); });
    reconnectTimer.setRepeats(false);
    reconnectTimer.start();
  }

  private void handleInbound(String raw) {
    netRecvCount++;
    netBytesDown += raw.length();
    netRecv.setText(String.valueOf(netRecvCount));
    netDown.setText(String.valueOf(netBytesDown));
    touchLast();
    System.out.println("↓ " + LocalTime.now().format(TIME_FMT) + " " + raw);
    JsonObject msg;
    try {
      msg = JsonParser.parseString(raw).getAsJsonObject();
    } catch (Exception e) {
      return;
    }
    String type = msg.has("type") ? msg.get("type").getAsString() : "";
    if ("pong".equals(type) && msg.has("ts")) {
      long rtt = System.currentTimeMillis() - msg.get("ts").getAsLong();
      netRtt.setText(String.valueOf(rtt));
      return;
    }
    switch (type) {
      case "joined" -> {
        myId = msg.get("playerId").getAsString();
        myName = msg.get("name").getAsString();
        PREFS.put("playerId", myId);
        PREFS.put("playerName", myName);
        reconnectAttempts = 0;
        reconnectLabel.setText(" ");
        netWs.setText("OPEN");
        String ph = msg.get("phase").getAsString();
        if ("lobby".equals(ph)) {
          showCard("LOBBY");
        } else if ("placement".equals(ph)) {
          placed.clear();
          pendingStartCell = null;
          // Restore previously placed ships sent back by server on reconnect
          if (msg.has("ships") && msg.get("ships").isJsonArray()) {
            for (JsonElement el : msg.getAsJsonArray("ships")) {
              JsonObject o = el.getAsJsonObject();
              Player.Ship sh = new Player.Ship();
              sh.name = o.get("name").getAsString();
              for (JsonElement c : o.getAsJsonArray("cells")) sh.cells.add(c.getAsString());
              placed.add(sh);
            }
          }
          updatePlaceHint();
          refreshPlaceGrid();
          showCard("PLACE");
        } else if ("playing".equals(ph)) {
          showCard("GAME");
        }
      }
      case "lobby_update" -> {
        String ph = msg.get("phase").getAsString();
        if ("placement".equals(ph)) {
          if (!"PLACE".equals(currentCard)) {
            // Only reset when first transitioning into placement, not on every broadcast
            placed.clear();
            pendingStartCell = null;
            updatePlaceHint();
            refreshPlaceGrid();
            showCard("PLACE");
          }
        } else {
          showCard("LOBBY");
          gameStarted = false;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : msg.getAsJsonArray("players")) {
          JsonObject p = el.getAsJsonObject();
          sb.append(p.get("name").getAsString());
          if (p.get("ready").getAsBoolean()) sb.append(" (ready)");
          sb.append('\n');
        }
        lobbyList.setText(sb.toString());
      }
      case "game_start" -> {
        showCard("GAME");
        if (!gameStarted) {
          gameLog.setText("");
          appendLog("Game started.");
          gameStarted = true;
        } else {
          appendLog("Reconnected to game in progress.");
        }
        lastYou = msg.getAsJsonObject("you");
        renderGameFromState();
      }
      case "state" -> {
        lastYou = msg.getAsJsonObject("you");
        renderGameFromState();
      }
      case "your_turn" -> {
        currentTurnId = msg.get("playerId").getAsString();
        renderAttackFromState();
      }
      case "fire_result" -> {
        JsonObject sh = msg.getAsJsonObject("shooter");
        JsonObject tg = msg.getAsJsonObject("target");
        String cell = msg.get("cell").getAsString();
        String res = msg.get("result").getAsString();
        String sunk =
            msg.has("shipSunk") && !(msg.get("shipSunk") instanceof JsonNull)
                ? " Sunk " + msg.get("shipSunk").getAsString() + "!"
                : "";
        String next = "hit".equals(res) ? sh.get("name").getAsString() + " fires again." : "Turn passes.";
        appendLog(
            sh.get("name").getAsString()
                + " → "
                + tg.get("name").getAsString()
                + " @ "
                + cell
                + " — "
                + res.toUpperCase()
                + "!"
                + sunk
                + " "
                + next);
      }
      case "player_eliminated" -> appendLog("Eliminated: " + msg.get("playerId").getAsString());
      case "game_over" -> {
        cardLayout.show(cards, "END");
        StringBuilder sb = new StringBuilder();
        if (msg.has("winner") && !(msg.get("winner") instanceof JsonNull)) {
          JsonObject w = msg.getAsJsonObject("winner");
          sb.append("Winner: ").append(w.get("name").getAsString()).append('\n');
        }
        for (JsonElement el : msg.getAsJsonArray("standings")) {
          JsonObject s = el.getAsJsonObject();
          sb.append("#")
              .append(s.get("rank").getAsInt())
              .append(" ")
              .append(s.get("name").getAsString())
              .append(s.get("eliminated").getAsBoolean() ? " (eliminated)" : "")
              .append('\n');
        }
        endText.setText(sb.toString());
      }
      case "error" -> {
        String errMsg = msg.get("message").getAsString();
        appendLog("Error: " + errMsg);
        // Stale ID rejected — wipe it so the next connect attempt is a fresh join
        if ("Game already started.".equals(errMsg) || "Name mismatch for reconnect.".equals(errMsg)) {
          PREFS.remove("playerId");
          PREFS.remove("playerName");
        }
        JOptionPane.showMessageDialog(this, errMsg);
      }
      default -> {}
    }
  }

  private void appendLog(String line) {
    gameLog.append("[" + LocalTime.now().format(TIME_FMT) + "] " + line + "\n");
  }

  private void renderGameFromState() {
    if (lastYou == null) return;
    JsonObject ob = lastYou.getAsJsonObject("ownBoard");
    Set<String> hits = jsonToStringSet(ob.getAsJsonArray("hits"));
    Set<String> misses = jsonToStringSet(ob.getAsJsonArray("misses"));
    Set<String> shipCells = new HashSet<>();
    if (lastYou.has("ownShips") && lastYou.get("ownShips").isJsonArray()) {
      for (JsonElement el : lastYou.getAsJsonArray("ownShips")) {
        for (JsonElement c : el.getAsJsonObject().getAsJsonArray("cells")) {
          shipCells.add(c.getAsString());
        }
      }
    }
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      for (int c = 0; c < 10; c++) {
        String key = "" + rows.charAt(r) + (c + 1);
        JLabel lb = ownLabels[r][c];
        lb.setText("");
        if (hits.contains(key)) {
          lb.setBackground(new Color(200, 70, 70));
          lb.setText("×");
        } else if (misses.contains(key)) {
          lb.setBackground(new Color(100, 100, 110));
          lb.setText("·");
        } else if (shipCells.contains(key)) {
          lb.setBackground(new Color(50, 130, 70));
        } else {
          lb.setBackground(new Color(30, 40, 55));
        }
      }
    }
    targetIdToName.clear();
    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
    if (lastYou.has("livingOpponents")) {
      for (JsonElement el : lastYou.getAsJsonArray("livingOpponents")) {
        JsonObject o = el.getAsJsonObject();
        String id = o.get("id").getAsString();
        String nm = o.get("name").getAsString();
        targetIdToName.put(id, nm);
        model.addElement(id);
      }
    }
    String prev = (String) targetCombo.getSelectedItem();
    targetCombo.setModel(model);
    if (prev != null && model.getIndexOf(prev) >= 0) targetCombo.setSelectedItem(prev);
    else if (model.getSize() > 0) targetCombo.setSelectedIndex(0);
    StringBuilder sb = new StringBuilder();
    for (JsonElement el : lastYou.getAsJsonArray("shipCounts")) {
      JsonObject s = el.getAsJsonObject();
      sb.append(s.get("name").getAsString())
          .append(": ")
          .append(s.get("remaining").getAsInt())
          .append(" cells");
      if (s.get("eliminated").getAsBoolean()) sb.append(" OUT");
      if (s.get("disconnected").getAsBoolean()) sb.append(" DC");
      sb.append("  ");
    }
    statusBar.setText(sb.toString());
    renderAttackFromState();
  }

  private static Set<String> jsonToStringSet(JsonArray arr) {
    Set<String> s = new HashSet<>();
    if (arr == null) return s;
    for (JsonElement e : arr) s.add(e.getAsString());
    return s;
  }

  private void renderAttackFromState() {
    if (lastYou == null) return;
    String tid = (String) targetCombo.getSelectedItem();
    Map<String, String> cells = new java.util.HashMap<>();
    if (tid != null && lastYou.has("attacks")) {
      for (JsonElement el : lastYou.getAsJsonArray("attacks")) {
        JsonObject a = el.getAsJsonObject();
        if (!tid.equals(a.get("targetId").getAsString())) continue;
        for (JsonElement c : a.getAsJsonArray("cells")) {
          JsonObject o = c.getAsJsonObject();
          cells.put(o.get("cell").getAsString(), o.get("result").getAsString());
        }
      }
    }
    boolean myTurn = myId != null && myId.equals(currentTurnId);
    String rows = GameConstants.ROWS;
    for (int r = 0; r < 10; r++) {
      for (int c = 0; c < 10; c++) {
        String key = "" + rows.charAt(r) + (c + 1);
        JButton b = atkCells[r][c];
        b.setBackground(null);
        b.setText("");
        b.setEnabled(myTurn);
        String res = cells.get(key);
        if ("hit".equals(res)) {
          b.setBackground(new Color(200, 70, 70));
          b.setText("×");
        } else if ("miss".equals(res)) {
          b.setBackground(new Color(100, 100, 110));
          b.setText("·");
        }
      }
    }
  }

  private final class TcpClient {
    private final String host;
    private final int port;
    private final long encryptionKey = ThreadLocalRandom.current().nextLong();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open;
    private volatile boolean closedByOwner;

    TcpClient(String host, int port) {
      this.host = host;
      this.port = port;
    }

    void connect() {
      Thread t = new Thread(() -> {
        try {
          socket = new Socket(host, port);
          socket.setTcpNoDelay(true);
          out = socket.getOutputStream();
          open = true;
          SwingUtilities.invokeLater(() -> {
            netWs.setText("OPEN");
            JsonObject m = new JsonObject();
            m.addProperty("type", "join");
            if (!freshJoin) {
              // Automatic reconnect after disconnect: try to resume the existing session.
              String pid = PREFS.get("playerId", null);
              String savedName = PREFS.get("playerName", myName);
              if (pid != null) {
                m.addProperty("name", savedName);
                m.addProperty("playerId", pid);
              } else {
                m.addProperty("name", myName);
              }
            } else {
              // User clicked Connect — always start fresh so that multiple clients
              // on the same machine each get their own player slot.
              m.addProperty("name", myName);
            }
            sendJson(m);
          });
          DataInputStream dis = new DataInputStream(socket.getInputStream());
          while (true) {
            int idLen  = dis.readInt(); byte[] idB  = new byte[idLen];  dis.readFully(idB);
            int sidLen = dis.readInt(); byte[] sidB = new byte[sidLen]; dis.readFully(sidB);
            long key   = dis.readLong();
            int datLen = dis.readInt(); byte[] datB = new byte[datLen]; dis.readFully(datB);
            String data = new String(Packet.xorEncrypt(datB, key), StandardCharsets.UTF_8);
            SwingUtilities.invokeLater(() -> handleInbound(data));
          }
        } catch (IOException e) {
          // connection failed or broken — fall through to finally
        } finally {
          open = false;
          final boolean skip = closedByOwner;
          SwingUtilities.invokeLater(() -> {
            if (!skip) {
              netWs.setText("CLOSED");
              scheduleReconnect();
            }
          });
        }
      }, "battleship-tcp");
      t.setDaemon(true);
      t.start();
    }

    synchronized void send(String raw) {
      OutputStream o = out;
      if (o == null) return;
      try {
        ByteBuffer buf = new Packet("client", encryptionKey, raw).toBytes();
        o.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
        o.flush();
      } catch (IOException e) {
        // Close socket so the read loop also dies; leave closedByOwner=false so
        // the finally block schedules a reconnect.
        out = null;
        open = false;
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
      }
    }

    boolean isOpen() { return open; }

    void close() {
      // Caller-initiated close: suppress the automatic scheduleReconnect.
      closedByOwner = true;
      open = false;
      try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
  }
}
