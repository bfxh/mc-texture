package dev.trit.mc.texture;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 验证 Java 解码器与 Rust（trit-texture）逐像素一致：
 *  1) 同一图的 .ttx1 与 .ttx2 解码结果必须完全相同（ttx1_to_ttx2 块数据原样进单级 TEX）
 *  2) 两者都必须与磁盘上 Rust 解码产物 *_roundtrip.png 逐字节一致
 *  *_roundtrip.png 即 Rust decode → PNG 的权威输出，作为 ground truth。
 */
public final class Verify {
    private static final List<String> NAMES =
        Arrays.asList("stone", "dirt", "grass_block_top", "oak_log", "coal_ore", "water", "lava");

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0]
            : "D:/KF/.yanagent/mc-texture/pack/opt/ttx";
        String outDir = dir + "/verify-java";
        new File(outDir).mkdirs();

        int pass = 0, fail = 0;
        System.out.printf("verify dir: %s%n", dir);
        for (String name : NAMES) {
            byte[] pngBytes = Files.readAllBytes(new File(dir, name + "_roundtrip.png").toPath());
            byte[] ttx1Bytes = Files.readAllBytes(new File(dir, name + ".ttx1").toPath());
            byte[] ttx2Bytes = Files.readAllBytes(new File(dir, name + ".ttx2").toPath());

            PngIO.Rgba truth = PngIO.read(dir + "/" + name + "_roundtrip.png");

            TritCodec.TritTexture t1 = TritCodec.fromTtx1(ttx1Bytes);
            TritCodec.TritTexture t2 = TritCodec.fromTtx2(ttx2Bytes).levels[0];

            byte[] dec1 = TritCodec.decodeRgba(t1);
            byte[] dec2 = TritCodec.decodeRgba(t2);

            // 把解码结果也写成 PNG 供肉眼核对
            PngIO.write(new PngIO.Rgba(t1.width, t1.height, dec1), outDir + "/" + name + "_java_ttx1.png");
            PngIO.write(new PngIO.Rgba(t2.width, t2.height, dec2), outDir + "/" + name + "_java_ttx2.png");

            boolean same12 = Arrays.equals(dec1, dec2);
            boolean match1 = Arrays.equals(dec1, truth.rgba);
            boolean match2 = Arrays.equals(dec2, truth.rgba);
            double psnr1 = PngIO.psnrRgb(dec1, truth.rgba);
            double psnr2 = PngIO.psnrRgb(dec2, truth.rgba);

            boolean ok = same12 && match1 && match2;
            if (ok) pass++; else fail++;
            System.out.printf("%-16s %dx%d  ttx1==ttx2:%b  match(roundtrip):%b  PSNR1=%.2f PSNR2=%.2f  %s%n",
                name, t1.width, t1.height, same12, match1 && match2, psnr1, psnr2, ok ? "OK" : "FAIL");
        }
        System.out.printf("%n==== %d passed, %d failed ====%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
