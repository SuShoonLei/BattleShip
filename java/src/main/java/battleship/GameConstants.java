package battleship;

import java.util.List;

/**
 * Same rules as the Node edition: 3–4 players, 10×10, standard fleet.
 */
public final class GameConstants {
  private GameConstants() {}

  public static final int MIN_PLAYERS = 3;
  public static final int MAX_PLAYERS = 4;
  public static final long RECONNECT_MS = 30_000;
  public static final long LOBBY_DEBOUNCE_MS = 2_000;
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
