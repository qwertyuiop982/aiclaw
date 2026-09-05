package com.operit.aiclaw.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Common filesystem safety policy. No whitelist is imposed; this only blocks path escapes and system roots. */
final class PathPolicy {
    private PathPolicy() {}

    static Path normalize(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }

    static void rejectDangerous(Path path) {
        String p = path.toString();
        for (String guarded : new String[]{"/etc", "/sys", "/proc", "/boot", "/dev"}) {
            if (p.equals("/")) throw new ToolException("refusing dangerous system path: /");
            if (p.equals(guarded) || p.startsWith(guarded + "/")) {
                throw new ToolException("refusing dangerous system path: " + path);
            }
        }
    }

    static void rejectDirectoryIntoChild(Path src, Path dst) {
        if (Files.isDirectory(src) && (dst.equals(src) || dst.startsWith(src))) {
            throw new ToolException("destination must not be the source directory or its child");
        }
    }

    static boolean isSymlink(Path path) {
        return Files.isSymbolicLink(path);
    }

    static Path realParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        return parent == null ? null : parent.toRealPath();
    }
}