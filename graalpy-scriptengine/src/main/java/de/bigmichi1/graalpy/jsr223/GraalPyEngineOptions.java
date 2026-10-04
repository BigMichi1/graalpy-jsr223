package de.bigmichi1.graalpy.jsr223;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Predicate;

import org.graalvm.polyglot.HostAccess;

/**
 * Immutable configuration of a {@link GraalPyScriptEngineFactory}.
 *
 * <p>Instances are created with {@link #builder()} or read from system properties with
 * {@link #fromSystemProperties()}. The latter is what the factory uses when it is discovered
 * through {@link java.util.ServiceLoader}, e.g. by {@code javax.script.ScriptEngineManager} or a
 * process engine such as CIB seven.
 *
 * <table>
 *   <caption>Supported system properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code graalpy.jsr223.hostAccess}</td><td>{@code all}</td>
 *       <td>{@code all}, {@code constrained}, {@code explicit} or {@code none} — which Java members
 *       scripts may use on objects passed in through bindings.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.hostClassLookup}</td><td>{@code *}</td>
 *       <td>Classes scripts may load with {@code java.type(...)}: {@code *} for all, empty for
 *       none, otherwise a comma separated list of class name prefixes.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.allowIO}</td><td>{@code false}</td>
 *       <td>Grant scripts access to the host file system.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.allowCreateThread}</td><td>{@code false}</td>
 *       <td>Allow scripts to start threads.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.allowNativeAccess}</td><td>{@code false}</td>
 *       <td>Allow native extensions / ctypes.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.maxIdleContexts}</td><td>number of CPUs</td>
 *       <td>Contexts kept warm in the pool between evaluations.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.maxEvaluationsPerContext}</td><td>{@code 0}</td>
 *       <td>Recycle a context after this many evaluations ({@code 0} = never).</td></tr>
 *   <tr><td>{@code graalpy.jsr223.compilationCacheSize}</td><td>{@code 256}</td>
 *       <td>Compiled scripts cached per context ({@code 0} disables caching).</td></tr>
 *   <tr><td>{@code graalpy.jsr223.writeBack}</td><td>{@code true}</td>
 *       <td>Store top-level variables a script assigns into the engine scope bindings.</td></tr>
 *   <tr><td>{@code graalpy.jsr223.option.<name>}</td><td></td>
 *       <td>Passed as polyglot option {@code <name>} to every context, e.g.
 *       {@code graalpy.jsr223.option.python.PythonPath}.</td></tr>
 * </table>
 */
public final class GraalPyEngineOptions {

    static final String PREFIX = "graalpy.jsr223.";
    static final String OPTION_PREFIX = PREFIX + "option.";

    /** Which Java members are visible to scripts. */
    public enum HostAccessPolicy {
        /** Full access to public members, arrays, lists and maps (same as Jython). */
        ALL(HostAccess.ALL),
        /** GraalVM's {@link HostAccess#CONSTRAINED} policy. */
        CONSTRAINED(HostAccess.CONSTRAINED),
        /** Only members annotated with {@link HostAccess.Export}. */
        EXPLICIT(HostAccess.EXPLICIT),
        /** No access to Java members at all. */
        NONE(HostAccess.NONE);

        private final HostAccess hostAccess;

        HostAccessPolicy(HostAccess hostAccess) {
            this.hostAccess = hostAccess;
        }

        HostAccess hostAccess() {
            return hostAccess;
        }
    }

    private final HostAccessPolicy hostAccess;
    private final Predicate<String> hostClassFilter;
    private final boolean allowIO;
    private final boolean allowCreateThread;
    private final boolean allowNativeAccess;
    private final int maxIdleContexts;
    private final int maxEvaluationsPerContext;
    private final int compilationCacheSize;
    private final boolean writeBack;
    private final Map<String, String> polyglotOptions;

