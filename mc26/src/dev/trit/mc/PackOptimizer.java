package dev.trit.mc;

import dev.trit.mc.texture.PngIO;
import dev.trit.mc.texture.TritCodec;
import dev.trit.mc.texture.TritEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时材质包优化器：把玩家**自己**装的材质包降采样 + TTX 缓存，产出一个优化包覆盖上去。
 *
 * 【为什么是"降采样"而不是"TTX 直供 GPU"】
 *   MC 的 atlas 硬编码 RGBA8，纹理只能是 PNG。TTX 无法作为最终格式喂给渲染管线，
 *   所以 TTX 在这里的定位是**磁盘/解码缓存**：源 PNG → TTX2（体积小 3.6×、解码快 8×），
 *   重建优化包时从 TTX 恢复，避免每次都重新解 PNG。
 *   真正省显存的是降采样：512×512 的材质包会把 blocks atlas 从 1024×512 撑到
 *   4096×4096（实测），显存涨 16×。压到 256 后 atlas 回落，显存约省 4~16×。
 *
 * 【为什么优化包必须比源包优先级高】
 *   优化包的内容就是玩家材质包本身，只是变小了；它必须盖住源包才能生效。
 *   这不是 "trit 自己另搞一套材质"，而是把玩家已有的包优化后再交回去。
 */
public final class PackOptimizer {

    public static final String OPT_PACK_DIR_NAME = "trit-optimized";

    private static final Logger LOG = LoggerFactory.getLogger("trit");

    /** 不参与降采样的目录：字体/GUI/颜色表缩小后会糊或直接失效。 */
    private static final String[] EXCLUDED_PREFIXES = {
        "assets/minecraft/textures/font",
        "assets/minecraft/textures/gui",
        "assets/minecraft/textures/colormap",
        "assets/minecraft/textures/environment",
    };

    public enum Result { UP_TO_DATE, REBUILT, NO_PACKS, FAILED }

    public static final class Report {
        public final Result result;
        public final int textures;
        public final int downscaled;
        public final long srcBytes;
        public final long outBytes;
        public final long elapsedMs;
        Report(Result r, int t, int d, long s, long o, long ms) {
            result = r; textures = t; downscaled = d; srcBytes = s; outBytes = o; elapsedMs = ms;
        }
    }

    /**
     * 确保优化包是最新的。maxEdge<=0 表示只做 TTX 缓存不降采样。
     */
    public static Report ensure(Path gameDir, int maxEdge, List<String> selectedPackIds) {
        long t0 = System.currentTimeMillis();
        try {
            Path rpDir = gameDir.resolve("resourcepacks");
            Path optDir = rpDir.resolve(OPT_PACK_DIR_NAME);
            Path cacheDir = gameDir.resolve("trit-optimize").resolve("cache");

            if (!Files.isDirectory(rpDir)) return new Report(Result.NO_PACKS, 0, 0, 0, 0, 0);

            List<SourcePack> srcs = discoverSources(rpDir, selectedPackIds);
            if (srcs.isEmpty()) return new Report(Result.NO_PACKS, 0, 0, 0, 0, 0);

            String fp = fingerprint(srcs, maxEdge);
            Path stateFile = optDir.resolve(".trit-state");
            if (Files.isDirectory(optDir) && Files.exists(stateFile)
                    && Files.readString(stateFile, StandardCharsets.UTF_8).trim().equals(fp)
                    && Files.isDirectory(optDir.resolve("assets"))) {
                return new Report(Result.UP_TO_DATE, 0, 0, 0, 0, System.currentTimeMillis() - t0);
            }

            return rebuild(srcs, optDir, cacheDir, maxEdge, fp, t0);
        } catch (Throwable t) {
            LOG.error("[trit] 材质包优化失败", t);
            return new Report(Result.FAILED, 0, 0, 0, 0, System.currentTimeMillis() - t0);
        }
    }

    // ------------------------------------------------------------------
    // 源包发现
    // ------------------------------------------------------------------

    public static final class SourcePack {
        public final String name;
        public final Path path;     // zip 文件或目录
        public final boolean zip;
        SourcePack(String name, Path path, boolean zip) {
            this.name = name; this.path = path; this.zip = zip;
        }
    }

