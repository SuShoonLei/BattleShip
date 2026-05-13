package battleship;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
public final class Player {
  public final String id;
  public final String name;
  public Connection conn;
  public Long disconnectedAt;
  public ScheduledFuture<?> disconnectTimer;
  public List<Ship> ships;
  public boolean placementDone;
  public boolean eliminated;

  public Player(String id, String name) {
    this.id = id;
    this.name = name;
    this.ships = null;
    this.placementDone = false;
    this.eliminated = false;
    this.conn = null;
    this.disconnectedAt = null;
    this.disconnectTimer = null;
  }

  public static final class Ship {
    public String name;
    public List<String> cells = new ArrayList<>();

    public Ship copy() {
      Ship s = new Ship();
      s.name = name;
      s.cells = new ArrayList<>(cells);
      return s;
    }
  }
}
