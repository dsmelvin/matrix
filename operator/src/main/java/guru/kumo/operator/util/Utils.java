package guru.kumo.operator.util;

import java.io.File;

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
}
