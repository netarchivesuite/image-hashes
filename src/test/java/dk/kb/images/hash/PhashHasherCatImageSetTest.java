package dk.kb.images.hash;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pHash tests against a fixed set of real-photo variants.
 *
 * The original image (cat_01_original.jpg) is loaded and hashed exactly
 * once in {@link #setUp()}. Each test method below loads one transformed
 * variant, hashes it, and computes the Hamming distance back to the
 * original. The assertion in each method is left for manual calibration —
 * see the measured reference values noted in each test's comment.
 *
 * Image set background: all variants are derived from the same source
 * photo (a yawning cat) using a fixed set of transforms — see the
 * github README for how each file was generated. Geometric
 * transforms (rotation, mirroring, cropping) are included deliberately:
 * pHash is not rotation- or mirror-invariant by design, so those tests are
 * expected to demonstrate a LARGE distance, documenting a known
 * limitation rather than catching a bug.
 *
 * Reference distances measured against this Java implementation
 * (64-bit hash, max distance 64):
 *
 *   cat_01_original.jpg        0    (identical)
 *   cat_02_downscaled.jpg      0    (25% resolution)
 *   cat_03_grayscale.jpg       0    (colour removed)
 *   cat_04_rotated45.jpg      28    (45-degree rotation)
 *   cat_05_rotated90.jpg      32    (90-degree rotation)
 *   cat_06_rotated180.jpg     34    (180-degree rotation)
 *   cat_07_mirrored.jpg       30    (horizontal flip)
 *   cat_08_noise.jpg           0    (visible random noise)
 *   cat_09_text_opaque.jpg     6    (opaque caption bar)
 *   cat_10_text_overlay.jpg    2    (translucent watermark)
 *   cat_11_jpeg_lowquality.jpg 0    (quality=15 recompression)
 *   cat_12_cropped.jpg        32    (15% cropped off each edge)
 */
class PhashHasherCatImageSetTest {

    private static final int SIMILARITY_THRESHOLD=10; // Seems to be the general recommendation for pHash
    private static String originalHash;

    @BeforeAll
    static void setUp() throws IOException {
        BufferedImage original = ImageLoadUtil.loadImage("cat_01_original.jpg");
        originalHash = PhashHasher.getHash(original);         
    }

    @Test
    @DisplayName("Original image matches itself (sanity check)")
    void original() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_01_original.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_01_original.jpg distance = " + distance);
        assertEquals(0,  distance);
        assertTrue(match(distance));            
    }

    @Test
    @DisplayName("Downscaled to 25% resolution")
    void downscaled() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_02_downscaled.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_02_downscaled.jpg distance = " + distance);
        assertEquals(0,  distance);
        assertTrue(match(distance));                
    }

    @Test
    @DisplayName("Converted to grayscale")
    void grayscale() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_03_grayscale.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_03_grayscale.jpg distance = " + distance);
        assertEquals(0,  distance);
        assertTrue(match(distance));
    }

    @Test
    @DisplayName("Rotated 45 degrees (expected to fail similarity threshold)")
    void rotated45() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_04_rotated45.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_04_rotated45.jpg distance = " + distance);
        assertEquals(28,  distance);
        assertFalse(match(distance));        
        // pHash limitation: the algorithm is not rotation-invariant.
    }

    @Test
    @DisplayName("Rotated 90 degrees (expected to fail similarity threshold)")
    void rotated90() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_05_rotated90.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_05_rotated90.jpg distance = " + distance);
        assertEquals(32,  distance);
        assertFalse(match(distance));        
        // pHash limitation: the algorithm is not rotation-invariant.
    }

    @Test
    @DisplayName("Rotated 180 degrees (expected to fail similarity threshold)")
    void rotated180() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_06_rotated180.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_06_rotated180.jpg distance = " + distance);
        assertEquals(34,  distance);
        assertFalse(match(distance));        
    }

    @Test
    @DisplayName("Mirrored horizontally (expected to fail similarity threshold)")
    void mirrored() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_07_mirrored.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_07_mirrored.jpg distance = " + distance);
        assertEquals(30,  distance);
        assertFalse(match(distance));
        // pHash limitation: the algorithm is not mirror-invariant.
    }

    @Test
    @DisplayName("Visible random pixel noise added")
    void noise() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_08_noise.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_08_noise.jpg distance = " + distance);
        assertEquals(0,  distance);
        assertTrue(match(distance));        
    }

    @Test
    @DisplayName("Opaque caption bar added")
    void textOpaque() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_09_text_opaque.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_09_text_opaque.jpg distance = " + distance);
        assertEquals(6,  distance);
        assertTrue(match(distance));        
    }

    @Test
    @DisplayName("Translucent watermark text overlay added")
    void textOverlay() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_10_text_overlay.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_10_text_overlay.jpg distance = " + distance);
        assertEquals(2,  distance);
        assertTrue(match(distance));                
    }

    @Test
    @DisplayName("Heavy JPEG recompression (quality 15)")
    void jpegLowQuality() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_11_jpeg_lowquality.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_11_jpeg_lowquality.jpg distance = " + distance);
        assertEquals(0,  distance);
        assertTrue(match(distance));        
    }

    @Test
    @DisplayName("Cropped 15% off each edge (expected to fail similarity threshold)")
    void cropped() throws IOException {
        BufferedImage img = ImageLoadUtil.loadImage("cat_12_cropped.jpg");
        String hash = PhashHasher.getHash(img);
        int distance = PhashHasher.hammingDistance(originalHash, hash);
        System.out.println("cat_12_cropped.jpg distance = " + distance);
        assertEquals(32,  distance);
        assertFalse(match(distance));               
        // the framing enough that pHash's similarity threshold is exceeded.
    }
    
    private static boolean match(int distance) {
        return (distance <= SIMILARITY_THRESHOLD);
    }
    
  
}