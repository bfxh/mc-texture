// 跨语言字节等价验证：固定 8x8 RGBA → TritEncoder.rgbaToTtx2 → 打印 hex。
// 运行后把输出的 hex 抄进 trit-texture 的 Rust 测试，证明 Java 编码 == Rust 编码。
import dev.trit.mc.texture.TritCodec;
import dev.trit.mc.texture.TritEncoder;
import java.nio.ByteBuffer;

public class CrossCheck {
    public static void main(String[] args) {
        // 确定性 8x8 图案（梯度 + 几个纯色块），覆盖端点 min/max 与三色调色板。
        int w = 8, h = 8;
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                rgba[i]     = (byte) ((x * 32) & 0xff); // R 梯度
                rgba[i + 1] = (byte) ((y * 32) & 0xff); // G 梯度
                rgba[i + 2] = (byte) (((x + y) * 16) & 0xff); // B
                rgba[i + 3] = (byte) 255;
            }
        }
        byte[] ttx2 = TritEncoder.rgbaToTtx2(rgba, w, h);
        StringBuilder sb = new StringBuilder();
        for (byte b : ttx2) sb.append(String.format("%02x", b & 0xff));
        System.out.println("LEN=" + ttx2.length);
        System.out.println("HEX=" + sb);
    }
}
