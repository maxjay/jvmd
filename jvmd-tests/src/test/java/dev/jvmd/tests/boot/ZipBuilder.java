package dev.jvmd.tests.boot;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Assembles an archive byte by byte, so a test can say exactly what the central directory claims: Zip64 end-of-central-directory
 * records, Zip64 extra fields, and sizes that disagree with the data. The layouts are those of the ZIP application note.
 */
final class ZipBuilder {
    private record Entry(String name, byte[] stored, int method, long crc, long declaredSize, long declaredCompressed, boolean zip64Extra) { }

    private final List<Entry> entries = new ArrayList<>();

    /** A deflated entry whose central directory states the true sizes. */
    ZipBuilder deflated(String name, byte[] data, boolean zip64Extra) { return deflated(name, data, zip64Extra, -1); }

    /** A deflated entry whose central directory states {@code declaredSize} (or the true size if negative) as its inflated size. */
    ZipBuilder deflated(String name, byte[] data, boolean zip64Extra, long declaredSize) {
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        var crc = new CRC32();
        crc.update(data);
        entries.add(new Entry(name, out.toByteArray(), 8, crc.getValue(), declaredSize >= 0 ? declaredSize : data.length, out.size(), zip64Extra));
        return this;
    }

    /** A stored entry whose central directory states {@code declaredSize} (or the true size if negative). */
    ZipBuilder stored(String name, byte[] data, long declaredSize) {
        var crc = new CRC32();
        crc.update(data);
        entries.add(new Entry(name, data, 0, crc.getValue(), declaredSize >= 0 ? declaredSize : data.length, data.length, false));
        return this;
    }

    byte[] build(boolean zip64EndOfCentralDirectory) {
        var out = new ByteArrayOutputStream();
        var offsets = new ArrayList<Long>();
        for (var e : entries) {
            offsets.add((long) out.size());
            var name = e.name().getBytes(StandardCharsets.UTF_8);
            var header = le(30);
            header.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) e.method()).putShort((short) 0).putShort((short) 0);
            header.putInt((int) e.crc()).putInt(e.stored().length).putInt(e.stored().length).putShort((short) name.length).putShort((short) 0);
            out.writeBytes(header.array());
            out.writeBytes(name);
            out.writeBytes(e.stored());
        }
        long directory = out.size();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var name = e.name().getBytes(StandardCharsets.UTF_8);
            int extra = e.zip64Extra() ? 28 : 0;
            var h = le(46);
            h.putInt(0x02014b50).putShort((short) 45).putShort((short) 45).putShort((short) 0).putShort((short) e.method()).putShort((short) 0).putShort((short) 0);
            h.putInt((int) e.crc());
            if (e.zip64Extra()) h.putInt(-1).putInt(-1); else h.putInt((int) e.declaredCompressed()).putInt((int) e.declaredSize());
            h.putShort((short) name.length).putShort((short) extra).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0);
            h.putInt(e.zip64Extra() ? -1 : offsets.get(i).intValue());
            out.writeBytes(h.array());
            out.writeBytes(name);
            if (e.zip64Extra()) {
                // id 0x0001, 24 data bytes: uncompressed size, compressed size, local header offset.
                var x = le(28);
                x.putShort((short) 1).putShort((short) 24).putLong(e.declaredSize()).putLong(e.declaredCompressed()).putLong(offsets.get(i));
                out.writeBytes(x.array());
            }
        }
        long directorySize = out.size() - directory;
        if (zip64EndOfCentralDirectory) {
            long record = out.size();
            var z = le(56);
            z.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0).putLong(entries.size()).putLong(entries.size());
            z.putLong(directorySize).putLong(directory);
            out.writeBytes(z.array());
            var locator = le(20);
            locator.putInt(0x07064b50).putInt(0).putLong(record).putInt(1);
            out.writeBytes(locator.array());
            var end = le(22);
            end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) -1).putInt(-1).putInt(-1).putShort((short) 0);
            out.writeBytes(end.array());
        } else {
            var end = le(22);
            end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) entries.size()).putShort((short) entries.size());
            end.putInt((int) directorySize).putInt((int) directory).putShort((short) 0);
            out.writeBytes(end.array());
        }
        return out.toByteArray();
    }

    private static ByteBuffer le(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
}
