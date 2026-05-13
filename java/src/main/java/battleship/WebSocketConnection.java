package battleship;

import org.java_websocket.WebSocket;

public final class WebSocketConnection implements Connection {
  private final WebSocket ws;

  public WebSocketConnection(WebSocket ws) { this.ws = ws; }

  @Override public void send(String json)       { if (ws.isOpen()) ws.send(json); }
  @Override public boolean isOpen()             { return ws.isOpen(); }
  @Override public void setAttachment(Object o) { ws.setAttachment(o); }
  @Override @SuppressWarnings("unchecked")
            public <T> T getAttachment()        { return ws.getAttachment(); }
}
