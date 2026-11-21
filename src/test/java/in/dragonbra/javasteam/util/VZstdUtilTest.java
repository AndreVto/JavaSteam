package in.dragonbra.javasteam.util;

import com.github.luben.zstd.Zstd;
import in.dragonbra.javasteam.steam.cdn.DepotChunk;
import in.dragonbra.javasteam.types.ChunkData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

class VZstdUtilTest {
    @Test
    void decompressesLogicalStreamWithoutReadingTrailingPadding() throws Exception {
        var plain = "Steam depot compressed data".repeat(24).getBytes(StandardCharsets.UTF_8);
        var compressed = Zstd.compress(plain);
        var stream = new byte[8 + compressed.length + 15];
        var data = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, 0x615A5356);
        var crc = new CRC32();
        crc.update(plain);
        data.putInt(4, (int) crc.getValue());
        System.arraycopy(compressed, 0, stream, 8, compressed.length);
        data.putInt(stream.length - 15, (int) crc.getValue());
        data.putInt(stream.length - 11, plain.length);
        stream[stream.length - 3] = 'z';
        stream[stream.length - 2] = 's';
        stream[stream.length - 1] = 'v';

        var padded = Arrays.copyOf(stream, stream.length + 20);
        var output = new byte[plain.length];
        Assertions.assertEquals(plain.length, VZstdUtil.decompress(padded, stream.length, output, true));
        Assertions.assertArrayEquals(plain, output);

        var chunk = new ChunkData(new byte[20], Adler32.calculate(plain), 0, stream.length, plain.length);
        Assertions.assertEquals(plain.length, DepotChunk.processDecrypted(chunk, stream, new byte[plain.length]));
        var corrupted = new ChunkData(new byte[20], chunk.getChecksum() ^ 1, 0, stream.length, plain.length);
        Assertions.assertThrows(IOException.class, () -> DepotChunk.processDecrypted(corrupted, stream, new byte[plain.length]));

        Assertions.assertThrows(IOException.class, () -> VZstdUtil.decompress(padded, output, true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> VZstdUtil.decompress(padded, padded.length + 1, output, false));
    }
}
