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
