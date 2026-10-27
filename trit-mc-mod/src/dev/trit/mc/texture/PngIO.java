package dev.trit.mc.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** RGBA(8bit/通道) 图像 + PNG 读写（仅依赖 JDK，验证用）。 */
public final class PngIO {

    public static final class Rgba {
        public final int width, height;
        public final byte[] rgba; // width*height*4, 行主序, alpha=255
        public Rgba(int width, int height, byte[] rgba) {
            this.width = width; this.height = height; this.rgba = rgba;
        }
    }

    /** 读 PNG → RGBA（alpha 恒 255；透明像素以不透明读入，不影响 RGB 比对）。 */
    public static Rgba read(String path) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) throw new IOException("cannot read image: " + path);
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
                rgba[off + 3] = (byte) 255;
            }
        }
        return new Rgba(w, h, rgba);
    }

    /** 写 RGBA → PNG。 */
    public static void write(Rgba img, String path) throws IOException {
        BufferedImage out = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[img.width];
        for (int y = 0; y < img.height; y++) {
            for (int x = 0; x < img.width; x++) {
                int off = (y * img.width + x) * 4;
                int r = img.rgba[off] & 0xff;
                int g = img.rgba[off + 1] & 0xff;
                int b = img.rgba[off + 2] & 0xff;
                row[x] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, y, img.width, 1, row, 0, img.width);
        }
        if (!ImageIO.write(out, "png", new File(path)))
            throw new IOException("no PNG writer for " + path);
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
