/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import com.hypixel.hytale.logger.HytaleLogger;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SLF4J 2.0 service provider that forwards core log calls to
 * {@link HytaleLogger}.
 *
 * <p>The service file
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} names this
 * class. SLF4J loads it from the class loader that defined
 * {@code org.slf4j.LoggerFactory}. Discovery inside a running Hytale plugin
 * class loader is not proven here.
 *
 * <p>SLF4J {@code {}} messages are formatted first. The result is passed to
 * flogger as the argument of {@code log("%s", text)} so {@code %} characters
 * in the text stay literal. An SLF4J marker is prefixed as {@code [name] }.
 * A throwable is attached with {@code withCause}. The original message is
 * never written again if the bridge itself fails, because core logs can
 * carry paths and exception text.
 *
 * <p>Level mapping: ERROR {@link java.util.logging.Level#SEVERE}, WARN
 * {@link java.util.logging.Level#WARNING}, INFO
 * {@link java.util.logging.Level#INFO}, DEBUG
 * {@link java.util.logging.Level#FINE}, TRACE
 * {@link java.util.logging.Level#FINEST}.
 */
public final class HytaleSlf4jBridge implements SLF4JServiceProvider, ILoggerFactory {

    /** Matches the 2.0 line advertised by {@code API_COMPATIBILITY_LIST}. */
    static final String REQUESTED_API_VERSION = "2.0.99";

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;
    private boolean initialized;

    /** Required by {@link java.util.ServiceLoader}. */
    public HytaleSlf4jBridge() {
        // initialize() does the real setup so construction cannot recurse
        // through LoggerFactory.
    }

    @Override
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new HytaleMdcAdapter();
        initialized = true;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        initialize();
        return this;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        initialize();
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        initialize();
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public Logger getLogger(String name) {
        String safeName = name == null ? "" : name;
        return loggers.computeIfAbsent(safeName, HytaleSlf4jLogger::new);
    }

    /**
     * One SLF4J logger bound to {@link HytaleLogger#get(String)}.
     */
    static final class HytaleSlf4jLogger extends AbstractLogger {

        private final String loggerName;
        private volatile HytaleLogger backend;

        HytaleSlf4jLogger(String name) {
            this.loggerName = name;
            this.name = name;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return HytaleSlf4jLogger.class.getName();
        }

        @Override
        public boolean isTraceEnabled() {
            return enabled(java.util.logging.Level.FINEST);
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return isTraceEnabled();
        }

        @Override
        public boolean isDebugEnabled() {
            return enabled(java.util.logging.Level.FINE);
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return isDebugEnabled();
        }

        @Override
        public boolean isInfoEnabled() {
            return enabled(java.util.logging.Level.INFO);
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return isInfoEnabled();
        }

        @Override
        public boolean isWarnEnabled() {
            return enabled(java.util.logging.Level.WARNING);
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return isWarnEnabled();
        }

        @Override
        public boolean isErrorEnabled() {
            return enabled(java.util.logging.Level.SEVERE);
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return isErrorEnabled();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String format,
                                                    Object[] arguments, Throwable throwable) {
            try {
                Object[] args = arguments == null ? new Object[0] : arguments;
                String rendered = format == null ? "" : MessageFormatter.basicArrayFormat(format, args);
                if (marker != null && marker.getName() != null) {
                    rendered = "[" + marker.getName() + "] " + rendered;
                }
                HytaleLogger.Api api = backend().at(toJul(level));
                if (throwable != null) {
                    api = api.withCause(throwable);
                }
                api.log("%s", rendered);
            } catch (Throwable ignored) {
                // Do not rethrow, and do not log `format` or `rendered`.
            }
        }

        private boolean enabled(java.util.logging.Level level) {
            try {
                return backend().at(level).isEnabled();
            } catch (Throwable ignored) {
                return false;
            }
        }

        private HytaleLogger backend() {
            HytaleLogger current = backend;
            if (current == null) {
                current = HytaleLogger.get(loggerName);
                backend = current;
            }
            return current;
        }

        private static java.util.logging.Level toJul(Level level) {
            if (level == null) {
                return java.util.logging.Level.INFO;
            }
            return switch (level) {
                case ERROR -> java.util.logging.Level.SEVERE;
                case WARN -> java.util.logging.Level.WARNING;
                case INFO -> java.util.logging.Level.INFO;
                case DEBUG -> java.util.logging.Level.FINE;
                case TRACE -> java.util.logging.Level.FINEST;
            };
        }
    }

    /** Thread-local MDC. Values are not logged by this class. */
    static final class HytaleMdcAdapter implements MDCAdapter {

        private final ThreadLocal<Map<String, String>> values = new ThreadLocal<>();
        private final ThreadLocal<Map<String, Deque<String>>> deques = new ThreadLocal<>();

        @Override
        public void put(String key, String val) {
            requireKey(key);
            map().put(key, val);
        }

        @Override
        public String get(String key) {
            Map<String, String> current = values.get();
            if (current == null || key == null) {
                return null;
            }
            return current.get(key);
        }

        @Override
        public void remove(String key) {
            Map<String, String> current = values.get();
            if (current == null || key == null) {
                return;
            }
            current.remove(key);
            if (current.isEmpty()) {
                values.remove();
            }
        }

        @Override
        public void clear() {
            values.remove();
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            Map<String, String> current = values.get();
            if (current == null || current.isEmpty()) {
                return null;
            }
            return new HashMap<>(current);
        }

        @Override
        public void setContextMap(Map<String, String> contextMap) {
            if (contextMap == null || contextMap.isEmpty()) {
                values.remove();
                return;
            }
            values.set(new HashMap<>(contextMap));
        }

        @Override
        public void pushByKey(String key, String value) {
            requireKey(key);
            dequeMap().computeIfAbsent(key, ignored -> new ArrayDeque<>()).push(value);
        }

        @Override
        public String popByKey(String key) {
            Map<String, Deque<String>> current = deques.get();
            if (current == null || key == null) {
                return null;
            }
            Deque<String> deque = current.get(key);
            if (deque == null || deque.isEmpty()) {
                return null;
            }
            String value = deque.pop();
            if (deque.isEmpty()) {
                current.remove(key);
            }
            if (current.isEmpty()) {
                deques.remove();
            }
            return value;
        }

        @Override
        public Deque<String> getCopyOfDequeByKey(String key) {
            Map<String, Deque<String>> current = deques.get();
            if (current == null || key == null) {
                return null;
            }
            Deque<String> deque = current.get(key);
            if (deque == null) {
                return null;
            }
            return new ArrayDeque<>(deque);
        }

        @Override
        public void clearDequeByKey(String key) {
            Map<String, Deque<String>> current = deques.get();
            if (current == null || key == null) {
                return;
            }
            current.remove(key);
            if (current.isEmpty()) {
                deques.remove();
            }
        }

        private Map<String, String> map() {
            Map<String, String> current = values.get();
            if (current == null) {
                current = new HashMap<>();
                values.set(current);
            }
            return current;
        }

        private Map<String, Deque<String>> dequeMap() {
            Map<String, Deque<String>> current = deques.get();
            if (current == null) {
                current = new HashMap<>();
                deques.set(current);
            }
            return current;
        }

        private static void requireKey(String key) {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
        }
    }
}
