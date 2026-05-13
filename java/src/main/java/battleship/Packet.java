package battleship;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Packet {
    private final String id;
    private final String senderId;
    private final long encryptionKey;
    private final String data;

    public Packet(String senderId, long encryptionKey, String data) {
        this.id            = UUID.randomUUID().toString();
        this.senderId      = senderId;
        this.encryptionKey = encryptionKey;
        this.data          = data;
    }

    private Packet(String id, String senderId, long encryptionKey, String data) {
        this.id            = id;
        this.senderId      = senderId;
        this.encryptionKey = encryptionKey;
        this.data          = data;
    }

    public String getId()            { return id; }
    public String getSenderId()      { return senderId; }
    public long   getEncryptionKey() { return encryptionKey; }
    public String getData()          { return data; }

    /** Serialize to a length-prefixed binary frame with the data field encrypted. */
    public ByteBuffer toBytes() {
        byte[] idB  = id.getBytes(StandardCharsets.UTF_8);
        byte[] sidB = senderId.getBytes(StandardCharsets.UTF_8);
        byte[] datB = xorEncrypt(data.getBytes(StandardCharsets.UTF_8), encryptionKey);

        ByteBuffer buf = ByteBuffer.allocate(4 + idB.length + 4 + sidB.length + 8 + 4 + datB.length);
        writeField(buf, idB);
        writeField(buf, sidB);
        buf.putLong(encryptionKey);
        writeField(buf, datB);
        buf.flip();
        return buf;
    }

    /**
     * Parse a binary frame and decrypt the data field.
     * Advances buf's position past the packet on success.
     * Resets buf's position and returns null if the frame is incomplete or malformed.
     */
    public static Packet fromBytes(ByteBuffer buf) {
        buf.mark();
        try {
            String id            = readField(buf);
            String senderId      = readField(buf);
            long   encryptionKey = buf.getLong();
            byte[] datB          = readRawField(buf);
            String data          = new String(xorEncrypt(datB, encryptionKey), StandardCharsets.UTF_8);
            return new Packet(id, senderId, encryptionKey, data);
        } catch (Exception e) {
            buf.reset();
            return null;
        }
    }

    private static void writeField(ByteBuffer buf, byte[] field) {
        buf.putInt(field.length);
        buf.put(field);
    }

    private static String readField(ByteBuffer buf) {
        return new String(readRawField(buf), StandardCharsets.UTF_8);
    }

    private static byte[] readRawField(ByteBuffer buf) {
        byte[] bytes = new byte[buf.getInt()];
        buf.get(bytes);
        return bytes;
    }

    /** XOR each byte of data against the keystream produced by xorShift. Symmetric — same call encrypts and decrypts. */
    static byte[] xorEncrypt(byte[] data, long key) {
        byte[] out = new byte[data.length];
        long seed = key;
        int i = 0;
        while (i < data.length) {
            seed = xorShift(seed);
            for (int shift = 56; shift >= 0 && i < data.length; shift -= 8, i++) {
                out[i] = (byte) (data[i] ^ (seed >>> shift));
            }
        }
        return out;
    }

    static long xorShift(long xorSeed) {
        xorSeed ^= xorSeed << 13;
        xorSeed ^= xorSeed >>> 7;
        xorSeed ^= xorSeed << 17;
        return xorSeed;
    }
}
