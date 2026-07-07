package guru.kumo.operator.util;

import java.io.File;
import java.nio.file.Path;

public class Utils {
    public static boolean containsFile(File dir, String targetName) {
        // If the starting point itself is the target file, return true
        if (dir.isFile() && dir.getName().equalsIgnoreCase(targetName)) {
            return true;
        }

        if (dir.isDirectory()) {
            File[] children = dir.listFiles();

            // Ensure the directory is accessible and not empty
            if (children != null) {
                for (File child : children) {
                    // Recursively check each child. Return true immediately if found.
                    if (containsFile(child, targetName)) {
                        return true;
                    }
                }
            }
        }

        // Return false if the file was not found anywhere in this path
        return false;
    }

    public static String getAbsolutePath(String pathName) {
        Path filePath = Path.of("").toAbsolutePath().resolve(pathName);
        if (!filePath.toFile().isDirectory() && System.getenv("PWD") != null && !pathName.startsWith("/")) {
            return Path.of(System.getenv("PWD")).toAbsolutePath().resolve(pathName).toAbsolutePath().toString();
        } else {
            return pathName;
        }
    }

    public static String getAbsoluteFilePathName(String filePathName) {
        Path filePath = Path.of("").toAbsolutePath().resolve(filePathName);
        if (!filePath.toFile().isFile() && System.getenv("PWD") != null && !filePathName.startsWith("/")) {
            return Path.of(System.getenv("PWD")).toAbsolutePath().resolve(filePathName).toAbsolutePath().toString();
        } else {
            return filePathName;
        }
    }
}
