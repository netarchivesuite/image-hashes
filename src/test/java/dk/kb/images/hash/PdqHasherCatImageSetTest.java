package dk.kb.images.hash;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PDQ hash tests against a fixed set of real-photo variants.
 *
 * The original image (cat_01_original.jpg) is loaded and hashed exactly
 * once in {@link #setUp()}. Each test method below loads one transformed
 * variant, hashes it, and computes the Hamming distance back to the
 * original. The assertion in each method is left for manual calibration —
 * see the measured reference values noted in each test's comment.
 *
 * Image set background: all variants are derived from the same source
 * photo (a yawning cat) using a fixed set of transforms — see the
 * conversation/README for how each file was generated. Geometric
 * transforms (rotation, mirroring, cropping) are included deliberately:
 * PDQ is not rotation- or mirror-invariant by design, so those tests are
 * expected to demonstrate a LARGE distance, documenting a known
 * limitation rather than catching a bug.
 *
 * Reference distances measured against this Java implementation
 * (256-bit hash, max distance 256):
 *
 *   cat_01_original.jpg        0    (identical)
 *   cat_02_downscaled.jpg     16    (25% resolution)
 *   cat_03_grayscale.jpg       2    (colour removed)
 *   cat_04_rotated45.jpg     114    (45-degree rotation)
 *   cat_05_rotated90.jpg     136    (90-degree rotation)
 *   cat_06_rotated180.jpg    130    (180-degree rotation)
 *   cat_07_mirrored.jpg      128    (horizontal flip)
 *   cat_08_noise.jpg           2    (visible random noise)
 *   cat_09_text_opaque.jpg    40    (opaque caption bar)
 *   cat_10_text_overlay.jpg   10    (translucent watermark)
 *   cat_11_jpeg_lowquality.jpg 4    (quality=15 recompression)
 *   cat_12_cropped.jpg       130    (15% cropped off each edge)
 */
class PdqHasherCatImageSetTest {

    /** Classpath-relative location: src/test/resources/test_images/ */
    private static final String IMAGE_DIR = "test_images/";

    private static PdqHasher.Result originalHash;

    @BeforeAll
    static void setUp() throws IOException {
        BufferedImage original = loadImage("cat_01_original.jpg");
        originalHash = PdqHasher.hash(original);
    }

    @Test
    @DisplayName("Original image matches itself (sanity check)")
    void original() throws IOException {
        BufferedImage img = loadImage("cat_01_original.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_01_original.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 0)
    }

    @Test
    @DisplayName("Downscaled to 25% resolution")
    void downscaled() throws IOException {
        BufferedImage img = loadImage("cat_02_downscaled.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_02_downscaled.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 16)
    }

    @Test
    @DisplayName("Converted to grayscale")
    void grayscale() throws IOException {
        BufferedImage img = loadImage("cat_03_grayscale.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_03_grayscale.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 2)
    }

    @Test
    @DisplayName("Rotated 45 degrees (expected to fail similarity threshold)")
    void rotated45() throws IOException {
        BufferedImage img = loadImage("cat_04_rotated45.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_04_rotated45.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 114) -- documents a known
        // PDQ limitation: the algorithm is not rotation-invariant.
    }

    @Test
    @DisplayName("Rotated 90 degrees (expected to fail similarity threshold)")
    void rotated90() throws IOException {
        BufferedImage img = loadImage("cat_05_rotated90.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_05_rotated90.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 136) -- documents a known
        // PDQ limitation: the algorithm is not rotation-invariant.
    }

    @Test
    @DisplayName("Rotated 180 degrees (expected to fail similarity threshold)")
    void rotated180() throws IOException {
        BufferedImage img = loadImage("cat_06_rotated180.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_06_rotated180.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 130) -- documents a known
        // PDQ limitation: the algorithm is not rotation-invariant.
    }

    @Test
    @DisplayName("Mirrored horizontally (expected to fail similarity threshold)")
    void mirrored() throws IOException {
        BufferedImage img = loadImage("cat_07_mirrored.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_07_mirrored.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 128) -- documents a known
        // PDQ limitation: the algorithm is not mirror-invariant.
    }

    @Test
    @DisplayName("Visible random pixel noise added")
    void noise() throws IOException {
        BufferedImage img = loadImage("cat_08_noise.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_08_noise.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 2)
    }

    @Test
    @DisplayName("Opaque caption bar added")
    void textOpaque() throws IOException {
        BufferedImage img = loadImage("cat_09_text_opaque.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_09_text_opaque.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 40)
    }

    @Test
    @DisplayName("Translucent watermark text overlay added")
    void textOverlay() throws IOException {
        BufferedImage img = loadImage("cat_10_text_overlay.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_10_text_overlay.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 10)
    }

    @Test
    @DisplayName("Heavy JPEG recompression (quality 15)")
    void jpegLowQuality() throws IOException {
        BufferedImage img = loadImage("cat_11_jpeg_lowquality.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_11_jpeg_lowquality.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 4)
    }

    @Test
    @DisplayName("Cropped 15% off each edge (expected to fail similarity threshold)")
    void cropped() throws IOException {
        BufferedImage img = loadImage("cat_12_cropped.jpg");
        PdqHasher.Result hash = PdqHasher.hash(img);
        int distance = originalHash.hammingDistance(hash);
        System.out.println("cat_12_cropped.jpg distance = " + distance);

        // TODO: assert expected distance (measured: 130) -- cropping shifts
        // the framing enough that PDQ's similarity threshold is exceeded.
    }

    // -----------------------------------------------------------------------

    private static BufferedImage loadImage(String filename) throws IOException {
        String resourcePath = IMAGE_DIR + filename;
        try (InputStream in = PdqHasherCatImageSetTest.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Could not find test resource on classpath: " + resourcePath
                + " (expected at src/test/resources/" + resourcePath + ")");
            BufferedImage img = javax.imageio.ImageIO.read(in);
            assertNotNull(img, "ImageIO could not decode resource: " + resourcePath);
            return img;
        }
    }
}