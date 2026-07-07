package guru.kumo.operator.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class ImageReaderTool {
    public static final String TOOL_NAME = "ImageReader";

    public record ImageResult(String mimeType, String base64, int width, int height) {
    }

    // @formatter:off
    @Tool(name = TOOL_NAME, description = """
            Reads an image or screenshot file from the local filesystem.
            
            Usage:
            - The file_path parameter must be an absolute path, not a relative path
            - This tool allows to read images and screenshots (eg PNG, JPG, etc).
            """)
    // @formatter:on
    public String readImage(@ToolParam(description = "The absolute path to the file to read") String filePath) {
        if (!StringUtils.hasLength(filePath)) return "";
        return filePath;
    }

    public static ImageReaderTool.Builder builder() {
        return new ImageReaderTool.Builder();
    }

    public static class Builder {
        private Builder() {
        }

        public ImageReaderTool build() {
            return new ImageReaderTool();
        }
    }
/*
    // @formatter:off
    @Tool(name = TOOL_NAME, description = """
            Reads an image or screenshot file from the local filesystem.

            Usage:
            - The file_path parameter must be an absolute path, not a relative path
            - This tool allows to read images and screenshots (eg PNG, JPG, etc).
            """)
    // @formatter:on
    public ImageResult readImage(@ToolParam(description = "The absolute path to the file to read") String filePath) {
        if (!StringUtils.hasLength(filePath)) throw new RuntimeException("File path is empty!");
        FileSystemResource resource = new FileSystemResource(filePath);

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(resource.getContentAsByteArray()));
            if (original == null) {
                throw new IllegalArgumentException("Invalid or unsupported image data provided.");
            }
            BufferedImage resized = resizeImage(original, 800);
            byte[] jpeg = toJpegBytes(resized, 0.8f);
            return new ImageResult("image/jpeg",
                    Base64.getEncoder().encodeToString(jpeg),
                    resized.getWidth(), resized.getHeight());
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private BufferedImage resizeImage(BufferedImage src, int maxDimension) {
        int w = src.getWidth(), h = src.getHeight();
        if (w > maxDimension || h > maxDimension) {
            if (w > h) { h = h * maxDimension / w; w = maxDimension; }
            else       { w = w * maxDimension / h; h = maxDimension; }
        }

        // Opaque RGB so the JPEG writer is happy
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);                 // background for transparent pixels
        g.fillRect(0, 0, w, h);

        if (w == src.getWidth() && h == src.getHeight()) {
            g.drawImage(src, 0, 0, null);        // no scaling needed
        } else {
            Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            g.drawImage(scaled, 0, 0, null);     // drawImage waits for the scaled image to load
        }
        g.dispose();
        return out;
    }

    private byte[] toJpegBytes(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
 */
}
