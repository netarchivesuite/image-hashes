package dk.kb.images.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit 5 tests for PdqHasher — validates correctness of the
 * reference-compatible Java PDQ implementation.
 *
 * Test coverage:
 *   1.  Identical images -> distance 0.
 *   2.  Reference image cross-check: hash of maria.png must equal the
 *       Python/C++ reference value (if the file is present; skipped otherwise).
 *   3.  Different images -> large distance.
 *   4.  2D DCT bug check: H-gradient and V-gradient produce different hashes.
 *   5.  Quality: flat image low, high-contrast image high.
 *   6.  Hex round-trip correct.
 *   7.  Hamming distance arithmetic correct.
 *   8.  Window-size formula matches C++ reference.
 */
class PdqHasherTest {

    private static final String REFERENCE_IMAGE_PATH = "/mnt/user-data/uploads/maria.png";
    private static final String REFERENCE_IMAGE_HASH =
        "aa74a9e4b3952eb95c5711e6a5b2dad1d5a852c62e0155b995ae2be4d823e31c";

    @Test
    @DisplayName("Identical images produce distance 0")
    void identicalImagesHaveZeroDistance() {
        BufferedImage img = makeCheckerboard(256, 256, 32);
        PdqHasher.Result r1 = PdqHasher.hash(img);
        PdqHasher.Result r2 = PdqHasher.hash(img);
        assertEquals(0, r1.hammingDistance(r2), "Identical images must hash to distance 0");
    }

    @Test
    @DisplayName("Reference image hash matches Python/C++ reference value")
    void referenceImageMatchesKnownHash() throws IOException {
        File f = new File(REFERENCE_IMAGE_PATH);
        assumeTrue(f.exists(), "Reference image not found at " + REFERENCE_IMAGE_PATH + "; skipping");

        BufferedImage img = javax.imageio.ImageIO.read(f);
        PdqHasher.Result r = PdqHasher.hash(img);

        assertEquals(REFERENCE_IMAGE_HASH, r.toHexString(),
            "Hash must match the Python/C++ pdqhash reference value exactly");
        assertEquals(100, r.quality, "Reference image should have maximum quality");
    }

    @Test
    @DisplayName("Visually different images produce a large Hamming distance")
    void differentImagesHaveLargeDistance() {
        BufferedImage img1 = makeCheckerboard(256, 256, 32);
        BufferedImage img2 = makeGradient(256, 256);
        int dist = PdqHasher.hash(img1).hammingDistance(PdqHasher.hash(img2));
        assertTrue(dist > 50, "Expected distance > 50 for very different images, got " + dist);
    }

    @Test
    @DisplayName("2D DCT check: horizontal vs vertical gradients hash differently")
    void twoDimensionalDctIsNotConfusedWithOneDimensional() {
        // A correct 2D DCT must distinguish horizontal from vertical gradients.
        // With only a 1D DCT (a common porting bug), these would hash similarly.
        BufferedImage h = makeHorizontalGradient(128, 128);
        BufferedImage v = makeVerticalGradient(128, 128);
        int dist = PdqHasher.hash(h).hammingDistance(PdqHasher.hash(v));
        assertTrue(dist > 30,
            "Expected distance > 30 between H-gradient and V-gradient (got " + dist
            + "); a low distance here indicates the 1D-DCT-instead-of-2D bug");
    }

    @Test
    @DisplayName("Flat image has low quality score")
    void flatImageHasLowQuality() {
        int qFlat = PdqHasher.hash(makeSolid(256, 256, 128)).quality;
        assertTrue(qFlat < 20, "Expected quality < 20 for a flat image, got " + qFlat);
    }

    @Test
    @DisplayName("High-contrast image has high quality score")
    void highContrastImageHasHighQuality() {
        int qChecker = PdqHasher.hash(makeCheckerboard(256, 256, 4)).quality;
        assertTrue(qChecker > 50, "Expected quality > 50 for a high-contrast image, got " + qChecker);
    }

    @Test
    @DisplayName("Hex string is well-formed")
    void hexStringIsWellFormed() {
        PdqHasher.Result r = PdqHasher.hash(makeGradient(128, 128));
        String hex = r.toHexString();
        assertTrue(hex.matches("[0-9a-f]{64}"), "Expected 64 lowercase hex chars, got: " + hex);
    }

    @Test
    @DisplayName("Hex round-trip preserves the hash exactly")
    void hexRoundTripIsLossless() {
        PdqHasher.Result r = PdqHasher.hash(makeGradient(128, 128));
        String hex = r.toHexString();
        int[] parsed = PdqHasher.fromHexString(hex);
        assertEquals(0, PdqHasher.hammingDistance(r.words, parsed),
            "Parsing a hash's own hex string must reproduce the identical hash");
    }

    @Test
    @DisplayName("Hamming distance arithmetic is correct for a full 16-bit word flip")
    void hammingDistanceCountsFullWordFlip() {
        int[] a = new int[16];
        a[15] = 0xFFFF;
        int[] b = new int[16];
        assertEquals(16, PdqHasher.hammingDistance(a, b));
    }

    @Test
    @DisplayName("Hamming distance of a hash to itself is zero")
    void hammingDistanceToSelfIsZero() {
        int[] a = new int[16];
        a[15] = 0xFFFF;
        assertEquals(0, PdqHasher.hammingDistance(a, a));
    }

    @Test
    @DisplayName("Jarosz window-size formula matches the C++ reference for common sizes")
    void windowSizeFormulaMatchesReference() {
        assertEquals(9, PdqHasher.computeWindowSize(1070, 64));
        assertEquals(6, PdqHasher.computeWindowSize(700, 64));
        assertEquals(1, PdqHasher.computeWindowSize(64, 64));
        assertEquals(8, PdqHasher.computeWindowSize(1024, 64));
    }

    // -----------------------------------------------------------------------
    // Image generators
    // -----------------------------------------------------------------------

    private static BufferedImage makeCheckerboard(int w, int h, int blockSize) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean black = ((x / blockSize) + (y / blockSize)) % 2 == 0;
                img.setRGB(x, y, black ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    private static BufferedImage makeGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) (255.0 * x / w);
                int u = (int) (255.0 * y / h);
                img.setRGB(x, y, (v << 16) | (u << 8) | ((v + u) / 2));
            }
        }
        return img;
    }

    private static BufferedImage makeHorizontalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) (255.0 * x / w);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static BufferedImage makeVerticalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) (255.0 * y / h);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static BufferedImage makeSolid(int w, int h, int grey) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int rgb = (grey << 16) | (grey << 8) | grey;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @SuppressWarnings("unused") // kept for parity with PhashHasherTest / potential future tests
    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }
}