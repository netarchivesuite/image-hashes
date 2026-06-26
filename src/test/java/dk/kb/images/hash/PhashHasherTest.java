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
 * JUnit 5 tests for PhashHasher — validates correctness of the
 * phim-compatible Java pHash implementation.
 *
 * Test coverage:
 *   1.  Identical images -> distance 0.
 *   2.  Reference image cross-check: hash of maria.png must equal the
 *       phim (Rust/Python) reference value (if the file is present; skipped otherwise).
 *   3.  Different images -> large distance.
 *   4.  2D DCT bug check: H-gradient and V-gradient produce different hashes.
 *   5.  Hex round-trip correct.
 *   6.  Hamming distance arithmetic correct.
 *   7.  Robustness: same image resized differently still hashes near-identically.
 */
class PhashHasherTest {

    private static final String REFERENCE_IMAGE_PATH = "/mnt/user-data/uploads/maria.png";
    private static final String REFERENCE_IMAGE_HASH = "38e427759da86355";

    @Test
    @DisplayName("Identical images produce distance 0")
    void identicalImagesHaveZeroDistance() {
        BufferedImage img = makeCheckerboard(256, 256, 32);
        PhashHasher.Result r1 = PhashHasher.hash(img);
        PhashHasher.Result r2 = PhashHasher.hash(img);
        assertEquals(0, r1.hammingDistance(r2), "Identical images must hash to distance 0");
    }

    @Test
    @DisplayName("Reference image hash matches phim reference value")
    void referenceImageMatchesKnownHash() throws IOException {
        File f = new File(REFERENCE_IMAGE_PATH);
        assumeTrue(f.exists(), "Reference image not found at " + REFERENCE_IMAGE_PATH + "; skipping");

        BufferedImage img = javax.imageio.ImageIO.read(f);
        PhashHasher.Result r = PhashHasher.hash(img);

        assertEquals(REFERENCE_IMAGE_HASH, r.toHexString(),
            "Hash must match the phim (Rust/Python) reference value exactly");
    }

    @Test
    @DisplayName("Visually different images produce a large Hamming distance")
    void differentImagesHaveLargeDistance() {
        BufferedImage img1 = makeCheckerboard(256, 256, 32);
        BufferedImage img2 = makeGradient(256, 256);
        int dist = PhashHasher.hash(img1).hammingDistance(PhashHasher.hash(img2));
        assertTrue(dist > 15, "Expected distance > 15 (of 64) for very different images, got " + dist);
    }

    @Test
    @DisplayName("2D DCT check: horizontal vs vertical gradients hash differently")
    void twoDimensionalDctIsNotConfusedWithOneDimensional() {
        BufferedImage h = makeHorizontalGradient(128, 128);
        BufferedImage v = makeVerticalGradient(128, 128);
        int dist = PhashHasher.hash(h).hammingDistance(PhashHasher.hash(v));
        assertTrue(dist > 8,
            "Expected distance > 8 (of 64) between H-gradient and V-gradient (got " + dist
            + "); a low distance here indicates the 1D-DCT-instead-of-2D bug");
    }

    @Test
    @DisplayName("Hex string is well-formed")
    void hexStringIsWellFormed() {
        PhashHasher.Result r = PhashHasher.hash(makeGradient(128, 128));
        String hex = r.toHexString();
        assertTrue(hex.matches("[0-9a-f]{16}"), "Expected 16 lowercase hex chars, got: " + hex);
    }

    @Test
    @DisplayName("Hex round-trip preserves the hash exactly")
    void hexRoundTripIsLossless() {
        PhashHasher.Result r = PhashHasher.hash(makeGradient(128, 128));
        String hex = r.toHexString();
        byte[] parsed = PhashHasher.fromHexString(hex);
        assertEquals(0, PhashHasher.hammingDistance(r.bytes, parsed),
            "Parsing a hash's own hex string must reproduce the identical hash");
    }

    @Test
    @DisplayName("Hamming distance arithmetic is correct for a full byte flip")
    void hammingDistanceCountsFullByteFlip() {
        byte[] a = new byte[8];
        a[7] = (byte) 0xFF;
        byte[] b = new byte[8];
        assertEquals(8, PhashHasher.hammingDistance(a, b));
    }

    @Test
    @DisplayName("Hamming distance of a hash to itself is zero")
    void hammingDistanceToSelfIsZero() {
        byte[] a = new byte[8];
        a[7] = (byte) 0xFF;
        assertEquals(0, PhashHasher.hammingDistance(a, a));
    }

    @Test
    @DisplayName("Resized version of the same image hashes near-identically")
    void resizedImageHasSmallDistance() throws IOException {
        File f = new File(REFERENCE_IMAGE_PATH);
        assumeTrue(f.exists(), "Reference image not found at " + REFERENCE_IMAGE_PATH + "; skipping");

        BufferedImage img = javax.imageio.ImageIO.read(f);
        BufferedImage half = new BufferedImage(
            img.getWidth() / 2, img.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = half.createGraphics();
        g.drawImage(img, 0, 0, img.getWidth() / 2, img.getHeight() / 2, null);
        g.dispose();

        int dist = PhashHasher.hash(img).hammingDistance(PhashHasher.hash(half));
        assertTrue(dist < 10, "Expected distance < 10 (of 64) for a 50% resize, got " + dist);
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
                int v = 255 * x / w;
                int u = 255 * y / h;
                img.setRGB(x, y, (v << 16) | (u << 8) | ((v + u) / 2));
            }
        }
        return img;
    }

    private static BufferedImage makeHorizontalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 255 * x / w;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static BufferedImage makeVerticalGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 255 * y / h;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }
}