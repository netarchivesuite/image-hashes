package dk.kb.images.hash;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * Manual performance benchmark for {@link PdqHasher} and {@link PhashHasher}.
 *
 * This is NOT a unit test — it is not annotated with @Test and will not be
 * picked up by Maven's test runner (surefire/failsafe). Run it directly
 * from the command line when you want to measure throughput, since
 * hashing hundreds or thousands of images takes too long to be part of
 * the normal automated test suite.
 *
 * Usage:
 *   java pdq.PerformanceBenchMark &lt;imageName&gt; &lt;numHashes&gt;
 *
 *   imageName  - path to an image file on disk
 *   numHashes  - number of times to compute the hash (e.g. 1000)
 *
 * Example:
 *   java pdq.PerformanceBenchMark /path/to/photo.jpg 1000
 *
 * The image is loaded once. Every registered hash type (see HASH_TYPES
 * below) is benchmarked in turn against that same image: a warmup phase
 * runs the hash a number of times first (so the JIT has compiled the hot
 * path before timing starts), then the timed run measures the requested
 * number of hashes and prints average time per image and images/sec.
 *
 * To add a new hash type later, add one entry to HASH_TYPES — no other
 * changes needed.
 */
public class PerformanceBenchMark {

    /**
     * Registered hash types, in the order they will be benchmarked.
     * Each entry is a (name, function) pair; the function computes one
     * hash of a BufferedImage. Add new hash implementations here.
     */
    private static final HashType[] HASH_TYPES = {
        new HashType("pdqHash", PdqHasher::getHash),
        new HashType("pHash", PhashHasher::getHash),
    };

    /** Warmup iterations before timing starts, capped at numHashes. */
    private static final int MAX_WARMUP = 50;

    public static void main(String[] args) {
        if (args.length != 2) {
            printUsageAndExit();
            return;
        }

        String imageName = args[0];
        int numHashes;

        try {
            numHashes = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("ERROR: numHashes must be an integer, got: " + args[1]);
            printUsageAndExit();
            return;
        }

        if (numHashes <= 0) {
            System.err.println("ERROR: numHashes must be a positive integer, got: " + numHashes);
            printUsageAndExit();
            return;
        }

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

        for (HashType hashType : HASH_TYPES) {
            runBenchmark(hashType, image, numHashes);
            System.out.println();
        }
    }

    private static void runBenchmark(HashType hashType, BufferedImage image, int numHashes) {
        int warmup = Math.min(MAX_WARMUP, numHashes);

        System.out.println("--- " + hashType.name + " ---");

        // Warmup: let the JIT compile the hot path before timing.
        for (int i = 0; i < warmup; i++) {
            hashType.hashFunction.apply(image);
        }

        long start = System.nanoTime();
        for (int i = 0; i < numHashes; i++) {
            hashType.hashFunction.apply(image);
        }
        long elapsedNanos = System.nanoTime() - start;

        double elapsedMs = elapsedNanos / 1_000_000.0;
        double msPerImage = elapsedMs / numHashes;
        double imagesPerSec = numHashes / (elapsedMs / 1000.0);

        System.out.printf("Total time:      %.1f ms%n", elapsedMs);
        System.out.printf("Time per image:  %.4f ms%n", msPerImage);
        System.out.printf("Throughput:      %.1f images/sec%n", imagesPerSec);
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: java pdq.PerformanceBenchMark <imageName> <numHashes>");
        System.err.println("  imageName  - path to an image file on disk");
        System.err.println("  numHashes  - number of times to compute the hash, e.g. 1000");
        System.err.println();
        System.err.println("Runs every registered hash type (currently: pdqHash, pHash)");
        System.err.println("against the given image and prints timing for each.");
        System.err.println();
        System.err.println("Example: java pdq.PerformanceBenchMark photo.jpg 1000");
        System.exit(1);
    }

    /** One registered hash type: a display name plus the function that computes it. */
    private static final class HashType {
        final String name;
        final Function<BufferedImage, String> hashFunction;

        HashType(String name, Function<BufferedImage, String> hashFunction) {
            this.name = name;
            this.hashFunction = hashFunction;
        }
    }
}