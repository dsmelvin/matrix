package guru.kumo.operator.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

public class ImageReaderTool {
    public static final String name = "ImageReader";

    // @formatter:off
    @Tool(name = name, description = """
            Reads an image or screenshot file from the local filesystem.
            
            Usage:
            - The file_path parameter must be an absolute path, not a relative path
            - This tool allows to read images and screenshots (eg PNG, JPG, etc).
            """)
    // @formatter:on
    public String readImage(@ToolParam(description = "The absolute path to the file to read") String filePath) {
        if (!StringUtils.hasLength(filePath)) return "";
        return filePath;
//        try {
//            Path imagePath = Path.of(filePath);
//            byte[] imageBytes = Files.readAllBytes(imagePath);
//            String base64String = Base64.getEncoder().encodeToString(imageBytes);
//            MimeType mimeType = MimeTypeUtils.parseMimeType(Files.probeContentType(imagePath));
//            return String.format("data:%s;base64,%s", mimeType, base64String);
//        } catch (IOException e) {
//            return e.getMessage();
//        }

//        UserMessage.Builder builder = UserMessage.builder().text(ImageReaderTool.name);
//        FileSystemResource resource = new FileSystemResource(filePath);
//        try {
//            MimeType mimeType = MimeTypeUtils.parseMimeType(Files.probeContentType(resource.getFilePath()));
//            if (!mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_JPEG) && !mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_PNG)) {
//                builder.text("Can't load images other than PNG or JPEG").build();
//            }
//            builder.media(new Media(mimeType, resource));
//            return  builder.build();
//        } catch (Exception e) {
//            return builder.text(e.getMessage()).build();
//        }
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
}
