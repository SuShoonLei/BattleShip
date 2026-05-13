package battleship;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public final class NioConnection implements Connection {
    private final SocketChannel channel;
    private final String senderId;
    private final long encryptionKey;
    private Object attachment;
    private volatile long lastActiveMs = System.currentTimeMillis();

    public NioConnection(SocketChannel channel, String senderId, long encryptionKey) {
        this.channel       = channel;
        this.senderId      = senderId;
        this.encryptionKey = encryptionKey;
    }

    @Override public void send(String json) {
        ByteBuffer buf = new Packet(senderId, encryptionKey, json).toBytes();
        try {
            while (buf.hasRemaining()) channel.write(buf);
        } catch (IOException e) {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    public void touch() { lastActiveMs = System.currentTimeMillis(); }
    public long getLastActiveMs() { return lastActiveMs; }

    @Override public boolean isOpen() { return channel.isOpen(); }

    @Override public void setAttachment(Object o) { this.attachment = o; }

    @Override @SuppressWarnings("unchecked")
    public <T> T getAttachment() { return (T) attachment; }
}
