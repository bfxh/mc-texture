// ============================================================================
// ttx_sample.glsl — GPU 端 TT3/TTX 块解码（GLSL 330 core / Iris shaderpack）
//
// 目的：让 TTX2 成为「GPU 可采样」格式，而不是在 CPU 解码成 RGBA 再上传。
// 显存里只驻留 12 字节/块（4x10 纹素）的块数据，采样时用整数运算即时还原颜色。
//
//   512x510 RGBA8 + 完整 mip  : 1,392,640 B
//   512x510 TTX   + 完整 mip  :   104,832 B   ->  13.28x 显存
//
// 本文件的整数运算语义与 Java 参考实现 verify/GlslRef.java 逐位一致，
// 后者已对全部 35 个 .ttx2 的全部 mip 级与官方解码器 TritCodec 逐像素比对通过。
//
// ---------------------------------------------------------------------------
// 块布局（与 trit-texture crate (Rust) / TritCodec.java 完全一致）
//
//   byte 0..1 : e0 = RGB565 大端（该块逐通道最小值）
//   byte 2..3 : e1 = RGB565 大端（该块逐通道最大值）
//   byte 4..10: 7 字节 trit 载荷（共 11 字节块）；每字节独立承载 5 个 trit，低位 trit 在前
//               digit_i = (payload[i / 5] / 3^(i % 5)) % 3
//   块内纹素 i -> x = i % 4, y = i / 4      （块 4 宽 8 高，共 32 纹素）
//   调色板     : [e0, mid, e1]，mid = (e0 + e1) / 2 逐通道整除
//
// 注意：trit 载荷是「5 trit/字节」的分组打包，不是跨字节的定长位流。
// 这正是它能被 GPU 解码的原因——每个字节自包含，全部运算留在 8/32 位整数域内，
// 不需要 64 位整数（GLSL 没有 uint64_t）。
//
// ---------------------------------------------------------------------------
// 数据纹理布局（本文件采用的形式）
//
//   一张 RGBA8UI 纹理，宽 = ttxDataWidth（建议 384 = 128 块 * 3）
//   每块占 3 个纹素，按「全局块索引」线性排布（跨 mip 级连续）：
//
//     gi = ttxLevelOffset[lod] + blockRow * blockCols + blockCol
//     texel(gi*3 + 0) = (e0.hi, e0.lo, e1.hi, e1.lo)
//     texel(gi*3 + 1) = payload[0..3]
//     texel(gi*3 + 2) = payload[4..6]，第 4 字节为 padding 0
//                       （Java 块为 11 字节：4 端点 + 7 trit，payload 仅 0..6；
//                        GLSL 的 by∈[0,6] 只访问 payload[4..6]，绝不读 texel2.a）
//
//   因为 3*gi % 384 只会取 0,3,...,381，而 381+2 = 383 < 384，
//   所以一个块的 3 个纹素永远不会跨行——texelFetch 直接算坐标即可，无需过滤。
//   总纹素数 = 总块数 * 3，数据纹理总字节 = 总块数 * 12（其中 texel2.a 为 padding，
//   实际 TTX 载荷为 11 字节/块；零浪费指纹素排布不跨行，非载荷百分百填满）。
// ============================================================================

// 块尺寸必须与 TritCodec 一致（4×8）。原 4×10 的块高不整除任何 MC 纹理边长，
// 已改为 4×8；32 trit 需 7 字节（5 trit/字节，向上取整）。
const int TTX_BLOCK_W        = 4;
const int TTX_BLOCK_H        = 8;
const int TTX_BLOCK_TEXELS   = 32;   // 4 * 8
const int TTX_BLOCK_BYTES    = 11;   // 4 (端点) + 7 (trit 载荷)
const int TTX_MAX_LEVELS     = 8;

// --- 数据纹理（由 mod 侧上传，NEAREST / 无 mip / RGBA8UI）---
uniform highp usampler2D ttxData;
uniform int  ttxDataWidth;                    // 每行纹素数（建议 384）
uniform int  ttxLevelCount;                   // 实际 mip 级数（512x510 -> 6）
uniform int  ttxLevelOffset[TTX_MAX_LEVELS];  // 每级起始全局块索引
uniform ivec2 ttxLevelBlocks[TTX_MAX_LEVELS]; // 每级 (blockCols, blockRows)

