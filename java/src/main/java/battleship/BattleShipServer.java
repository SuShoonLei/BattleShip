package battleship;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class BattleShipServer {
  private final int port;
  private final GameEngine engine;

  public BattleShipServer(int port) {
    this.port = port;
    this.engine = new GameEngine(Executors.newSingleThreadScheduledExecutor());
  }

  public static void main(String[] args) {
    int port = 3000;
    for (int i = 0; i < args.length; i++) {
      if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      }
    }
    new BattleShipServer(port).start();
  }

  public void start() {
    try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
         Selector selector = Selector.open()) {

      serverChannel.bind(new InetSocketAddress(port));
      serverChannel.configureBlocking(false);
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);
      System.out.println("Battleship server listening on port " + port);

      // reuse the same NioConnection per channel so attachment (player ID) persists
      Map<SocketChannel, NioConnection> connections = new HashMap<>();
      Map<SocketChannel, ByteArrayOutputStream> buffers = new HashMap<>();
      ByteBuffer readBuf = ByteBuffer.allocate(4096);

      while (true) {
        selector.select(5_000);
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
          SelectionKey key = it.next();
          it.remove();

          if (key.isAcceptable()) {
            SocketChannel client = serverChannel.accept();
            if (client != null) {
              client.configureBlocking(false);
              client.register(selector, SelectionKey.OP_READ);
              connections.put(client, new NioConnection(client, "server", ThreadLocalRandom.current().nextLong()));
              buffers.put(client, new ByteArrayOutputStream());
            }

          } else if (key.isReadable()) {
            SocketChannel client = (SocketChannel) key.channel();
            NioConnection conn = connections.get(client);
            readBuf.clear();
            int n;
            try {
              n = client.read(readBuf);
            } catch (IOException e) {
              n = -1;
            }
            if (n == -1) {
              engine.onSocketClosed(conn);
              connections.remove(client);
              buffers.remove(client);
              key.cancel();
              client.close();
            } else {
              conn.touch();
              readBuf.flip();
              ByteArrayOutputStream acc = buffers.get(client);
              byte[] chunk = new byte[readBuf.remaining()];
              readBuf.get(chunk);
              acc.write(chunk);

              byte[] raw = acc.toByteArray();
              ByteBuffer bb = ByteBuffer.wrap(raw);
              while (bb.hasRemaining()) {
                Packet pkt = Packet.fromBytes(bb);
                if (pkt == null) break;
                engine.handleMessage(conn, pkt.getData());
              }
              acc.reset();
              if (bb.hasRemaining()) acc.write(raw, bb.position(), bb.remaining());
            }
          }
        }

        // Drop connections that haven't sent any data (including pings) within the dead-connection window.
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<SocketChannel, NioConnection>> connIt = connections.entrySet().iterator();
        while (connIt.hasNext()) {
          Map.Entry<SocketChannel, NioConnection> entry = connIt.next();
          NioConnection conn = entry.getValue();
          if (now - conn.getLastActiveMs() > GameConstants.DEAD_CONNECTION_MS) {
            SocketChannel ch = entry.getKey();
            System.out.println("Dead connection swept: " + ch.getRemoteAddress());
            engine.onSocketClosed(conn);
            connIt.remove();
            buffers.remove(ch);
            SelectionKey sk = ch.keyFor(selector);
            if (sk != null) sk.cancel();
            try { ch.close(); } catch (IOException ignored) {}
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
