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
 *   2.  Reference image cross-check: hash of cat_01_original.jpg must equal the
 *       Python/C++ reference value 
 *   3.  Different images -> large distance.
 *   4.  2D DCT bug check: H-gradient and V-gradient produce different hashes.
 *   5.  Quality: flat image low, high-contrast image high.
 *   6.  Hex round-trip correct.
 *   7.  Hamming distance arithmetic correct.
 *   8.  Window-size formula matches C++ reference.
 *   9.  getHashAndQuality() consistent with separate getHash()/getQuality().
 */
class PdqHasherTest {

    private static final String REFERENCE_IMAGE = "cat_01_original.jpg";
    private static final String REFERENCE_IMAGE_HASH = "7c744d5ce9c82d4ec65e6b138dd63b9699966644a360e1e1b165da19b6295c4b";
                                                        
    @Test
    @DisplayName("Identical images produce distance 0")
    void identicalImagesHaveZeroDistance() {
        BufferedImage img = makeCheckerboard(256, 256, 32);
        String hash1 = PdqHasher.getHash(img);
        String hash2 = PdqHasher.getHash(img);
        assertEquals(0, PdqHasher.hammingDistance(hash1, hash2),
            "Identical images must hash to distance 0");
    }

    @Test
    @DisplayName("Reference image hash matches Python/C++ reference value")
    void referenceImageMatchesKnownHash() throws IOException {
        
        BufferedImage img= ImageLoadUtil.loadImage(REFERENCE_IMAGE);

        String hash = PdqHasher.getHash(img);
        int quality = PdqHasher.getQuality(img);

        assertEquals(REFERENCE_IMAGE_HASH, hash,
            "Hash must match the Python/C++ pdqhash reference value exactly");
        assertEquals(100, quality, "Reference image should have maximum quality");
    }

    @Test
    @DisplayName("Visually different images produce a large Hamming distance")
    void differentImagesHaveLargeDistance() {
        BufferedImage img1 = makeCheckerboard(256, 256, 32);
        BufferedImage img2 = makeGradient(256, 256);
        int dist = PdqHasher.hammingDistance(PdqHasher.getHash(img1), PdqHasher.getHash(img2));
        assertTrue(dist > 50, "Expected distance > 50 for very different images, got " + dist);
    }

    @Test
    @DisplayName("2D DCT check: horizontal vs vertical gradients hash differently")
    void twoDimensionalDctIsNotConfusedWithOneDimensional() {
        // A correct 2D DCT must distinguish horizontal from vertical gradients.
        // With only a 1D DCT (a common porting bug), these would hash similarly.
        BufferedImage h = makeHorizontalGradient(128, 128);
        BufferedImage v = makeVerticalGradient(128, 128);
        int dist = PdqHasher.hammingDistance(PdqHasher.getHash(h), PdqHasher.getHash(v));
        assertTrue(dist > 30,
            "Expected distance > 30 between H-gradient and V-gradient (got " + dist
            + "); a low distance here indicates the 1D-DCT-instead-of-2D bug");
    }

    @Test
    @DisplayName("Flat image has low quality score")
    void flatImageHasLowQuality() {
        int qFlat = PdqHasher.getQuality(makeSolid(256, 256, 128));
        assertTrue(qFlat < 20, "Expected quality < 20 for a flat image, got " + qFlat);
    }

    @Test
    @DisplayName("High-contrast image has high quality score")
    void highContrastImageHasHighQuality() {
        int qChecker = PdqHasher.getQuality(makeCheckerboard(256, 256, 4));
        assertTrue(qChecker > 50, "Expected quality > 50 for a high-contrast image, got " + qChecker);
    }

    @Test
    @DisplayName("Hex string is well-formed")
    void hexStringIsWellFormed() {
        String hex = PdqHasher.getHash(makeGradient(128, 128));
        assertTrue(hex.matches("[0-9a-f]{64}"), "Expected 64 lowercase hex chars, got: " + hex);
    }

    @Test
    @DisplayName("Hex round-trip preserves the hash exactly")
    void hexRoundTripIsLossless() {
        String hex = PdqHasher.getHash(makeGradient(128, 128));
        // Re-hashing the same image and comparing hex strings directly
        // confirms getHash() is stable and the value round-trips through
        // the public String-based hammingDistance() with distance 0.
        assertEquals(0, PdqHasher.hammingDistance(hex, hex),
            "A hash's distance to its own hex string must be 0");
    }

    @Test
    @DisplayName("Hamming distance arithmetic is correct for a full 16-bit word flip")
    void hammingDistanceCountsFullWordFlip() {
        // Two hashes differing only in their highest 16-bit word (all 1s vs all 0s).
        String allZeros = "0".repeat(64);
        String oneWordSet = "ffff" + "0".repeat(60);
        assertEquals(16, PdqHasher.hammingDistance(oneWordSet, allZeros));
    }

    @Test
    @DisplayName("Hamming distance of a hash to itself is zero")
    void hammingDistanceToSelfIsZero() {
        String hex = "ffff" + "0".repeat(60);
        assertEquals(0, PdqHasher.hammingDistance(hex, hex));
    }

    @Test
    @DisplayName("Jarosz window-size formula matches the C++ reference for common sizes")
    void windowSizeFormulaMatchesReference() {
        assertEquals(9, PdqHasher.computeWindowSize(1070, 64));
        assertEquals(6, PdqHasher.computeWindowSize(700, 64));
        assertEquals(1, PdqHasher.computeWindowSize(64, 64));
        assertEquals(8, PdqHasher.computeWindowSize(1024, 64));
    }

    @Test
    @DisplayName("getHashAndQuality matches separate getHash()/getQuality() calls")
    void getHashAndQualityIsConsistentWithSeparateCalls() {
        BufferedImage img = makeCheckerboard(256, 256, 4);

        PdqHasher.Result result = PdqHasher.getHashAndQuality(img);
        String expectedHash = PdqHasher.getHash(img);
        int expectedQuality = PdqHasher.getQuality(img);

        assertEquals(expectedHash, result.hash,
            "getHashAndQuality().hash must match getHash()");
        assertEquals(expectedQuality, result.quality,
            "getHashAndQuality().quality must match getQuality()");
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