package de.bigmichi1.graalpy.jsr223;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Value;

/**
 * Converts Python values leaving the interpreter into plain Java objects.
 *
 * <p>Values returned from {@code eval} and variables written back to the bindings are stored by
 * callers such as a process engine, possibly serialized, and read again on other threads. Plain
 * Java objects are safe for all of that; polyglot {@link Value}s are not. Anything without a
 * natural Java counterpart (functions, instances of Python classes, ...) is returned as
 * {@link Value}.
 *
 * <table>
 *   <caption>Mapping</caption>
 *   <tr><th>Python</th><th>Java</th></tr>
 *   <tr><td>{@code None}</td><td>{@code null}</td></tr>
 *   <tr><td>{@code bool}</td><td>{@link Boolean}</td></tr>
 *   <tr><td>{@code int}</td><td>{@link Integer}, {@link Long} or {@link BigInteger}, the
 *       narrowest that fits</td></tr>
 *   <tr><td>{@code float}</td><td>{@link Double}</td></tr>
 *   <tr><td>{@code str}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code bytes}, {@code bytearray}</td><td>{@code byte[]}</td></tr>
 *   <tr><td>{@code list}, {@code tuple}</td><td>{@link ArrayList}</td></tr>
 *   <tr><td>{@code dict}</td><td>{@link LinkedHashMap}</td></tr>
 *   <tr><td>{@code set}, {@code frozenset}</td><td>{@link LinkedHashSet}</td></tr>
 *   <tr><td>{@code datetime.date}/{@code time}/{@code datetime}</td><td>{@code LocalDate},
 *       {@code LocalTime}, {@code LocalDateTime} or {@code ZonedDateTime}</td></tr>
 *   <tr><td>{@code datetime.timedelta}</td><td>{@code Duration}</td></tr>
 *   <tr><td>Java object</td><td>the original Java object</td></tr>
 * </table>
 */
final class ValueConverter {

    /** Guards against self-referencing containers. */
    private static final int MAX_DEPTH = 64;

    /**
     * Upper bound of values visited by one conversion. A list holding itself twice would otherwise
     * take 2^64 steps before {@link #MAX_DEPTH} stops it.
     */
    private static final int MAX_NODES = 1_000_000;

    private ValueConverter() {}

    static Object toJava(final Value value) {
        return toJava(value, 0, new int[] { MAX_NODES });
    }

    /**
     * Whether a converted value still holds a polyglot {@link Value}, at the top or nested in a
     * container. Such a value belongs to the pooled context that produced it and must not be stored
     * by the caller.
     */
    static boolean containsGuestValue(final Object converted) {
        if (converted instanceof Value) {
            return true;
        }
        if (converted instanceof final Map<?, ?> map) {
            return map
                .entrySet()
                .stream()
                .anyMatch(entry -> containsGuestValue(entry.getKey()) || containsGuestValue(entry.getValue()));
        }
        if (converted instanceof final Iterable<?> iterable) {
            for (final Object element : iterable) {
                if (containsGuestValue(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object toJava(final Value value, final int depth, final int[] budget) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isHostObject()) {
            try {
                return value.asHostObject();
            } catch (final UnsupportedOperationException e) {
                // Host members such as bound methods have no Java object representation.
                return value;
            }
        }
        if (value.isProxyObject()) {
            return value.asProxyObject();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return toNumber(value);
        }
        if (value.isDate() || value.isTime()) {
            return toTemporal(value);
        }
        if (value.isDuration()) {
            return value.asDuration();
        }
        if (depth >= MAX_DEPTH || --budget[0] < 0) {
            return value;
        }
        if (value.hasBufferElements()) {
            final byte[] bytes = new byte[Math.toIntExact(value.getBufferSize())];
            value.readBuffer(0, bytes, 0, bytes.length);
            return bytes;
        }
        if (value.hasHashEntries()) {
            final Map<Object, Object> map = new LinkedHashMap<>();
            final Value iterator = value.getHashEntriesIterator();
            while (iterator.hasIteratorNextElement()) {
                final Value entry = iterator.getIteratorNextElement();
                map.put(toJava(entry.getArrayElement(0), depth + 1, budget), toJava(entry.getArrayElement(1), depth + 1, budget));
            }
            return map;
        }
        if (value.hasArrayElements()) {
            final long size = value.getArraySize();
            final List<Object> list = new ArrayList<>((int) Math.min(size, Integer.MAX_VALUE));
            for (long i = 0; i < size; i++) {
                list.add(toJava(value.getArrayElement(i), depth + 1, budget));
            }
            return list;
        }
        if (value.hasIterator() && isSet(value)) {
            final Set<Object> set = new LinkedHashSet<>();
            final Value iterator = value.getIterator();
            while (iterator.hasIteratorNextElement()) {
                set.add(toJava(iterator.getIteratorNextElement(), depth + 1, budget));
            }
            return set;
        }
        return value;
    }

    private static Object toNumber(final Value value) {
        // Python floats with integral values (2.0) also "fit" into int; keep them floating point.
        if (!isFloat(value)) {
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            if (value.fitsInBigInteger()) {
                return value.as(BigInteger.class);
            }
        }
        return value.asDouble();
    }

    private static boolean isFloat(final Value value) {
        final Value meta = value.getMetaObject();
        return meta != null && "float".equals(meta.getMetaSimpleName());
    }

    private static Object toTemporal(final Value value) {
        if (value.isDate() && value.isTime()) {
            final LocalDateTime dateTime = LocalDateTime.of(value.asDate(), value.asTime());
            return value.isTimeZone() ? ZonedDateTime.of(dateTime, value.asTimeZone()) : dateTime;
        }
        return value.isDate() ? value.asDate() : value.asTime();
    }

    private static boolean isSet(final Value value) {
        final Value meta = value.getMetaObject();
        return meta != null && ("set".equals(meta.getMetaSimpleName()) || "frozenset".equals(meta.getMetaSimpleName()));
    }
}
