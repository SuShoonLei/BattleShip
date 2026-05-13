package battleship;

import java.util.List;

/*
 * Same rules as the Node edition: 3–4 players, 10×10, standard fleet.
 */

public final class GameConstants {
  private GameConstants() {}

  public static final int MIN_PLAYERS = 3;
  public static final int MAX_PLAYERS = 4;
  public static final long RECONNECT_MS = 30_000;
  public static final long LOBBY_DEBOUNCE_MS = 2_000;
  /** Server closes a connection that has sent no data for this long (ms). Must be > ping interval (5 s). */
  public static final long DEAD_CONNECTION_MS = 15_000;
  /** Auto-advance the turn if the active player hasn't fired within this window (ms). */
  public static final long TURN_TIMEOUT_MS = 60_000;
  public static final String ROWS = "ABCDEFGHIJ";

  public record FleetSpec(String name, int len) {}

  public static final List<FleetSpec> FLEET = List.of(
      new FleetSpec("Carrier", 5),
      new FleetSpec("Battleship", 4),
      new FleetSpec("Cruiser", 3),
      new FleetSpec("Submarine", 3),
      new FleetSpec("Destroyer", 2)
  );
}