// ---------------------------------------------------------------------------
// 3^n，n ∈ [0,4]。GLSL 无 constexpr 循环展开保证，直接查表最稳。
// ---------------------------------------------------------------------------
uint ttxPow3(int n) {
    // 1, 3, 9, 27, 81
    if (n <= 0) return 1u;
    if (n == 1) return 3u;
    if (n == 2) return 9u;
    if (n == 3) return 27u;
    return 81u;
}

// ---------------------------------------------------------------------------
// RGB565 -> RGB888（左对齐位复制：高位补到低位，保证 0 -> 0、满量程 -> 255）
//   r5 -> (r5 << 3) | (r5 >> 2)
//   g6 -> (g6 << 2) | (g6 >> 4)
//   b5 -> (b5 << 3) | (b5 >> 2)
// ---------------------------------------------------------------------------
uvec3 ttxUnpack565(uint v) {
    uint r5 = (v >> 11) & 31u;
    uint g6 = (v >> 5)  & 63u;
    uint b5 =  v        & 31u;
    return uvec3((r5 << 3) | (r5 >> 2),
                 (g6 << 2) | (g6 >> 4),
                 (b5 << 3) | (b5 >> 2));
}

// ---------------------------------------------------------------------------
// 取块的第 k 个数据纹素（k = 0,1,2）
// ---------------------------------------------------------------------------
uvec4 ttxFetchBlockTexel(int gi, int k) {
    int t = gi * 3 + k;
    return texelFetch(ttxData, ivec2(t % ttxDataWidth, t / ttxDataWidth), 0);
}

// ---------------------------------------------------------------------------
// 主入口：采样 TTX 纹理
//
//   uv  : 归一化纹理坐标 [0,1]
//   lod : mip 级（0 = 最细）。调用方按屏幕导数自行选级，或直接用 textureQueryLod。
//
// 返回线性空间的 vec4（rgb 已 /255，a = 1）。
// 块压缩格式无法跨块做硬件双线性；跨块插值需要额外取邻块，见 ttxSampleBilinear。
// ---------------------------------------------------------------------------
vec4 ttxSample(vec2 uv, int lod) {
    lod = clamp(lod, 0, ttxLevelCount - 1);

    ivec2 bs    = ttxLevelBlocks[lod];
    vec2  dim   = vec2(float(bs.x * TTX_BLOCK_W), float(bs.y * TTX_BLOCK_H));

    // 纹素坐标（nearest）
    vec2  f = clamp(uv, 0.0, 1.0) * dim;
    ivec2 p = ivec2(floor(f));
    p = clamp(p, ivec2(0), ivec2(bs.x * TTX_BLOCK_W - 1, bs.y * TTX_BLOCK_H - 1));

    // 块坐标 + 块内偏移
    ivec2 b = ivec2(p.x / TTX_BLOCK_W, p.y / TTX_BLOCK_H);
    ivec2 o = ivec2(p.x - b.x * TTX_BLOCK_W, p.y - b.y * TTX_BLOCK_H);
    int  ti = o.y * TTX_BLOCK_W + o.x;          // 块内纹素索引 0..39

    // 三个数据纹素
    int  gi = ttxLevelOffset[lod] + b.y * bs.x + b.x;
    uvec4 t0 = ttxFetchBlockTexel(gi, 0);
    uvec4 t1 = ttxFetchBlockTexel(gi, 1);
    uvec4 t2 = ttxFetchBlockTexel(gi, 2);

    // 端点（大端 RGB565）
    uint e0 = (t0.r << 8) | t0.g;
    uint e1 = (t0.b << 8) | t0.a;

    // trit 索引：每字节 5 个 trit，低位在前
    int  by   = ti / 5;
    int  slot = ti - by * 5;
    uint vb;
    if (by < 4) {
        vb = (by == 0) ? t1.r : (by == 1 ? t1.g : (by == 2 ? t1.b : t1.a));
    } else {
        int k = by - 4;
        vb = (k == 0) ? t2.r : (k == 1 ? t2.g : (k == 2 ? t2.b : t2.a));
    }
    uint digit = (vb / ttxPow3(slot)) % 3u;

    // 3 级调色板 [e0, mid, e1]
    uvec3 c0  = ttxUnpack565(e0);
    uvec3 c1  = ttxUnpack565(e1);
    uvec3 mid = (c0 + c1) / 2u;                // 与 Java (a+b)/2 整除同义

    uvec3 col = (digit == 0u) ? c0 : (digit == 1u ? mid : c1);
    return vec4(vec3(col) / 255.0, 1.0);
}

