package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

class MapImagesTest {
    static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** Two-frame GIF (red, blue) with 200 ms delays, written with the JDK's GIF writer. */
    static byte[] gif() throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (Color c : new Color[] { Color.RED, Color.BLUE }) {
                BufferedImage frame = solid(40, 20, c);
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", "20");
                control.setAttribute("transparentColorIndex", "0");
                root.appendChild(control);
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame, null, meta), null);
            }
            writer.endWriteSequence();
        }
        return bytes.toByteArray();
    }

    @Test
    void givenPalette_whenLoaded_thenItHasTheMapColours() {
        int usable = 0;
        for (int rgb : MapImages.palette()) if (rgb >= 0) usable++;
        assertTrue(usable > 200, "map colours: " + usable);
    }

    @Test
    void givenRedImage_whenConverted_thenEveryPixelIsAReddishMapColourAndTheBorderTransparent() {
        byte[] map = MapImages.toMap(solid(10, 10, Color.RED), 40, 20, true);
        assertEquals(40 * 20, map.length);
        assertEquals(0, map[0], "outside the image (letterbox) stays transparent");
        int rgb = MapImages.palette()[map[10 * 40 + 20] & 0xFF];   // centre of the image
        assertTrue((rgb >> 16 & 0xFF) > 150 && (rgb & 0xFF) < 100, Integer.toHexString(rgb));
    }

    @Test
    void givenAnimatedGif_whenDecoded_thenAllFramesWithTheirDelays() throws Exception {
        List<Integer> delays = new ArrayList<>();
        List<BufferedImage> frames = MapImages.gifFrames(new ByteArrayInputStream(gif()), 10, delays);
        assertEquals(2, frames.size());
        assertEquals(List.of(200, 200), delays);
        assertTrue((frames.get(1).getRGB(5, 5) & 0xFF) > 200, "second frame is blue");
    }

    @Test
    void givenImageSizes_whenChoosingAGrid_thenItFollowsTheAspectRatio() {
        assertEquals(new Vystorm.vystorm_core.screen.ScreenWidget.MapGrid(4, 2), ScreenGifDemo.grid(480, 120));
        assertEquals(2, ScreenGifDemo.grid(100, 100).columns());
        assertEquals(3, ScreenGifDemo.grid(320, 240).rows());
    }
}
