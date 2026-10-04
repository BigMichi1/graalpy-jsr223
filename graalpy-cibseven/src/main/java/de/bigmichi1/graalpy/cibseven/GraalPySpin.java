package de.bigmichi1.graalpy.cibseven;

import org.cibseven.spin.DataFormats;
import org.cibseven.spin.Spin;
import org.cibseven.spin.spi.DataFormat;

/**
 * Spin's {@code S}, {@code JSON} and {@code XML} functions as Java objects Python can call.
 *
 * <p>The Spin environment script shipped for Jython imports the static method
 * {@code org.cibseven.spin.Spin.S}, which GraalPy cannot do. Bound host methods also cannot be handed
 * from the environment script to the actual script (see the engine documentation). Instances of a
 * functional interface are plain Java objects, so they survive the trip through the bindings and
 * are callable from Python like functions.
 */
public final class GraalPySpin {

    /** A callable taking any number of arguments. */
    @FunctionalInterface
    public interface SpinFunction {
        Object call(Object... arguments);
    }

    /** {@code S(input)}, {@code S(input, "application/json")} or {@code S(input, dataFormat)}. */
    public static final SpinFunction S = GraalPySpin::s;

    /** {@code JSON(input)} */
    public static final SpinFunction JSON = arguments -> Spin.JSON(single("JSON", arguments));

    /** {@code XML(input)} */
    public static final SpinFunction XML = arguments -> Spin.XML(single("XML", arguments));

    private GraalPySpin() {}

    private static Object s(final Object... arguments) {
        if (arguments.length == 1) {
            return Spin.S(arguments[0]);
        }
        if (arguments.length == 2) {
            final Object format = arguments[1];
            if (format instanceof final String name) {
                return Spin.S(arguments[0], name);
            }
            if (format instanceof final DataFormat<?> dataFormat) {
                return Spin.S(arguments[0], dataFormat);
            }
            throw new IllegalArgumentException("S(): second argument must be a data format name or " + DataFormats.class.getSimpleName() + " instance, got " + format);
        }
        throw new IllegalArgumentException("S() takes 1 or 2 arguments, got " + arguments.length);
    }

    private static Object single(final String function, final Object... arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(function + "() takes exactly 1 argument, got " + arguments.length);
        }
        return arguments[0];
    }
}
