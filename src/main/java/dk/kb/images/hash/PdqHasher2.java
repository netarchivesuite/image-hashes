package dk.kb.images.hash;


import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * PDQ perceptual hash — pure Java, zero dependencies beyond java.awt.
 *
 * This is a faithful port of Meta's reference C++ implementation from
 * https://github.com/facebook/ThreatExchange/tree/main/pdq
 *
 * The algorithm (matching the C++ reference exactly):
 *   1.  Convert image to float luma (Y = 0.299R + 0.587G + 0.114B).
 *   2.  Apply a Jarosz windowed box-filter (2 passes of 1D box along rows
 *       then columns) to blur and anti-alias the full-resolution image.
 *   3.  Decimate to 64×64 by centre-pixel sampling.
 *   4.  Compute a gradient-based quality score (0–100).
 *   5.  Apply a 64→16 partial DCT: compute only the 16×16 low-frequency
 *       output using the asymmetric DCT matrix D where
 *           D[i][j] = sqrt(2/64) * cos(pi/(2*64) * (i+1) * (2j+1))
 *       (note: rows 1..16, NOT 0..15, so DC is excluded from the matrix
 *        but all 256 output cells ARE used for the median).
 *   6.  Find the median of all 256 DCT values using the Torben algorithm.
 *   7.  Threshold and pack into a Hash256 (16 unsigned shorts stored LSB-first,
 *       serialised high-word-first for the hex wire format).
 *
 * IMPORTANT DIFFERENCES from a naïve DCT-hash implementation:
 *   • Downsampling uses the Jarosz filter, NOT bilinear resize.
 *   • The DCT matrix rows start at i=1 (not i=0), so there is no DC row.
 *   • Median is over all 256 cells (not 255 AC cells).
 *   • Quality metric uses integer gradient arithmetic matching the C++.
 *
 * DIHEDRAL VARIANTS (rotations / mirrors without re-running the pipeline):
 * PDQ's 16×16 DCT output has a known symmetry under each of the 8 dihedral
 * transforms (4 rotations × optional mirror) — flipping or rotating the
 * source image corresponds to flipping the sign and/or transposing
 * specific cells of the 16×16 DCT buffer, before the median-threshold
 * step. This means all 8 variant hashes can be derived from a single
 * Jarosz-filter + DCT pass, without re-decoding or re-filtering the image.
 * See {@link #hashAll(BufferedImage)}. This mirrors Meta's reference
 * dihedral hashing (pdqDihedralHash256esFromFloatLuma in pdqhashing.cpp).
 *
 * BSD-licensed, same as the Meta original.
 */
public class PdqHasher2 {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    public static final int HASH_BITS = 256;

    private static final int DCT_SIZE  = 64;   // luma buffer side
    private static final int KEEP      = 16;   // DCT output side (16x16 = 256 bits)
    private static final int NUM_PASSES = 2;   // Jarosz filter passes

    /** Luma weights (ITU-R BT.601, matching reference). */
    private static final float LUMA_R = 0.299f;
    private static final float LUMA_G = 0.587f;
    private static final float LUMA_B = 0.114f;

    /**
     * Precomputed 16×64 DCT matrix.
     * D[i][j] = sqrt(2/64) * cos(pi / (2*64) * (i+1) * (2j+1))
     * Stored row-major as a flat float[16*64].
     */
    private static final float[] DCT_MATRIX;
    static {
        DCT_MATRIX = new float[KEEP * DCT_SIZE];
        float scale = (float) Math.sqrt(2.0 / DCT_SIZE);
        double piOver2N = Math.PI / (2.0 * DCT_SIZE);
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < DCT_SIZE; j++) {
                DCT_MATRIX[i * DCT_SIZE + j] =
                    (float) (scale * Math.cos(piOver2N * (i + 1) * (2 * j + 1)));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public result type
    // -----------------------------------------------------------------------

    /**
     * A PDQ hash result: 256 bits packed as 16 unsigned shorts (stored in
     * an int[] for convenience), plus a quality score 0–100.
     *
     * Quality < 50 means the image is low-gradient / featureless; the
     * reference recommends discarding hashes with quality ≤ 49.
     *
     * Wire format: 64 hex chars, words w[15]..w[0] each as 4 hex chars.
     * This matches the ThreatExchange canonical format.
     */
    public static final class Result {
        /** 16 unsigned shorts stored as int[0..15], w[0] at index 0. */
        public final int[] words;
        public final int quality;

        Result(int[] words, int quality) {
            this.words = words;
            this.quality = quality;
        }

        /**
         * 64-character lowercase hex string (ThreatExchange wire format).
         * Words are emitted high-index first: w[15], w[14], …, w[0].
         */
        public String toHexString() {
            StringBuilder sb = new StringBuilder(64);
            for (int i = 15; i >= 0; i--) {
                sb.append(String.format("%04x", words[i] & 0xFFFF));
            }
            return sb.toString();
        }

        /** Hamming distance (0 = identical; ≤ 31 = similar per reference thresholds). */
        public int hammingDistance(Result other) {
            return PdqHasher.hammingDistance(this.words, other.words);
        }

        @Override
        public String toString() {
            return "PDQHash{hash=" + toHexString() + ", quality=" + quality + "}";
        }
    }

    /**
     * All 8 dihedral-transform hashes derived from a single image decode +
     * filter + DCT pass — i.e. the hash you would get from rotating or
     * mirroring the source image yourself, without actually doing so.
     *
     * Naming and geometric meaning (matching Meta's reference labels):
     *   original    — the unmodified image
     *   rotate90    — rotated 90° counter-clockwise
     *   rotate180   — rotated 180°
     *   rotate270   — rotated 90° clockwise (= 270° counter-clockwise)
     *   flipY       — horizontal mirror (left/right swapped) — i.e. PIL's
     *                 FLIP_LEFT_RIGHT / the common "mirror image" transform
     *   flipX       — vertical mirror (top/bottom swapped)
     *   flipPlus1   — transpose about the main diagonal (top-left/bottom-right)
     *   flipMinus1  — transpose about the anti-diagonal (top-right/bottom-left)
     *
     * All 8 share the same quality score, since quality is computed from
     * the un-rotated 64×64 luma buffer (rotating/mirroring doesn't change
     * the gradient-magnitude sum).
     */
    public static final class DihedralResult {
        public final Result original;
        public final Result rotate90;
        public final Result rotate180;
        public final Result rotate270;
        public final Result flipX;
        public final Result flipY;
        public final Result flipPlus1;
        public final Result flipMinus1;

        DihedralResult(Result original, Result rotate90, Result rotate180, Result rotate270,
                       Result flipX, Result flipY, Result flipPlus1, Result flipMinus1) {
            this.original = original;
            this.rotate90 = rotate90;
            this.rotate180 = rotate180;
            this.rotate270 = rotate270;
            this.flipX = flipX;
            this.flipY = flipY;
            this.flipPlus1 = flipPlus1;
            this.flipMinus1 = flipMinus1;
        }

        /** All 8 variants as an array, in the same order as Meta's reference output. */
        public Result[] all() {
            return new Result[]{original, rotate90, rotate180, rotate270,
                                 flipX, flipY, flipPlus1, flipMinus1};
        }

        /**
         * Minimum Hamming distance from {@code query} to any of these 8
         * variants. Use this to test "is the query a rotated/mirrored
         * version of this image?" without knowing which transform was used.
         */
        public int minHammingDistance(Result query) {
            int min = Integer.MAX_VALUE;
            for (Result r : all()) {
                int d = r.hammingDistance(query);
                if (d < min) min = d;
            }
            return min;
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Compute the PDQ hash of a BufferedImage. */
    public static Result hash(BufferedImage image) {
        DctBuffer dctBuf = computeDctBuffer(image);
        return bufferToResult(dctBuf.buffer, dctBuf.quality);
    }

    /**
     * Compute the PDQ hash of a BufferedImage AND all 7 of its dihedral
     * (rotated/mirrored) variants, in one pass — i.e. without re-decoding
     * the image, re-running the Jarosz filter, or re-running the DCT 8
     * times. Only the cheap final median+threshold step is repeated per
     * variant, on a sign/transpose-flipped copy of the same 16×16 buffer.
     *
     * Use this when you need to test a query hash against all possible
     * orientations of a stored image (or vice versa) — for example to
     * detect that an incoming image is a 90°-rotated copy of one already
     * indexed, without storing 8 separate hashes per image up front.
     */
    public static DihedralResult hashAll(BufferedImage image) {
        DctBuffer dctBuf = computeDctBuffer(image);
        float[][] B = dctBuf.buffer;
        int quality = dctBuf.quality;

        float[][] aux = new float[KEEP][KEEP];

        Result original = bufferToResult(B, quality);

        dct16OriginalToRotate90(B, aux);
        Result rotate90 = bufferToResult(aux, quality);

        dct16OriginalToRotate180(B, aux);
        Result rotate180 = bufferToResult(aux, quality);

        dct16OriginalToRotate270(B, aux);
        Result rotate270 = bufferToResult(aux, quality);

        dct16OriginalToFlipX(B, aux);
        Result flipX = bufferToResult(aux, quality);

        dct16OriginalToFlipY(B, aux);
        Result flipY = bufferToResult(aux, quality);

        dct16OriginalToFlipPlus1(B, aux);
        Result flipPlus1 = bufferToResult(aux, quality);

        dct16OriginalToFlipMinus1(B, aux);
        Result flipMinus1 = bufferToResult(aux, quality);

        return new DihedralResult(original, rotate90, rotate180, rotate270,
                                   flipX, flipY, flipPlus1, flipMinus1);
    }

    // -----------------------------------------------------------------------
    // Internal: luma extraction (with fast paths for common BufferedImage types)
    // -----------------------------------------------------------------------

    /**
     * Extracts a row-major float luma array from {@code image}.
     *
     * Calling {@code BufferedImage.getRGB(x, y)} once per pixel is the
     * single largest cost in the whole hashing pipeline (measured at
     * roughly 60% of total hash() time) because every call goes through
     * bounds-checking and a virtual ColorModel conversion.
     *
     * For the two BufferedImage types most commonly produced by
     * {@code ImageIO.read()} on JPEG and opaque/alpha PNG files —
     * TYPE_3BYTE_BGR and TYPE_4BYTE_ABGR — the backing byte array can be
     * read directly with no per-pixel method call overhead, because their
     * raw bytes ARE plain sRGB component values with no colour-space
     * transform involved. This was verified to produce byte-identical
     * output to the safe getRGB() path on real test images.
     *
     * IMPORTANT: TYPE_BYTE_GRAY is deliberately NOT given a fast path
     * here. Its raw bytes are often encoded against a non-sRGB ICC colour
     * profile (e.g. a Gray Gamma 2.2 profile from libpng), and
     * getRGB()/getRed() silently performs a real colour-space conversion
     * — the raw byte value is NOT the sRGB luma value. A naive direct-byte
     * shortcut for this type was measured to be wrong by up to ~70/255
     * levels per pixel on a real PNG. Any other type not explicitly
     * handled below (indexed/palette images, USHORT variants, custom
     * ColorModels, etc.) falls through to the same safe bulk-getRGB path.
     */
    private static float[] extractLuma(BufferedImage image, int imgW, int imgH) {
        int type = image.getType();

        if (type == BufferedImage.TYPE_3BYTE_BGR) {
            return extractLuma3ByteBgr(image, imgW, imgH);
        }
        if (type == BufferedImage.TYPE_4BYTE_ABGR
                || type == BufferedImage.TYPE_4BYTE_ABGR_PRE) {
            return extractLuma4ByteAbgr(image, imgW, imgH);
        }
        return extractLumaGeneric(image, imgW, imgH);
    }

    /** Fast path for TYPE_3BYTE_BGR: raw byte layout per pixel is [B, G, R]. */
    private static float[] extractLuma3ByteBgr(BufferedImage image, int imgW, int imgH) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        float[] luma = new float[imgW * imgH];
        for (int i = 0, p = 0; i < luma.length; i++, p += 3) {
            int b = data[p]     & 0xFF;
            int g = data[p + 1] & 0xFF;
            int r = data[p + 2] & 0xFF;
            luma[i] = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        }
        return luma;
    }

    /** Fast path for TYPE_4BYTE_ABGR(_PRE): raw byte layout per pixel is [A, B, G, R]. */
    private static float[] extractLuma4ByteAbgr(BufferedImage image, int imgW, int imgH) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        float[] luma = new float[imgW * imgH];
        for (int i = 0, p = 0; i < luma.length; i++, p += 4) {
            int b = data[p + 1] & 0xFF;
            int g = data[p + 2] & 0xFF;
            int r = data[p + 3] & 0xFF;
            luma[i] = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        }
        return luma;
    }

    /**
     * Safe fallback for every other BufferedImage type (BYTE_GRAY,
     * BYTE_INDEXED, USHORT_*, custom ColorModels, etc.). Uses the bulk
     * getRGB(x,y,w,h,...) overload, which is still substantially faster
     * than calling getRGB(x,y) once per pixel because it amortises the
     * per-call overhead, but goes through the correct ColorModel/ICC
     * conversion for types where the raw bytes aren't plain sRGB.
     */
    private static float[] extractLumaGeneric(BufferedImage image, int imgW, int imgH) {
        int[] pixels = new int[imgW * imgH];
        image.getRGB(0, 0, imgW, imgH, pixels, 0, imgW);
        float[] luma = new float[imgW * imgH];
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            float rv = (rgb >> 16) & 0xFF;
            float gv = (rgb >>  8) & 0xFF;
            float bv =  rgb        & 0xFF;
            luma[i] = LUMA_R * rv + LUMA_G * gv + LUMA_B * bv;
        }
        return luma;
    }

    // -----------------------------------------------------------------------
    // Internal: shared pipeline (image -> 16x16 DCT buffer + quality)
    // -----------------------------------------------------------------------

    /** Holds the 16x16 DCT output buffer plus the quality score for one image. */
    private static final class DctBuffer {
        final float[][] buffer;
        final int quality;
        DctBuffer(float[][] buffer, int quality) {
            this.buffer = buffer;
            this.quality = quality;
        }
    }

    /**
     * Runs steps 1-5 of the algorithm (luma extraction through partial DCT),
     * shared by both {@link #hash(BufferedImage)} and
     * {@link #hashAll(BufferedImage)}.
     */
    private static DctBuffer computeDctBuffer(BufferedImage image) {
        int imgH = image.getHeight();
        int imgW = image.getWidth();

        // --- Step 1: convert to float luma ---
        float[] luma = extractLuma(image, imgW, imgH);

        // --- Step 2: Jarosz filter (blur in-place, 2 passes) ---
        float[] tmp = new float[imgH * imgW];
        int wsAlongRows = computeWindowSize(imgW, DCT_SIZE);
        int wsAlongCols = computeWindowSize(imgH, DCT_SIZE);
        jaroszFilter(luma, tmp, imgH, imgW, wsAlongRows, wsAlongCols, NUM_PASSES);

        // --- Step 3: decimate to 64×64 ---
        float[][] luma64 = new float[DCT_SIZE][DCT_SIZE];
        for (int i = 0; i < DCT_SIZE; i++) {
            int srcRow = (int) ((i + 0.5) * imgH / DCT_SIZE);
            for (int j = 0; j < DCT_SIZE; j++) {
                int srcCol = (int) ((j + 0.5) * imgW / DCT_SIZE);
                luma64[i][j] = luma[srcRow * imgW + srcCol];
            }
        }

        // --- Step 4: quality metric ---
        int quality = qualityMetric(luma64);

        // --- Step 5: partial 2D DCT (64→16 each dimension) ---
        // T = D * luma64   (16×64)
        float[][] T = new float[KEEP][DCT_SIZE];
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < DCT_SIZE; j++) {
                float sum = 0f;
                for (int k = 0; k < DCT_SIZE; k++) {
                    sum += DCT_MATRIX[i * DCT_SIZE + k] * luma64[k][j];
                }
                T[i][j] = sum;
            }
        }
        // B = T * D^T   (16×16)
        float[][] B = new float[KEEP][KEEP];
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                float sum = 0f;
                for (int k = 0; k < DCT_SIZE; k++) {
                    sum += T[i][k] * DCT_MATRIX[j * DCT_SIZE + k]; // D^T[k][j] = D[j][k]
                }
                B[i][j] = sum;
            }
        }

        return new DctBuffer(B, quality);
    }

    /**
     * Runs steps 6-7 (median threshold + bit-pack) on a given 16×16 DCT
     * buffer, producing a Result. Shared by the original hash and all
     * dihedral variants.
     */
    private static Result bufferToResult(float[][] B, int quality) {
        // --- Step 6: Torben median of all 256 values ---
        float[] flat = new float[KEEP * KEEP];
        for (int i = 0; i < KEEP; i++)
            System.arraycopy(B[i], 0, flat, i * KEEP, KEEP);
        float median = torbenMedian(flat);

        // --- Step 7: threshold and pack into 16 unsigned shorts ---
        int[] w = new int[16]; // w[0..15]
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                if (B[i][j] > median) {
                    int k = i * KEEP + j;
                    w[k >> 4] |= (1 << (k & 15));
                }
            }
        }

        return new Result(w, quality);
    }

    /** Hamming distance between two Result.words arrays. */
    public static int hammingDistance(int[] a, int[] b) {
        int dist = 0;
        for (int i = 0; i < 16; i++) {
            dist += Integer.bitCount((a[i] ^ b[i]) & 0xFFFF);
        }
        return dist;
    }

    /**
     * Parse a 64-char hex string (ThreatExchange wire format) into words[].
     * Words are stored low-index first; the hex string has w[15] first.
     */
    public static int[] fromHexString(String hex) {
        if (hex.length() != 64)
            throw new IllegalArgumentException("PDQ hex must be 64 chars, got " + hex.length());
        int[] w = new int[16];
        for (int i = 0; i < 16; i++) {
            // hex position: w[15] is at hex[0..3], w[0] is at hex[60..63]
            w[15 - i] = Integer.parseUnsignedInt(hex.substring(i * 4, i * 4 + 4), 16);
        }
        return w;
    }

    // -----------------------------------------------------------------------
    // Internal: Jarosz filter (matching C++ reference exactly)
    // -----------------------------------------------------------------------

    /** Jarosz window size: round-up formula from the C++ source. */
    static int computeWindowSize(int oldDim, int newDim) {
        return (oldDim + 2 * newDim - 1) / (2 * newDim);
    }

    /**
     * Apply the Jarosz filter in-place to {@code buf}.
     * {@code tmp} is a scratch buffer of the same size.
     * Each pass: box along rows → box along cols.
     */
    static void jaroszFilter(float[] buf, float[] tmp,
                             int numRows, int numCols,
                             int wsAlongRows, int wsAlongCols,
                             int nreps) {
        for (int pass = 0; pass < nreps; pass++) {
            // box along rows (stride 1 within each row)
            for (int r = 0; r < numRows; r++) {
                box1D(buf, r * numCols, tmp, r * numCols, numCols, 1, wsAlongRows);
            }
            // box along cols (stride numCols between rows)
            for (int c = 0; c < numCols; c++) {
                box1D(tmp, c, buf, c, numRows, numCols, wsAlongCols);
            }
        }
    }

    /**
     * 1D sliding box filter matching the C++ box1DFloat exactly.
     * Handles the four phases: accumulate / initial writes / full window / tail.
     *
     * @param in      source array
     * @param inOff   offset of first element in source
     * @param out     destination array
     * @param outOff  offset of first element in destination
     * @param len     number of elements
     * @param stride  element stride (1 for row-wise, numCols for col-wise)
     * @param ws      full window size
     */
    static void box1D(float[] in, int inOff,
                      float[] out, int outOff,
                      int len, int stride, int ws) {
        int half = (ws + 2) / 2;          // 7→4, 8→5, 9→5, 6→4
        int phase1 = half - 1;
        int phase2 = ws - half + 1;
        int phase3 = len - ws;
        int phase4 = half - 1;

        int li = inOff, ri = inOff, oi = outOff;
        float sum = 0f;
        int cw = 0;

        // Phase 1: accumulate, no output
        for (int i = 0; i < phase1; i++) {
            sum += in[ri]; cw++; ri += stride;
        }
        // Phase 2: output with growing window
        for (int i = 0; i < phase2; i++) {
            sum += in[ri]; cw++;
            out[oi] = sum / cw;
            ri += stride; oi += stride;
        }
        // Phase 3: full sliding window
        for (int i = 0; i < phase3; i++) {
            sum += in[ri]; sum -= in[li];
            out[oi] = sum / cw;
            li += stride; ri += stride; oi += stride;
        }
        // Phase 4: shrinking window at tail
        for (int i = 0; i < phase4; i++) {
            sum -= in[li]; cw--;
            out[oi] = sum / cw;
            li += stride; oi += stride;
        }
    }

    // -----------------------------------------------------------------------
    // Internal: dihedral transforms on the 16x16 DCT buffer
    // (ported directly from Meta's pdqhashing.cpp; see file header for the
    //  symmetry-table comment explaining the +/- sign patterns below)
    // -----------------------------------------------------------------------

    /** Rotate 90° counter-clockwise: transpose with alternating column sign. */
    static void dct16OriginalToRotate90(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[j][i] = ((j & 1) != 0) ? A[i][j] : -A[i][j];
            }
        }
    }

    /** Rotate 180°: no transpose, checkerboard sign flip. */
    static void dct16OriginalToRotate180(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[i][j] = (((i + j) & 1) != 0) ? -A[i][j] : A[i][j];
            }
        }
    }

    /** Rotate 270° counter-clockwise (= 90° clockwise): transpose, alternating row sign. */
    static void dct16OriginalToRotate270(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[j][i] = ((i & 1) != 0) ? A[i][j] : -A[i][j];
            }
        }
    }

    /** Vertical mirror (top/bottom swapped): no transpose, alternating row sign. */
    static void dct16OriginalToFlipX(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[i][j] = ((i & 1) != 0) ? A[i][j] : -A[i][j];
            }
        }
    }

    /** Horizontal mirror (left/right swapped — the common "mirror image"): alternating column sign. */
    static void dct16OriginalToFlipY(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[i][j] = ((j & 1) != 0) ? A[i][j] : -A[i][j];
            }
        }
    }

    /** Transpose about the main diagonal (top-left/bottom-right axis). */
    static void dct16OriginalToFlipPlus1(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[j][i] = A[i][j];
            }
        }
    }

    /** Transpose about the anti-diagonal (top-right/bottom-left axis). */
    static void dct16OriginalToFlipMinus1(float[][] A, float[][] B) {
        for (int i = 0; i < KEEP; i++) {
            for (int j = 0; j < KEEP; j++) {
                B[j][i] = (((i + j) & 1) != 0) ? -A[i][j] : A[i][j];
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: quality metric (matching C++ pdqImageDomainQualityMetric)
    // -----------------------------------------------------------------------

    static int qualityMetric(float[][] buf) {
        int gradientSum = 0;
        // vertical gradients
        for (int i = 0; i < 63; i++) {
            for (int j = 0; j < 64; j++) {
                int d = (int) ((buf[i][j] - buf[i + 1][j]) * 100 / 255);
                gradientSum += Math.abs(d);
            }
        }
        // horizontal gradients
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 63; j++) {
                int d = (int) ((buf[i][j] - buf[i][j + 1]) * 100 / 255);
                gradientSum += Math.abs(d);
            }
        }
        return Math.min(100, gradientSum / 90);
    }

    // -----------------------------------------------------------------------
    // Internal: Torben median (O(n log n) via sort — reference uses O(n) Torben
    // but results must match; a sort gives the same median value)
    // -----------------------------------------------------------------------

    static float torbenMedian(float[] arr) {
        float[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 1)
            ? sorted[n / 2]
            : (sorted[n / 2 - 1] + sorted[n / 2]) / 2f;
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Usage: java pdq.PdqHasher image1.jpg [image2.jpg ...]
     * Prints: quality  hex-hash  filename
     * If two files, also prints Hamming distance.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: PdqHasher image1 [image2 ...]");
            System.exit(1);
        }
        Result[] results = new Result[args.length];
        for (int i = 0; i < args.length; i++) {
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(args[i]));
            if (img == null) { System.err.println("Cannot read: " + args[i]); System.exit(1); }
            results[i] = hash(img);
            System.out.printf("quality=%3d  hash=%s  %s%n",
                results[i].quality, results[i].toHexString(), args[i]);
        }
        if (args.length == 2) {
            int dist = results[0].hammingDistance(results[1]);
            System.out.printf("Hamming distance: %d  (%s)%n",
                dist, dist <= 31 ? "SIMILAR" : "different");
        }
    }
}