// ---------------------------------------------------------------------------
// 可选：跨块双线性。块压缩格式下需要取最多 4 个块再在块内/块间插值。
// 代价是 4 倍 texelFetch；对 4x10 这种瘦高块收益有限，默认不建议开。
// ---------------------------------------------------------------------------
vec4 ttxSampleBilinear(vec2 uv, int lod) {
    lod = clamp(lod, 0, ttxLevelCount - 1);
    ivec2 bs  = ttxLevelBlocks[lod];
    vec2  dim = vec2(float(bs.x * TTX_BLOCK_W), float(bs.y * TTX_BLOCK_H));
    vec2  f   = clamp(uv, 0.0, 1.0) * dim - 0.5;
    vec2  w   = f - floor(f);

    vec2 texel = vec2(1.0) / dim;
    vec4 s00 = ttxSample(uv, lod);
    vec4 s10 = ttxSample(uv + vec2(texel.x, 0.0), lod);
    vec4 s01 = ttxSample(uv + vec2(0.0, texel.y), lod);
    vec4 s11 = ttxSample(uv + texel, lod);
    return mix(mix(s00, s10, w.x), mix(s01, s11, w.x), w.y);
}

// ---------------------------------------------------------------------------
// 兼容回退：部分管线（GLSL ES / 老驱动）不保证整数纹理可用。
// 用普通 sampler2D + NEAREST 读回浮点，再量化成字节；后续逻辑与上面完全相同。
// 数值上等价：字节值 ≤ 255 在 float32 下精确表示，(vb / 3^slot) % 3 结果精确。
// ---------------------------------------------------------------------------
vec4 ttxSampleFloat(sampler2D dataTex, vec2 uv, int lod) {
    lod = clamp(lod, 0, ttxLevelCount - 1);

    ivec2 bs  = ttxLevelBlocks[lod];
    vec2  dim = vec2(float(bs.x * TTX_BLOCK_W), float(bs.y * TTX_BLOCK_H));
    vec2  f   = clamp(uv, 0.0, 1.0) * dim;
    ivec2 p   = clamp(ivec2(floor(f)), ivec2(0),
                      ivec2(bs.x * TTX_BLOCK_W - 1, bs.y * TTX_BLOCK_H - 1));

    ivec2 b = ivec2(p.x / TTX_BLOCK_W, p.y / TTX_BLOCK_H);
    ivec2 o = ivec2(p.x - b.x * TTX_BLOCK_W, p.y - b.y * TTX_BLOCK_H);
    int  ti = o.y * TTX_BLOCK_W + o.x;

    int gi = ttxLevelOffset[lod] + b.y * bs.x + b.x;
    int base = gi * 3;

    vec4 q0 = texelFetch(dataTex, ivec2(base     % ttxDataWidth, base     / ttxDataWidth), 0);
    vec4 q1 = texelFetch(dataTex, ivec2((base+1) % ttxDataWidth, (base+1) / ttxDataWidth), 0);
    vec4 q2 = texelFetch(dataTex, ivec2((base+2) % ttxDataWidth, (base+2) / ttxDataWidth), 0);

    uint e0 = (uint(floor(q0.r * 255.0 + 0.5)) << 8) | uint(floor(q0.g * 255.0 + 0.5));
    uint e1 = (uint(floor(q0.b * 255.0 + 0.5)) << 8) | uint(floor(q0.a * 255.0 + 0.5));

    int  by   = ti / 5;
    int  slot = ti - by * 5;
    float fv;
    if (by < 4) {
        fv = (by == 0) ? q1.r : (by == 1 ? q1.g : (by == 2 ? q1.b : q1.a));
    } else {
        int k = by - 4;
        fv = (k == 0) ? q2.r : (k == 1 ? q2.g : (k == 2 ? q2.b : q2.a));
    }
    uint vb    = uint(floor(fv * 255.0 + 0.5));
    uint digit = uint(floor(float(vb) / float(ttxPow3(slot)))) % 3u;

    uvec3 c0  = ttxUnpack565(e0);
    uvec3 c1  = ttxUnpack565(e1);
    uvec3 mid = (c0 + c1) / 2u;
    uvec3 col = (digit == 0u) ? c0 : (digit == 1u ? mid : c1);
    return vec4(vec3(col) / 255.0, 1.0);
}
