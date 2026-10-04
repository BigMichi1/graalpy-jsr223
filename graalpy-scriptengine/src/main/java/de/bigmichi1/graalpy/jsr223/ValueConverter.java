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

    private ValueConverter() {
    }

    static Object toJava(Value value) {
        return toJava(value, 0);
    }

    private static Object toJava(Value value, int depth) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isHostObject()) {
            try {
                return value.asHostObject();
            } catch (UnsupportedOperationException e) {
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
        if (depth >= MAX_DEPTH) {
            return value;
        }
        if (value.hasBufferElements()) {
            byte[] bytes = new byte[Math.toIntExact(value.getBufferSize())];
            value.readBuffer(0, bytes, 0, bytes.length);
            return bytes;
        }
        if (value.hasHashEntries()) {
            Map<Object, Object> map = new LinkedHashMap<>();
            Value iterator = value.getHashEntriesIterator();
            while (iterator.hasIteratorNextElement()) {
                Value entry = iterator.getIteratorNextElement();
                map.put(toJava(entry.getArrayElement(0), depth + 1), toJava(entry.getArrayElement(1), depth + 1));
            }
            return map;
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            List<Object> list = new ArrayList<>((int) Math.min(size, Integer.MAX_VALUE));
            for (long i = 0; i < size; i++) {
                list.add(toJava(value.getArrayElement(i), depth + 1));
            }
            return list;
        }
        if (value.hasIterator() && isSet(value)) {
            Set<Object> set = new LinkedHashSet<>();
            Value iterator = value.getIterator();
            while (iterator.hasIteratorNextElement()) {
                set.add(toJava(iterator.getIteratorNextElement(), depth + 1));
            }
            return set;
        }
        return value;
    }

    private static Object toNumber(Value value) {
        // Python floats with integral values (2.0) also "fit" into int; keep them floating point.
        if (isFloat(value)) {
            return value.asDouble();
        }
        if (value.fitsInInt()) {
            return value.asInt();
        }
        if (value.fitsInLong()) {
            return value.asLong();
        }
        if (value.fitsInBigInteger()) {
            return value.as(BigInteger.class);
        }
        return value.asDouble();
    }

    private static boolean isFloat(Value value) {
        Value meta = value.getMetaObject();
        return meta != null && "float".equals(meta.getMetaSimpleName());
    }

    private static Object toTemporal(Value value) {
        if (value.isDate() && value.isTime()) {
            LocalDateTime dateTime = LocalDateTime.of(value.asDate(), value.asTime());
            return value.isTimeZone() ? ZonedDateTime.of(dateTime, value.asTimeZone()) : dateTime;
        }
        return value.isDate() ? value.asDate() : value.asTime();
    }

    private static boolean isSet(Value value) {
        Value meta = value.getMetaObject();
        if (meta == null) {
            return false;
        }
        String name = meta.getMetaSimpleName();
        return "set".equals(name) || "frozenset".equals(name);
    }
}
