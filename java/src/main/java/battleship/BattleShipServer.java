package battleship;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** WebSocket server wrapping {@link GameEngine}. */
public final class BattleShipServer extends WebSocketServer {
  private final GameEngine engine;

  public BattleShipServer(int port) {
    super(new InetSocketAddress(port));
    this.engine = new GameEngine(Executors.newSingleThreadScheduledExecutor());
    setReuseAddr(true);
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {}

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    engine.onSocketClosed(conn);
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    engine.handleMessage(conn, message);
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    ex.printStackTrace();
  }

  @Override
  public void onStart() {
    InetSocketAddress a = getAddress();
    String host = a.getAddress().getHostAddress();
    System.out.println("Battleship WebSocket server ws://" + host + ":" + a.getPort());
  }
}
