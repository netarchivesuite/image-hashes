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
 *   2.  Reference image cross-check: hash of cat_01_original.jpg must equal the
 *       phim (Rust/Python) reference value 
 *   3.  Different images -> large distance.
 *   4.  2D DCT bug check: H-gradient and V-gradient produce different hashes.
 *   5.  Hex round-trip correct.
 *   6.  Hamming distance arithmetic correct.
 *   7.  Robustness: same image resized differently still hashes near-identically.
 */
class PhashHasherTest {

    private static final String REFERENCE_IMAGE = "cat_01_original.jpg";
    private static final String REFERENCE_IMAGE_HASH = "d29499b7a706236b";

    @Test
    @DisplayName("Identical images produce distance 0")
    void identicalImagesHaveZeroDistance() {
        BufferedImage img = makeCheckerboard(256, 256, 32);
        String hash1 = PhashHasher.getHash(img);
        String hash2 = PhashHasher.getHash(img);
        assertEquals(0, PhashHasher.hammingDistance(hash1, hash2),
            "Identical images must hash to distance 0");
    }

    @Test
    @DisplayName("Reference image hash matches phim reference value")
    void referenceImageMatchesKnownHash() throws IOException {       
        BufferedImage img= ImageLoadUtil.loadImage(REFERENCE_IMAGE);        
        String hash = PhashHasher.getHash(img);
        assertEquals(REFERENCE_IMAGE_HASH, hash,
            "Hash must match the phim (Rust/Python) reference value exactly");
    }

    @Test
    @DisplayName("Visually different images produce a large Hamming distance")
    void differentImagesHaveLargeDistance() {
        BufferedImage img1 = makeCheckerboard(256, 256, 32);
        BufferedImage img2 = makeGradient(256, 256);
        int dist = PhashHasher.hammingDistance(
            PhashHasher.getHash(img1), PhashHasher.getHash(img2));
        assertTrue(dist > 15, "Expected distance > 15 (of 64) for very different images, got " + dist);
    }

    @Test
    @DisplayName("2D DCT check: horizontal vs vertical gradients hash differently")
    void twoDimensionalDctIsNotConfusedWithOneDimensional() {
        BufferedImage h = makeHorizontalGradient(128, 128);
        BufferedImage v = makeVerticalGradient(128, 128);
        int dist = PhashHasher.hammingDistance(
            PhashHasher.getHash(h), PhashHasher.getHash(v));
        assertTrue(dist > 8,
            "Expected distance > 8 (of 64) between H-gradient and V-gradient (got " + dist
            + "); a low distance here indicates the 1D-DCT-instead-of-2D bug");
    }

    @Test
    @DisplayName("Hex string is well-formed")
    void hexStringIsWellFormed() {
        String hex = PhashHasher.getHash(makeGradient(128, 128));
        assertTrue(hex.matches("[0-9a-f]{16}"), "Expected 16 lowercase hex chars, got: " + hex);
    }

    @Test
    @DisplayName("Hex round-trip preserves the hash exactly")
    void hexRoundTripIsLossless() {
        String hex = PhashHasher.getHash(makeGradient(128, 128));
        assertEquals(0, PhashHasher.hammingDistance(hex, hex),
            "A hash's distance to its own hex string must be 0");
    }

    @Test
    @DisplayName("Hamming distance arithmetic is correct for a full byte flip")
    void hammingDistanceCountsFullByteFlip() {
        // Two hashes differing only in their highest byte (0xFF vs 0x00).
        String allZeros = "0".repeat(16);
        String oneByteSet = "ff" + "0".repeat(14);
        assertEquals(8, PhashHasher.hammingDistance(oneByteSet, allZeros));
    }

    @Test
    @DisplayName("Hamming distance of a hash to itself is zero")
    void hammingDistanceToSelfIsZero() {
        String hex = "ff" + "0".repeat(14);
        assertEquals(0, PhashHasher.hammingDistance(hex, hex));
    }

    @Test
    @DisplayName("Resized version of the same image hashes near-identically")
    void resizedImageHasSmallDistance() throws IOException {
        
        
        BufferedImage img= ImageLoadUtil.loadImage(REFERENCE_IMAGE);
        BufferedImage half = new BufferedImage(
         img.getWidth() / 2, img.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = half.createGraphics();
        g.drawImage(img, 0, 0, img.getWidth() / 2, img.getHeight() / 2, null);
        g.dispose();

        int dist = PhashHasher.hammingDistance(
            PhashHasher.getHash(img), PhashHasher.getHash(half));
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