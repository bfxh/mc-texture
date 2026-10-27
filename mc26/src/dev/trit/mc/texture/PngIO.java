package dev.trit.mc.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * RGBA(8bit/通道) 图像 + PNG 读写（仅依赖 JDK）。
 *
 * 【修复】旧版 read() 把 alpha 恒写成 255 —— 那在做材质包优化时是致命的：
 *   玻璃、树叶、水、蜘蛛网等带透明通道的纹理会被变成不透明方块。
 *   这里完整保留 alpha。
 */
public final class PngIO {

    public static final class Rgba {
        public final int width, height;
        public final byte[] rgba; // width*height*4, 行主序 RGBA
        public Rgba(int width, int height, byte[] rgba) {
            this.width = width; this.height = height; this.rgba = rgba;
        }
    }

    /** 读 PNG → RGBA（完整保留 alpha）。 */
    public static Rgba read(String path) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) throw new IOException("cannot read image: " + path);
        return fromBuffered(img);
    }

    public static Rgba read(InputStream in) throws IOException {
        BufferedImage img = ImageIO.read(in);
        if (img == null) throw new IOException("cannot decode png from stream");
        return fromBuffered(img);
    }

    public static Rgba readBytes(byte[] png) throws IOException {
        return read(new ByteArrayInputStream(png));
    }

    private static Rgba fromBuffered(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int off = (y * w + x) * 4;
                rgba[off] = (byte) (argb >> 16);
                rgba[off + 1] = (byte) (argb >> 8);
                rgba[off + 2] = (byte) argb;
                rgba[off + 3] = (byte) (argb >>> 24);   // 保留 alpha
            }
        }
        return new Rgba(w, h, rgba);
    }

    /** 写 RGBA → PNG。 */
    public static void write(Rgba img, String path) throws IOException {
        if (!ImageIO.write(toBuffered(img), "png", new File(path)))
            throw new IOException("no PNG writer for " + path);
    }

    public static byte[] writeBytes(Rgba img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(toBuffered(img), "png", bos))
            throw new IOException("no PNG writer");
        return bos.toByteArray();
    }

    private static BufferedImage toBuffered(Rgba img) {
        BufferedImage out = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[img.width];
        for (int y = 0; y < img.height; y++) {
            for (int x = 0; x < img.width; x++) {
                int off = (y * img.width + x) * 4;
                int a = img.rgba[off + 3] & 0xff;
                int r = img.rgba[off] & 0xff;
                int g = img.rgba[off + 1] & 0xff;
                int b = img.rgba[off + 2] & 0xff;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, y, img.width, 1, row, 0, img.width);
        }
        return out;
    }

    /**
     * 整数 box 降采样：把最长边压到 <= maxEdge，缩放比取 2 的幂（保证 mip 链整齐）。
     * alpha 通道一同做平均，避免透明边缘出现黑边。
     * 返回原图（若无需缩放，不复制）。
     */
    public static Rgba downscaleTo(Rgba src, int maxEdge) {
        int max = Math.max(src.width, src.height);
        if (maxEdge <= 0 || max <= maxEdge) return src;
        int shift = 0;
        while ((max >> shift) > maxEdge) shift++;
        int w = src.width >> shift, h = src.height >> shift;
        if (w < 1 || h < 1) return src;
        return boxDownscale(src, w, h);
    }

    /** 任意目标尺寸的 box 平均降采样。 */
    public static Rgba boxDownscale(Rgba src, int dstW, int dstH) {
        if (dstW == src.width && dstH == src.height) return src;
        byte[] out = new byte[dstW * dstH * 4];
        // 每个目标像素在源图上的覆盖区间（整数分配，避免浮点误差）
        for (int dy = 0; dy < dstH; dy++) {
            int sy0 = dy * src.height / dstH;
            int sy1 = Math.max(sy0 + 1, (dy + 1) * src.height / dstH);
            for (int dx = 0; dx < dstW; dx++) {
                int sx0 = dx * src.width / dstW;
                int sx1 = Math.max(sx0 + 1, (dx + 1) * src.width / dstW);
                long r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int rowOff = sy * src.width;
                    for (int sx = sx0; sx < sx1; sx++) {
                        int off = (rowOff + sx) * 4;
                        r += src.rgba[off] & 0xff;
                        g += src.rgba[off + 1] & 0xff;
                        b += src.rgba[off + 2] & 0xff;
                        a += src.rgba[off + 3] & 0xff;
                        n++;
                    }
                }
                int o = (dy * dstW + dx) * 4;
                out[o] = (byte) (r / n);
                out[o + 1] = (byte) (g / n);
                out[o + 2] = (byte) (b / n);
                out[o + 3] = (byte) (a / n);
            }
        }
        return new Rgba(dstW, dstH, out);
    }

    /** RGB PSNR（忽略 alpha）。完全相同返回 Double.POSITIVE_INFINITY。 */
    public static double psnrRgb(byte[] a, byte[] b) {
        if (a.length != b.length || a.length == 0) return Double.NaN;
        long total = 0;
        int pixels = a.length / 4;
        for (int p = 0; p < pixels; p++) {
            for (int c = 0; c < 3; c++) {
                int av = a[p * 4 + c] & 0xff;
                int bv = b[p * 4 + c] & 0xff;
                long d = av - bv;
                total += d * d;
            }
        }
        double mse = (double) total / (pixels * 3.0);
        if (mse == 0.0) return Double.POSITIVE_INFINITY;
        return 10.0 * Math.log10(255.0 * 255.0 / mse);
    }
}
