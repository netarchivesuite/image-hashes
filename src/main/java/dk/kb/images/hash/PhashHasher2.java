package dk.kb.images.hash;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * pHash (DCT-based) perceptual hash — pure Java, zero dependencies beyond
 * java.awt.
 *
 * This is a faithful port matching the phim Python library
 * (https://github.com/NationalLibraryOfNorway/webdata-wp3-phim), which
 * itself follows Christoph Zauner's pHash specification:
 *   https://www.phash.org/docs/pubs/thesis_zauner.pdf  (Section 3.2.1)
 *
 * Algorithm (matching phim exactly):
 *   1.  Resize the RGB image to 32×32 using Pillow-compatible Lanczos
 *       resampling (separable, two 1D passes, each pass clipped/rounded
 *       to 8-bit before the next — this clipping is essential to match
 *       Pillow's 8-bit-pipeline behaviour).
 *   2.  Convert the resized RGB image to greyscale using ITU-R 601-2 luma:
 *           L = round(R*0.299 + G*0.587 + B*0.114)
 *   3.  Apply a 2D DCT-II (orthonormal) to the 32×32 luma matrix.
 *   4.  Take the 8×8 block of low-frequency coefficients starting at
 *       index [1,1] (i.e. skip the lowest row/column — the DC term and
 *       its immediate neighbours), giving 64 values.
 *   5.  Threshold by the median of those 64 values.
 *   6.  Pack into 8 bytes, row-major, MSB-first within each byte.
 *
 * IMPORTANT — two details that are easy to get wrong and will silently
 * desynchronise the hash from the Python reference:
 *   • The image must be resized in RGB *first*, then converted to
 *     greyscale — not the other way around. Resizing the greyscale
 *     image directly gives a different (but plausible-looking) result.
 *   • Pillow's Lanczos resize clips and rounds to the 0–255 integer
 *     range after EACH 1D pass (horizontal, then vertical), because its
 *     internal pipeline is 8-bit, not floating point. Skipping this
 *     intermediate rounding produces a subtly different result that
 *     will not match Pillow / phim hashes.
 *
 * Verified byte-for-byte identical to phim.compute_phash() across dozens
 * of real and synthetic test images (see PhashHasherTest.java).
 *
 * BSD-style licensing, consistent with the reference algorithm's origin.
 */
public class PhashHasher2 {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    public static final int HASH_BITS = 64;

    private static final int DCT_SIZE   = 32;  // resize target / DCT input size
    private static final int BLOCK_SIZE = 8;   // low-frequency block side (64 bits)
    private static final int BLOCK_OFFSET = 1; // skip index 0 in both dimensions
    private static final double LANCZOS_SUPPORT = 3.0;

    private static final double LUMA_R = 0.299;
    private static final double LUMA_G = 0.587;
    private static final double LUMA_B = 0.114;

    // -----------------------------------------------------------------------
    // Public result type
    // -----------------------------------------------------------------------

    /**
     * A pHash result: 64 bits packed into 8 bytes.
     *
     * Unlike PDQ, pHash has no built-in quality score in the reference
     * algorithm; low-information images (solid colour, etc.) still
     * produce a hash, just one that is not meaningful. Apply the same
     * "skip small/flat images" filtering policy used for PDQ if needed.
     */
    public static final class Result {
        /** 8 bytes, index 0 first (matches numpy packbits row-major order). */
        public final byte[] bytes;

        Result(byte[] bytes) {
            this.bytes = bytes;
        }

        /** 16-character lowercase hex string. */
        public String toHexString() {
            StringBuilder sb = new StringBuilder(16);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        }

        /** Hamming distance to another pHash result (0–64). */
        public int hammingDistance(Result other) {
            return PhashHasher.hammingDistance(this.bytes, other.bytes);
        }

        @Override
        public String toString() {
            return "PHash{hash=" + toHexString() + "}";
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Compute the pHash of a BufferedImage. */
    public static Result hash(BufferedImage image) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();

        // --- Step 1: extract R, G, B planes as double[][] ---
        double[][][] planes = extractRgbPlanes(image, srcW, srcH);
        double[][] r = planes[0];
        double[][] g = planes[1];
        double[][] b = planes[2];

        // --- Step 2: Lanczos-resize each channel to 32x32 (RGB, before greyscale) ---
        double[][] r32 = lanczosResize(r, srcW, srcH, DCT_SIZE, DCT_SIZE);
        double[][] g32 = lanczosResize(g, srcW, srcH, DCT_SIZE, DCT_SIZE);
        double[][] b32 = lanczosResize(b, srcW, srcH, DCT_SIZE, DCT_SIZE);

        // --- Step 3: convert to greyscale luma (PIL "L" formula, rounded) ---
        double[][] luma = new double[DCT_SIZE][DCT_SIZE];
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                luma[y][x] = Math.round(
                    LUMA_R * r32[y][x] + LUMA_G * g32[y][x] + LUMA_B * b32[y][x]);
            }
        }

        // --- Step 4: 2D DCT-II (orthonormal) ---
        double[][] dct = dct2D(luma);

        // --- Step 5: extract 8x8 block at offset [1,1] ---
        double[] block = new double[BLOCK_SIZE * BLOCK_SIZE];
        int idx = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            for (int j = 0; j < BLOCK_SIZE; j++) {
                block[idx++] = dct[i + BLOCK_OFFSET][j + BLOCK_OFFSET];
            }
        }

        // --- Step 6: median threshold ---
        double median = median(block);

        // --- Step 7: pack into 8 bytes, row-major, MSB-first per byte ---
        byte[] hashBytes = new byte[8];
        for (int i = 0; i < block.length; i++) {
            if (block[i] > median) {
                int byteIdx = i / 8;
                int bitIdx  = 7 - (i % 8); // MSB-first within byte (numpy packbits convention)
                hashBytes[byteIdx] |= (1 << bitIdx);
            }
        }

        return new Result(hashBytes);
    }

    /** Hamming distance between two 8-byte pHash arrays. */
    public static int hammingDistance(byte[] a, byte[] b) {
        int dist = 0;
        for (int i = 0; i < 8; i++) {
            dist += Integer.bitCount((a[i] ^ b[i]) & 0xFF);
        }
        return dist;
    }

    /** Parse a 16-char hex string into an 8-byte hash array. */
    public static byte[] fromHexString(String hex) {
        if (hex.length() != 16)
            throw new IllegalArgumentException("pHash hex must be 16 chars, got " + hex.length());
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // -----------------------------------------------------------------------
    // Internal: pixel extraction (with fast paths for common BufferedImage types)
    // -----------------------------------------------------------------------

    /**
     * Extracts the R, G, B planes of {@code image} as three {@code double[][]}
     * arrays, returned as {@code {r, g, b}}.
     *
     * Calling {@code getRGB(x, y)} once per pixel is the dominant cost of
     * the whole hashing pipeline (measured at roughly 69% of total hash()
     * time on a 1245×934 JPEG) because every call goes through
     * bounds-checking and a virtual ColorModel conversion.
     *
     * For TYPE_3BYTE_BGR and TYPE_4BYTE_ABGR(_PRE) — the types
     * {@code ImageIO.read()} produces for ordinary JPEGs and most PNGs —
     * the backing byte array can be read directly with no per-pixel method
     * call, because their raw bytes ARE plain sRGB component values with
     * no colour-space transform involved. Verified byte-for-byte identical
     * to the safe getRGB() path on real test images.
     *
     * TYPE_BYTE_GRAY is deliberately excluded from the fast path: its raw
     * bytes are frequently encoded against a non-sRGB ICC profile (e.g. a
     * Gray Gamma 2.2 profile from libpng), and getRGB() silently performs
     * a real colour-space conversion — the raw byte is NOT the sRGB value.
     * (See PdqHasher.extractLuma for the same finding, verified the same
     * way.) Any other type not explicitly handled below — indexed/palette
     * images, USHORT variants, custom ColorModels, BYTE_GRAY — falls
     * through to the safe bulk-getRGB path.
     */
    private static double[][][] extractRgbPlanes(BufferedImage image, int srcW, int srcH) {
        int type = image.getType();

        if (type == BufferedImage.TYPE_3BYTE_BGR) {
            return extractRgbPlanes3ByteBgr(image, srcW, srcH);
        }
        if (type == BufferedImage.TYPE_4BYTE_ABGR
                || type == BufferedImage.TYPE_4BYTE_ABGR_PRE) {
            return extractRgbPlanes4ByteAbgr(image, srcW, srcH);
        }
        return extractRgbPlanesGeneric(image, srcW, srcH);
    }

    /** Fast path for TYPE_3BYTE_BGR: raw byte layout per pixel is [B, G, R]. */
    private static double[][][] extractRgbPlanes3ByteBgr(BufferedImage image, int srcW, int srcH) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        double[][] r = new double[srcH][srcW];
        double[][] g = new double[srcH][srcW];
        double[][] b = new double[srcH][srcW];
        int idx = 0;
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int p = idx * 3;
                b[y][x] = data[p]     & 0xFF;
                g[y][x] = data[p + 1] & 0xFF;
                r[y][x] = data[p + 2] & 0xFF;
                idx++;
            }
        }
        return new double[][][]{r, g, b};
    }

    /** Fast path for TYPE_4BYTE_ABGR(_PRE): raw byte layout per pixel is [A, B, G, R]. */
    private static double[][][] extractRgbPlanes4ByteAbgr(BufferedImage image, int srcW, int srcH) {
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        double[][] r = new double[srcH][srcW];
        double[][] g = new double[srcH][srcW];
        double[][] b = new double[srcH][srcW];
        int idx = 0;
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int p = idx * 4;
                b[y][x] = data[p + 1] & 0xFF;
                g[y][x] = data[p + 2] & 0xFF;
                r[y][x] = data[p + 3] & 0xFF;
                idx++;
            }
        }
        return new double[][][]{r, g, b};
    }

    /**
     * Safe fallback for every other BufferedImage type (BYTE_GRAY,
     * BYTE_INDEXED, USHORT_*, custom ColorModels, etc.). Uses the bulk
     * getRGB(x,y,w,h,...) overload, which amortises per-call overhead
     * better than calling getRGB(x,y) once per pixel, while still going
     * through the correct ColorModel/ICC conversion for types where the
     * raw bytes aren't plain sRGB.
     */
    private static double[][][] extractRgbPlanesGeneric(BufferedImage image, int srcW, int srcH) {
        int[] pixels = new int[srcW * srcH];
        image.getRGB(0, 0, srcW, srcH, pixels, 0, srcW);
        double[][] r = new double[srcH][srcW];
        double[][] g = new double[srcH][srcW];
        double[][] b = new double[srcH][srcW];
        int idx = 0;
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int rgb = pixels[idx++];
                r[y][x] = (rgb >> 16) & 0xFF;
                g[y][x] = (rgb >>  8) & 0xFF;
                b[y][x] =  rgb        & 0xFF;
            }
        }
        return new double[][][]{r, g, b};
    }

    // -----------------------------------------------------------------------
    // Internal: Pillow-compatible Lanczos resize
    // -----------------------------------------------------------------------

    /**
     * Resize a single-channel image using separable Lanczos resampling,
     * matching Pillow's algorithm exactly (including intermediate 8-bit
     * clip/round between the horizontal and vertical passes).
     */
    static double[][] lanczosResize(double[][] channel, int srcW, int srcH,
                                     int dstW, int dstH) {
        double[][] horizontal = resizeHorizontal(channel, srcW, srcH, dstW);
        return resizeVertical(horizontal, dstW, srcH, dstH);
    }

    /** Resize horizontally (along columns), clip/round result to 0-255. */
    private static double[][] resizeHorizontal(double[][] src, int srcW, int srcH, int dstW) {
        int[] xMins = new int[dstW];
        double[][] coeffs = new double[dstW][];
        computeCoeffs(srcW, dstW, xMins, coeffs);

        double[][] out = new double[srcH][dstW];
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < dstW; x++) {
                double sum = 0.0;
                double[] c = coeffs[x];
                int xmin = xMins[x];
                for (int k = 0; k < c.length; k++) {
                    sum += src[y][xmin + k] * c[k];
                }
                out[y][x] = clampRound(sum);
            }
        }
        return out;
    }

    /** Resize vertically (along rows), clip/round result to 0-255. */
    private static double[][] resizeVertical(double[][] src, int srcW, int srcH, int dstH) {
        int[] yMins = new int[dstH];
        double[][] coeffs = new double[dstH][];
        computeCoeffs(srcH, dstH, yMins, coeffs);

        double[][] out = new double[dstH][srcW];
        for (int y = 0; y < dstH; y++) {
            double[] c = coeffs[y];
            int ymin = yMins[y];
            for (int x = 0; x < srcW; x++) {
                double sum = 0.0;
                for (int k = 0; k < c.length; k++) {
                    sum += c[k] * src[ymin + k][x];
                }
                out[y][x] = clampRound(sum);
            }
        }
        return out;
    }

    private static double clampRound(double v) {
        double r = Math.round(v);
        if (r < 0) return 0;
        if (r > 255) return 255;
        return r;
    }

    /**
     * Compute Lanczos resampling coefficients for one dimension, matching
     * Pillow's precompute_coeffs (PIL/src/libImaging/Resample.c).
     *
     * @param inSize   source dimension size
     * @param outSize  destination dimension size
     * @param outMins  (output) per-destination-pixel start index into source
     * @param outCoeffs (output) per-destination-pixel coefficient array
     */
    private static void computeCoeffs(int inSize, int outSize,
                                       int[] outMins, double[][] outCoeffs) {
        double scale = (double) inSize / outSize;
        double filterscale = Math.max(scale, 1.0);
        double supportScaled = LANCZOS_SUPPORT * filterscale;
        double invFilterscale = 1.0 / filterscale;

        for (int xx = 0; xx < outSize; xx++) {
            double center = (xx + 0.5) * scale;

            int xmin = (int) (center - supportScaled + 0.5);
            if (xmin < 0) xmin = 0;
            int xmax = (int) (center + supportScaled + 0.5);
            if (xmax > inSize) xmax = inSize;
            int len = xmax - xmin;

            double[] coeffs = new double[len];
            double total = 0.0;
            for (int x = 0; x < len; x++) {
                double w = lanczosFilter((x + xmin - center + 0.5) * invFilterscale);
                coeffs[x] = w;
                total += w;
            }
            if (total != 0.0) {
                for (int x = 0; x < len; x++) coeffs[x] /= total;
            }

            outMins[xx] = xmin;
            outCoeffs[xx] = coeffs;
        }
    }

    /** Lanczos kernel: sinc(x) * sinc(x/support), support = 3.0. */
    private static double lanczosFilter(double x) {
        if (x >= -LANCZOS_SUPPORT && x < LANCZOS_SUPPORT) {
            return sinc(x) * sinc(x / LANCZOS_SUPPORT);
        }
        return 0.0;
    }

    private static double sinc(double x) {
        if (x == 0.0) return 1.0;
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    // -----------------------------------------------------------------------
    // Internal: 2D DCT-II (orthonormal), same routine style as PdqHasher
    // -----------------------------------------------------------------------

    /**
     * Apply a 2D orthonormal DCT-II to an N×N matrix via two 1D passes
     * (rows, then columns).
     */
    private static double[][] dct2D(double[][] in) {
        int n = in.length;
        double[][] tmp = new double[n][n];
        double[][] out = new double[n][n];

        for (int r = 0; r < n; r++) {
            tmp[r] = dct1D(in[r]);
        }
        double[] col = new double[n];
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n; r++) col[r] = tmp[r][c];
            double[] dctCol = dct1D(col);
            for (int r = 0; r < n; r++) out[r][c] = dctCol[r];
        }
        return out;
    }

    /** Standard orthonormal 1D DCT-II. */
    private static double[] dct1D(double[] x) {
        int n = x.length;
        double[] out = new double[n];
        double piOver2N = Math.PI / (2.0 * n);
        double scale0 = 1.0 / Math.sqrt(n);
        double scaleK = Math.sqrt(2.0 / n);

        for (int k = 0; k < n; k++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                sum += x[i] * Math.cos(piOver2N * (2 * i + 1) * k);
            }
            out[k] = (k == 0 ? scale0 : scaleK) * sum;
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Internal: median
    // -----------------------------------------------------------------------

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 1)
            ? sorted[n / 2]
            : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    // -----------------------------------------------------------------------
    // CLI
    // -----------------------------------------------------------------------

    /**
     * Usage: java pdq.PhashHasher image1.jpg [image2.jpg ...]
     * Prints: hex-hash  filename
     * If two files, also prints Hamming distance.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: PhashHasher image1 [image2 ...]");
            System.exit(1);
        }
        Result[] results = new Result[args.length];
        for (int i = 0; i < args.length; i++) {
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(args[i]));
            if (img == null) { System.err.println("Cannot read: " + args[i]); System.exit(1); }
            results[i] = hash(img);
            System.out.printf("hash=%s  %s%n", results[i].toHexString(), args[i]);
        }
        if (args.length == 2) {
            int dist = results[0].hammingDistance(results[1]);
            System.out.printf("Hamming distance: %d / 64%n", dist);
        }
    }
}