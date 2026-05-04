# Battleship (Java)

Same rules and WebSocket JSON protocol as the Node `server.js` edition. Run the **server** in one terminal and **three or more clients** (Swing) to play.

## Build & run

From **`java/`**:

```bash
cd java
mvn -q compile
```

From the **repo root** (`BattleShip/`), use `-pl java`:

```bash
mvn -q -pl java compile
```

**Server** (WebSocket on TCP port):

```bash
# inside java/
mvn -q exec:java -Dexec.args="server --port 3000"
# from repo root
mvn -q -pl java exec:java -Dexec.args="server --port 3000"
```

**Client** (Swing UI; start 3–4 instances for a full game):

```bash
mvn -q exec:java -Dexec.args="client --host 127.0.0.1 --port 3000"
# from root
mvn -q -pl java exec:java -Dexec.args="client --host 127.0.0.1 --port 3000"
```

The packaged `target/battleship-java-1.0.0.jar` is a **thin** JAR; run with classpath, e.g.:

```bash
mvn -q dependency:copy-dependencies -DoutputDirectory=target/lib
java -cp "target/classes:target/lib/*" battleship.BattleShipApp server --port 3000
```

## Modules

| Class | Role |
|-------|------|
| `BattleShipApp` | `server` or Swing `client` entry |
| `BattleShipServer` | `WebSocketServer` from Java-WebSocket |
| `GameEngine` | Lobby, placement, combat, reconnect timers |
| `BattleShipClientFrame` | Swing UI + `WebSocketClient` |

Dependencies: **Java-WebSocket**, **Gson** (see `pom.xml`).
