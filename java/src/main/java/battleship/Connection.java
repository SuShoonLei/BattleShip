package battleship;

public interface Connection {
  void send(String json);
  boolean isOpen();
  void setAttachment(Object o);
  <T> T getAttachment();
}
