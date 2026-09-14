package dev.jvmd.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Implements 12.3: bounded Content-Length framing, including fragmented UTF-8 messages. */
public final class Framing {
    public static final int MAX_FRAME = 8 * 1024 * 1024;
    private Framing() { }
    public static byte[] read(InputStream input) throws IOException {
        int length = -1, headerBytes = 0;
        while (true) {
            var line = new ByteArrayOutputStream();
            int c;
            while ((c = input.read()) != -1 && c != '\n') {
                if (++headerBytes > 8192) throw new IOException("Headers too large");
                line.write(c);
            }
            if (c == -1) {
                if (headerBytes == 0) return null;
                throw new EOFException("Incomplete header");
            }
            var header = line.toString(StandardCharsets.US_ASCII);
            if (!header.endsWith("\r")) throw new IOException("Expected CRLF");
            header = header.substring(0, header.length() - 1);
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon < 1) throw new IOException("Invalid header");
            if (header.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                if (length != -1) throw new IOException("Duplicate Content-Length");
                try { length = Integer.parseInt(header.substring(colon + 1).trim()); }
                catch (NumberFormatException e) { throw new IOException("Invalid Content-Length", e); }
                if (length < 0 || length > MAX_FRAME) throw new IOException("Frame too large");
            }
        }
        if (length < 0) throw new IOException("Missing Content-Length");
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new EOFException("Incomplete body");
        return body;
    }
    public static void write(OutputStream output, byte[] body) throws IOException {
        if (body.length > MAX_FRAME) throw new IOException("Frame too large");
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
