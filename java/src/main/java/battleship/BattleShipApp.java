package battleship;

import javax.swing.SwingUtilities;


public final class BattleShipApp {
  private BattleShipApp() {}

  public static void main(String[] args) {
    if (args.length > 0 && "server".equalsIgnoreCase(args[0])) {
      int port = 3000;
      for (int i = 1; i < args.length; i++) {
        if ("--port".equals(args[i]) && i + 1 < args.length) {
          port = Integer.parseInt(args[++i]);
        }
      }
      BattleShipServer server = new BattleShipServer(port);
      server.start();
      return;
    }
    String host = "localhost";
    int port = 3000;
    for (int i = 0; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) host = args[++i];
      else if ("--port".equals(args[i]) && i + 1 < args.length) 
        port = Integer.parseInt(args[++i]);
    }

    String finalHost = host;
    int finalPort = port;
    SwingUtilities.invokeLater(() -> new BattleShipClientFrame(finalHost, finalPort).setVisible(true));
  }
}
