package de.bigmichi1.graalpy.jsr223;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads the Python sources this module ships next to its classes. Not part of the engine's API. */
public final class ClasspathResources {

    private ClasspathResources() {}

    /**
     * Reads a UTF-8 resource relative to {@code anchor}.
     *
     * @throws IllegalStateException if the resource is missing, which means a broken jar
     */
    public static String readUtf8(final Class<?> anchor, final String name) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + name + " next to " + anchor.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
