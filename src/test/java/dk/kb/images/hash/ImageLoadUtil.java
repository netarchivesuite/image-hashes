package dk.kb.images.hash;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class ImageLoadUtil {
    
    /** Classpath-relative location: src/test/resources/ */
    private static final String IMAGE_DIR = "test_images/";
    
    public static BufferedImage loadImage(String resourcePath) {
        try (InputStream in = PhashHasherCatImageSetTest.class
                .getClassLoader()
                .getResourceAsStream(IMAGE_DIR + resourcePath)) {
            if (in == null) return null;
            return javax.imageio.ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

}
