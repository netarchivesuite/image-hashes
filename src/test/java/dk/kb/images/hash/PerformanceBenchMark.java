package dk.kb.images.hash;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Manual performance benchmark for {@link PdqHasher} and {@link PhashHasher}.
 * 
 * The image is loaded once. Three benchmarks are run against that same
 * image, each with its own warmup phase (so the JIT has compiled the hot
 * path before timing starts) followed by the timed run:
 *
 *   1. pdqHash                      - PdqHasher.getHash(image)
 *   2. pHash                        - PhashHasher.getHash(image)
 *   3. pdqHash (8 dihedral variants) - PdqHasher.getAllDihedralHashes(image)
 */
public class PerformanceBenchMark {

    /**
     * Warmup iterations before timing starts, capped at numHashes.
     *   
     */
    private static final int MAX_WARMUP = 200; //50 was not enough.

    public static void main(String[] args) {
   
        String imageName = "/home/xxx/xxx.png";
        int numHashes=1000;

    
        File imageFile = new File(imageName);
        if (!imageFile.exists() || !imageFile.isFile()) {
            System.err.println("ERROR: image file not found: " + imageFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        BufferedImage image;
        try {
            image = ImageIO.read(imageFile);
        } catch (IOException e) {
            System.err.println("ERROR: could not read image file: " + imageFile.getAbsolutePath()
                + " (" + e.getMessage() + ")");
            System.exit(1);
            return;
        }
        if (image == null) {
            System.err.println("ERROR: ImageIO could not decode image file (unsupported format?): "
                + imageFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        System.out.println("Image:       " + imageName
            + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        System.out.println("Iterations:  " + numHashes + " (warmup: " + Math.min(MAX_WARMUP, numHashes) + ")");
        System.out.println();

        runSingleHashBenchmark("pdqHash", image, numHashes, PdqHasher::getHash);
        System.out.println();

        runSingleHashBenchmark("pHash", image, numHashes, PhashHasher::getHash);
        System.out.println();

        runDihedralBenchmark(image, numHashes);
        System.out.println();
    }

    /**
     * Benchmarks a hash function with the simple "one image in, one hash
     * string out" shape — covers both pdqHash and pHash today.
     */
    private static void runSingleHashBenchmark(String name, BufferedImage image, int numHashes,
                                                java.util.function.Function<BufferedImage, String> hashFunction) {
        int warmup = Math.min(MAX_WARMUP, numHashes);

        System.out.println("--- " + name + " ---");

        // Warmup: let the JIT compile the hot path before timing.
        for (int i = 0; i < warmup; i++) {
            hashFunction.apply(image);
        }

        long start = System.nanoTime();
        String hash = null;
        for (int i = 0; i < numHashes; i++) {
            hash = hashFunction.apply(image);
        }
        long elapsedNanos = System.nanoTime() - start;

        printTimings(elapsedNanos, numHashes);

        System.out.println();
        System.out.println(name + " hash: " + hash);
    }

    /**
     * Benchmarks PdqHasher.getAllDihedralHashes(image), which computes the
     * hash for all 8 dihedral variants (4 rotations x optional mirror) in
     * a single call. This is NOT directly comparable to the single-hash
     * pdqHash benchmark above — it is doing roughly 8x the hash-derivation
     * work per call, by design (the 7 extra variants come "for free" from
     * reusing the same Jarosz-filter-plus-DCT pass, but the final
     * median-threshold-and-pack step is still repeated 8 times).
     */
    private static void runDihedralBenchmark(BufferedImage image, int numHashes) {
        int warmup = Math.min(MAX_WARMUP, numHashes);

        System.out.println("--- pdqHash (8 dihedral variants) ---");
        System.out.println("(computes all 8 rotation/mirror variants per call -- not a 1:1");
        System.out.println(" comparison with the single-hash pdqHash benchmark above)");

        // Warmup: let the JIT compile the hot path before timing.
        for (int i = 0; i < warmup; i++) {
            PdqHasher.getAllDihedralHashes(image);
        }

        long start = System.nanoTime();
        String[] hashes = null;
        for (int i = 0; i < numHashes; i++) {
            hashes = PdqHasher.getAllDihedralHashes(image);
        }
        long elapsedNanos = System.nanoTime() - start;

        printTimings(elapsedNanos, numHashes);

        System.out.println();
        for (int i = 0; i < hashes.length; i++) {
            System.out.println(PdqHasher.DIHEDRAL_NAMES[i] + ": " + hashes[i]);
        }
    }

    /** Shared timing output format for all benchmarks. */
    private static void printTimings(long elapsedNanos, int numHashes) {
        double elapsedMs = elapsedNanos / 1_000_000.0;
        double msPerImage = elapsedMs / numHashes;
        double imagesPerSec = numHashes / (elapsedMs / 1000.0);

        System.out.printf("Total time:      %.1f ms%n", elapsedMs);
        System.out.printf("Time per image:  %.4f ms%n", msPerImage);
        System.out.printf("Throughput:      %.1f images/sec%n", imagesPerSec);
    }

   
}