    /**
     * 在 resourcepacks 目录里定位被启用的包。
     * MC 26.3 的包 id 形如 "file/xxx" 或 "file/xxx.zip"，这里做宽松匹配：
     * 取 id 最后一段、去掉 .zip 后缀，再在磁盘上找同名 .zip 或目录。
     */
    static List<SourcePack> discoverSources(Path rpDir, List<String> selectedPackIds) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> s = Files.list(rpDir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) return false;
                if (n.equals(OPT_PACK_DIR_NAME)) return false;
                if (Files.isDirectory(p)) return true;
                return n.toLowerCase(Locale.ROOT).endsWith(".zip");
            }).forEach(candidates::add);
        }

        List<SourcePack> out = new ArrayList<>();
        for (Path p : candidates) {
            String base = p.getFileName().toString();
            boolean isZip = !Files.isDirectory(p);
            String stem = isZip && base.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? base.substring(0, base.length() - 4) : base;
            boolean selected = selectedPackIds == null || selectedPackIds.isEmpty()
                || selectedPackIds.stream().anyMatch(id -> id.contains(stem));
            if (selected) out.add(new SourcePack(stem, p, isZip));
        }
        out.sort(Comparator.comparing(a -> a.name));
        return out;
    }

    static String fingerprint(List<SourcePack> srcs, int maxEdge) {
        StringBuilder sb = new StringBuilder("v1|max=" + maxEdge);
        for (SourcePack s : srcs) {
            try {
                sb.append('|').append(s.name).append(':');
                if (s.zip) {
                    sb.append(Files.size(s.path)).append(':').append(Files.getLastModifiedTime(s.path).toMillis());
                } else {
                    // 目录：用文件数 + 总大小近似（遍历全量太慢）
                    long[] acc = {0, 0};
                    try (Stream<Path> w = Files.walk(s.path)) {
                        w.filter(Files::isRegularFile).forEach(f -> {
                            acc[0]++;
                            try { acc[1] += Files.size(f); } catch (IOException ignored) { }
                        });
                    }
                    sb.append(acc[0]).append(':').append(acc[1]);
                }
            } catch (IOException e) {
                sb.append('?');
            }
        }
        return sha1Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 重建
    // ------------------------------------------------------------------

    static Report rebuild(List<SourcePack> srcs, Path optDir, Path cacheDir, int maxEdge,
                          String fp, long t0) throws IOException {
        if (Files.exists(optDir)) deleteRecursive(optDir);
        Files.createDirectories(optDir);
        Files.createDirectories(cacheDir);

        int textures = 0, downscaled = 0;
        long srcBytes = 0, outBytes = 0;

        byte[] mcmeta = null;
        for (SourcePack s : srcs) {
            for (Entry e : entries(s)) {
                String rel = e.path;                       // 形如 assets/minecraft/textures/block/stone.png
                if (!rel.startsWith("assets/")) continue;
                if (!rel.endsWith(".png")) {
                    // .mcmeta（动画描述）必须跟着走，否则动画纹理会退化成静态
                    if (rel.endsWith(".png.mcmeta")) copyEntry(s, e, optDir.resolve(rel));
                    continue;
                }
                if (isExcluded(rel)) continue;

                byte[] png = readEntry(s, e);
                if (png == null || png.length == 0) continue;

                // 只读 PNG 头判断尺寸：不需要降采样的纹理**完全不进优化包**，
                // 让它们继续由源包提供 —— 否则优化包会退化成源包的全量副本（磁盘翻倍、构建白慢）。
                int[] dim = pngSize(png);
                boolean needDownscale = maxEdge > 0 && dim != null && Math.max(dim[0], dim[1]) > maxEdge;
                if (!needDownscale) continue;

                srcBytes += png.length;
                downscaled++;

                String key = sha1Hex(png) + "-" + maxEdge;
                Path cacheFile = cacheDir.resolve(safe(s.name)).resolve(rel + ".ttx2");

                PngIO.Rgba img;
                boolean fromCache = false;
                if (Files.exists(cacheFile) && Files.exists(cacheFile.getParent().resolve(rel + ".key"))
                        && Files.readString(cacheFile.getParent().resolve(rel + ".key"), StandardCharsets.UTF_8).trim().equals(key)) {
                    try {
                        TritCodec.Ttx2Document doc = TritCodec.fromTtx2(Files.readAllBytes(cacheFile));
                        img = new PngIO.Rgba(doc.levels[0].width, doc.levels[0].height,
                                TritCodec.decodeRgba(doc.levels[0]));
                        fromCache = true;
                    } catch (Throwable ignored) { img = null; }
                } else {
                    img = null;
                }

                if (img == null) {
                    try {
                        img = PngIO.readBytes(png);
                    } catch (IOException ex) {
                        continue;   // 损坏的 PNG：跳过，不阻断整包
                    }
                    img = PngIO.downscaleTo(img, maxEdge);
                    // 写 TTX 缓存（失败不影响主流程）
                    try {
                        Files.createDirectories(cacheFile.getParent());
                        Files.write(cacheFile, TritEncoder.rgbaToTtx2(img.rgba, img.width, img.height));
                        Files.writeString(cacheFile.getParent().resolve(rel + ".key"), key, StandardCharsets.UTF_8);
                    } catch (Throwable ignored) { }
                }

                // 动画描述文件跟着一起走，否则被降采样的动画纹理会退化成静态
                copyEntry(s, new Entry(rel + ".mcmeta"), optDir.resolve(rel + ".mcmeta"));

                byte[] out;
                try {
                    out = PngIO.writeBytes(img);
                } catch (IOException ex) {
                    copyEntry(s, e, optDir.resolve(rel));
                    outBytes += png.length;
                    textures++;
                    continue;
                }

                Path dst = optDir.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.write(dst, out);
                outBytes += out.length;
                textures++;
                if (fromCache) { /* 缓存命中，省掉一次 PNG 解码 */ }
            }

            if (mcmeta == null) mcmeta = readPackMcmeta(s);
        }

        // 没有任何纹理需要降采样 -> 优化包是空的，删掉它，不能让空包混进资源栈
        // （空包会顶掉源包的优先级却什么都不提供）
        long ms = System.currentTimeMillis() - t0;
        if (textures == 0) {
            if (Files.exists(optDir)) deleteRecursive(optDir);
            LOG.info("[trit] 材质包无需优化（档位 max={} 下没有超限纹理），不生成优化包", maxEdge);
            return new Report(Result.NO_PACKS, 0, 0, 0, 0, ms);
        }

        Files.writeString(optDir.resolve("pack.mcmeta"),
            mcmeta != null ? new String(mcmeta, StandardCharsets.UTF_8) : defaultMcmeta(),
            StandardCharsets.UTF_8);
        Files.writeString(optDir.resolve(".trit-state"), fp, StandardCharsets.UTF_8);

        LOG.info("[trit] 材质包优化完成：{} 张（降采样 {} 张），{} → {} 字节，耗时 {} ms",
            textures, downscaled, srcBytes, outBytes, ms);
        return new Report(Result.REBUILT, textures, downscaled, srcBytes, outBytes, ms);
    }

    private static boolean isExcluded(String rel) {
        String r = rel.replace('\\', '/');
        for (String p : EXCLUDED_PREFIXES) if (r.startsWith(p)) return true;
        return false;
    }

    private static String defaultMcmeta() {
        int major = net.minecraft.SharedConstants.RESOURCE_PACK_FORMAT_MAJOR;
        int minor = net.minecraft.SharedConstants.RESOURCE_PACK_FORMAT_MINOR;
        return "{\"pack\":{\"pack_format\":" + major
            + ",\"min_format\":[" + major + "," + minor + "]"
            + ",\"max_format\":[" + major + "," + minor + "]"
            + ",\"description\":\"trit runtime-optimized copy of your resource packs\"}}\n";
    }

    private static byte[] readPackMcmeta(SourcePack s) {
        try {
            if (s.zip) {
                try (ZipFile zf = new ZipFile(s.path.toFile())) {
                    ZipEntry ze = zf.getEntry("pack.mcmeta");
                    if (ze == null) return null;
                    try (InputStream in = zf.getInputStream(ze)) { return in.readAllBytes(); }
                }
            } else {
                Path p = s.path.resolve("pack.mcmeta");
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
        } catch (IOException ignored) { }
        return null;
    }

    // ------------------------------------------------------------------
    // 条目遍历
    // ------------------------------------------------------------------

    static final class Entry {
        final String path;
        Entry(String path) { this.path = path; }
    }

    static List<Entry> entries(SourcePack s) throws IOException {
        List<Entry> out = new ArrayList<>();
        String lower = s.path.toString().toLowerCase(Locale.ROOT);
        if (s.zip) {
            try (ZipFile zf = new ZipFile(s.path.toFile())) {
                var it = zf.entries();
                while (it.hasMoreElements()) {
                    ZipEntry ze = it.nextElement();
                    if (ze.isDirectory()) continue;
                    out.add(new Entry(ze.getName()));
                }
            }
        } else {
            try (Stream<Path> w = Files.walk(s.path)) {
                w.filter(Files::isRegularFile).forEach(p -> {
                    String rel = s.path.relativize(p).toString().replace('\\', '/');
                    out.add(new Entry(rel));
                });
            }
        }
        return out;
    }

    static byte[] readEntry(SourcePack s, Entry e) throws IOException {
        if (s.zip) {
            try (ZipFile zf = new ZipFile(s.path.toFile())) {
                ZipEntry ze = zf.getEntry(e.path);
                if (ze == null) return null;
                try (InputStream in = zf.getInputStream(ze)) { return in.readAllBytes(); }
            }
        }
        Path p = s.path.resolve(e.path);
        if (!Files.exists(p)) return null;
        return Files.readAllBytes(p);
    }

    static void copyEntry(SourcePack s, Entry e, Path dst) throws IOException {
        byte[] b = readEntry(s, e);
        if (b == null) return;
        Files.createDirectories(dst.getParent());
        Files.write(dst, b);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 从 PNG 头（IHDR）读宽高，不解码整图。
     * 用来在动重活之前先判断"这张图到底需不需要降采样"。
     */
    static int[] pngSize(byte[] png) {
        if (png.length < 24) return null;
        // 8 字节签名 + 4 字节长度 + "IHDR"
        if ((png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }

    static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> w = Files.walk(root)) {
            List<Path> ps = w.sorted(Comparator.reverseOrder()).toList();
            for (Path p : ps) Files.deleteIfExists(p);
        }
    }
}
