package org.slf4j;
public interface Logger {
    default void info(String msg) {}
    default void info(String format, Object arg) {}
    default void info(String format, Object arg1, Object arg2) {}
    default void info(String format, Object... arguments) {}
    default void debug(String msg) {}
    default void debug(String format, Object arg) {}
    default void debug(String format, Object... arguments) {}
    default void debug(String msg, Throwable throwable) {}
    default void warn(String msg) {}
    default void warn(String format, Object arg) {}
    default void warn(String format, Object arg1, Object arg2) {}
    default void warn(String format, Object... arguments) {}
    default void warn(String msg, Throwable throwable) {}
    default void error(String msg) {}
    default void error(String format, Object arg) {}
    default void error(String format, Object arg1, Object arg2) {}
    default void error(String format, Object... arguments) {}
    default void error(String msg, Throwable throwable) {}
}