    private GraalPyEngineOptions(Builder builder) {
        this.hostAccess = builder.hostAccess;
        this.hostClassFilter = builder.hostClassFilter;
        this.allowIO = builder.allowIO;
        this.allowCreateThread = builder.allowCreateThread;
        this.allowNativeAccess = builder.allowNativeAccess;
        this.maxIdleContexts = builder.maxIdleContexts;
        this.maxEvaluationsPerContext = builder.maxEvaluationsPerContext;
        this.compilationCacheSize = builder.compilationCacheSize;
        this.writeBack = builder.writeBack;
        this.polyglotOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.polyglotOptions));
    }

    /** Returns a builder initialised with the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the defaults, overridden by {@code graalpy.jsr223.*} system properties. */
    public static GraalPyEngineOptions fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    /** Returns the defaults, overridden by {@code graalpy.jsr223.*} entries of {@code properties}. */
    public static GraalPyEngineOptions fromProperties(Properties properties) {
        Builder builder = builder();
        String value;
        if ((value = properties.getProperty(PREFIX + "hostAccess")) != null) {
            builder.hostAccess(HostAccessPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
        if ((value = properties.getProperty(PREFIX + "hostClassLookup")) != null) {
            builder.hostClassLookup(value);
        }
        if ((value = properties.getProperty(PREFIX + "allowIO")) != null) {
            builder.allowIO(Boolean.parseBoolean(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "allowCreateThread")) != null) {
            builder.allowCreateThread(Boolean.parseBoolean(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "allowNativeAccess")) != null) {
            builder.allowNativeAccess(Boolean.parseBoolean(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "maxIdleContexts")) != null) {
            builder.maxIdleContexts(Integer.parseInt(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "maxEvaluationsPerContext")) != null) {
            builder.maxEvaluationsPerContext(Integer.parseInt(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "compilationCacheSize")) != null) {
            builder.compilationCacheSize(Integer.parseInt(value.trim()));
        }
        if ((value = properties.getProperty(PREFIX + "writeBack")) != null) {
            builder.writeBack(Boolean.parseBoolean(value.trim()));
        }
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(OPTION_PREFIX)) {
                builder.polyglotOption(name.substring(OPTION_PREFIX.length()), properties.getProperty(name));
            }
        }
        return builder.build();
    }

    public HostAccessPolicy hostAccess() {
        return hostAccess;
    }

    public Predicate<String> hostClassFilter() {
        return hostClassFilter;
    }

    public boolean allowIO() {
        return allowIO;
    }

    public boolean allowCreateThread() {
        return allowCreateThread;
    }

    public boolean allowNativeAccess() {
        return allowNativeAccess;
    }

    public int maxIdleContexts() {
        return maxIdleContexts;
    }

    public int maxEvaluationsPerContext() {
        return maxEvaluationsPerContext;
    }

    public int compilationCacheSize() {
        return compilationCacheSize;
    }

    public boolean writeBack() {
        return writeBack;
    }

    public Map<String, String> polyglotOptions() {
        return polyglotOptions;
    }

    /** Builder for {@link GraalPyEngineOptions}. */
    public static final class Builder {
        private HostAccessPolicy hostAccess = HostAccessPolicy.ALL;
        private Predicate<String> hostClassFilter = className -> true;
        private boolean allowIO;
        private boolean allowCreateThread;
        private boolean allowNativeAccess;
        private int maxIdleContexts = Runtime.getRuntime().availableProcessors();
        private int maxEvaluationsPerContext;
        private int compilationCacheSize = 256;
        private boolean writeBack = true;
        private final Map<String, String> polyglotOptions = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder hostAccess(HostAccessPolicy hostAccess) {
            this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
            return this;
        }

        /** Restricts {@code java.type(...)} to classes accepted by {@code filter}. */
        public Builder hostClassFilter(Predicate<String> filter) {
            this.hostClassFilter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Configures class lookup from a textual spec: {@code *} allows every class, an empty
         * string none, anything else is a comma separated list of class name prefixes.
         */
        public Builder hostClassLookup(String spec) {
            String trimmed = spec.trim();
            if (trimmed.equals("*")) {
                return hostClassFilter(className -> true);
            }
            if (trimmed.isEmpty()) {
                return hostClassFilter(className -> false);
            }
            List<String> prefixes = Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(prefix -> !prefix.isEmpty())
                    .toList();
            return hostClassFilter(className -> prefixes.stream().anyMatch(className::startsWith));
        }

        public Builder allowIO(boolean allowIO) {
            this.allowIO = allowIO;
            return this;
        }

        public Builder allowCreateThread(boolean allowCreateThread) {
            this.allowCreateThread = allowCreateThread;
            return this;
        }

        public Builder allowNativeAccess(boolean allowNativeAccess) {
            this.allowNativeAccess = allowNativeAccess;
            return this;
        }

        public Builder maxIdleContexts(int maxIdleContexts) {
            if (maxIdleContexts < 0) {
                throw new IllegalArgumentException("maxIdleContexts must be >= 0");
            }
            this.maxIdleContexts = maxIdleContexts;
            return this;
        }

        public Builder maxEvaluationsPerContext(int maxEvaluationsPerContext) {
            if (maxEvaluationsPerContext < 0) {
                throw new IllegalArgumentException("maxEvaluationsPerContext must be >= 0");
            }
            this.maxEvaluationsPerContext = maxEvaluationsPerContext;
            return this;
        }

        public Builder compilationCacheSize(int compilationCacheSize) {
            if (compilationCacheSize < 0) {
                throw new IllegalArgumentException("compilationCacheSize must be >= 0");
            }
            this.compilationCacheSize = compilationCacheSize;
            return this;
        }

        public Builder writeBack(boolean writeBack) {
            this.writeBack = writeBack;
            return this;
        }

        /** Adds a raw polyglot context option such as {@code python.PythonPath}. */
        public Builder polyglotOption(String name, String value) {
            polyglotOptions.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public GraalPyEngineOptions build() {
            return new GraalPyEngineOptions(this);
        }
    }
}
