package battleship;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class CellUtil {
  private CellUtil() {}

  public static final Set<String> ALL_CELLS = buildAllCells();

  private static Set<String> buildAllCells() {
    Set<String> s = new HashSet<>();
    for (char r : GameConstants.ROWS.toCharArray()) {
      for (int c = 1; c <= 10; c++) s.add("" + r + c);
    }
    return Set.copyOf(s);
  }

  public record ParsedCell(char row, int col, String key) {}

  public static Optional<ParsedCell> parseCell(String cell) {
    if (cell == null || cell.length() < 2) return Optional.empty();
    char row = Character.toUpperCase(cell.charAt(0));
    if (GameConstants.ROWS.indexOf(row) < 0) return Optional.empty();
    int col;
    try {
      col = Integer.parseInt(cell.substring(1));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    if (col < 1 || col > 10) return Optional.empty();
    return Optional.of(new ParsedCell(row, col, "" + row + col));
  }

  public static String shotKey(String shooter, String target) {
    return shooter + "::" + target;
  }
}
