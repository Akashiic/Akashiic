package br.com.atmbrasil.lobby.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only reflection adapter for the final header/footer state tracked by TAB.
 *
 * <p>The integration deliberately reflects only TAB's public API and keeps no hard link to TAB,
 * so the rest of ProtocolObelisk remains loadable when TAB is absent.</p>
 */
final class TabStateAccessor {
    private static final String TAB_API = "me.neznamy.tab.api.TabAPI";

    private final Method getInstance;
    private final Method getPlayer;
    private final ConcurrentHashMap<Class<?>, PlayerMethods> playerMethods =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, TabListMethods> tabListMethods =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Method> componentConverters =
            new ConcurrentHashMap<>();

    private TabStateAccessor(Method getInstance, Method getPlayer) {
        this.getInstance = getInstance;
        this.getPlayer = getPlayer;
    }

    static TabStateAccessor resolve(ClassLoader tabClassLoader) throws ReflectiveOperationException {
        Objects.requireNonNull(tabClassLoader, "tabClassLoader");
        Class<?> apiClass = Class.forName(TAB_API, true, tabClassLoader);
        Method instanceMethod = publicMethod(apiClass, "getInstance");
        Method playerMethod = publicMethod(apiClass, "getPlayer", UUID.class);
        requireParameterCount(instanceMethod, 0, "TabAPI#getInstance");
        requireParameterCount(playerMethod, 1, "TabAPI#getPlayer");
        return new TabStateAccessor(instanceMethod, playerMethod);
    }

    Optional<State> read(UUID playerId) throws AccessException {
        Objects.requireNonNull(playerId, "playerId");
        try {
            Object api = getInstance.invoke(null);
            if (api == null) {
                throw new AccessException("TAB returned a null API instance");
            }
            Object tabPlayer = getPlayer.invoke(api, playerId);
            if (tabPlayer == null) {
                return Optional.empty();
            }
            PlayerMethods playerAccess = playerMethodsFor(tabPlayer.getClass());
            if (!Boolean.TRUE.equals(playerAccess.isLoaded().invoke(tabPlayer))) {
                return Optional.empty();
            }
            Object tabList = playerAccess.getTabList().invoke(tabPlayer);
            if (tabList == null) {
                throw new AccessException("TAB returned a null tab-list view");
            }
            TabListMethods tabListAccess = tabListMethodsFor(tabList.getClass());
            Object header = tabListAccess.getHeader().invoke(tabList);
            Object footer = tabListAccess.getFooter().invoke(tabList);
            return Optional.of(new State(toLegacy(header), toLegacy(footer)));
        } catch (IllegalAccessException exception) {
            throw new AccessException("TAB tracked state is not accessible", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new AccessException(
                    "TAB tracked-state invocation failed: "
                            + cause.getClass().getSimpleName()
                            + safeSuffix(cause.getMessage()),
                    cause);
        } catch (ReflectionResolutionException exception) {
            throw new AccessException(exception.getMessage(), exception.getCause());
        }
    }

    private PlayerMethods playerMethodsFor(Class<?> type) {
        return playerMethods.computeIfAbsent(type, ignored -> {
            try {
                Method isLoaded = publicMethod(type, "isLoaded");
                Method getTabList = publicMethod(type, "getTabList");
                requireParameterCount(isLoaded, 0, "TAB player isLoaded");
                requireParameterCount(getTabList, 0, "TAB player getTabList");
                return new PlayerMethods(isLoaded, getTabList);
            } catch (ReflectiveOperationException exception) {
                throw new ReflectionResolutionException(
                        "TAB player implementation has an unsupported tracked-state API",
                        exception);
            }
        });
    }

    private TabListMethods tabListMethodsFor(Class<?> type) {
        return tabListMethods.computeIfAbsent(type, ignored -> {
            try {
                Method getHeader = publicMethod(type, "getHeader");
                Method getFooter = publicMethod(type, "getFooter");
                requireParameterCount(getHeader, 0, "TAB tab-list getHeader");
                requireParameterCount(getFooter, 0, "TAB tab-list getFooter");
                return new TabListMethods(getHeader, getFooter);
            } catch (ReflectiveOperationException exception) {
                throw new ReflectionResolutionException(
                        "TAB tab-list implementation has an unsupported tracked-state API",
                        exception);
            }
        });
    }

    private String toLegacy(Object component)
            throws IllegalAccessException, InvocationTargetException, AccessException {
        if (component == null) {
            return "";
        }
        final Method converter;
        try {
            converter = componentConverters.computeIfAbsent(component.getClass(), ignored -> {
                try {
                    Method resolved = publicMethod(component.getClass(), "toLegacyText");
                    requireParameterCount(resolved, 0, "TAB component toLegacyText");
                    return resolved;
                } catch (ReflectiveOperationException exception) {
                    throw new ReflectionResolutionException(
                            "TAB component implementation has no compatible legacy serializer",
                            exception);
                }
            });
        } catch (ReflectionResolutionException exception) {
            throw new AccessException(exception.getMessage(), exception.getCause());
        }
        Object converted = converter.invoke(component);
        if (!(converted instanceof String legacyText)) {
            throw new AccessException("TAB component did not return legacy text");
        }
        return legacyText;
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        method.trySetAccessible();
        return method;
    }

    private static void requireParameterCount(Method method, int expected, String description)
            throws NoSuchMethodException {
        if (method.getParameterCount() != expected) {
            throw new NoSuchMethodException(description + " has an unexpected signature");
        }
    }

    private static String safeSuffix(String message) {
        return message == null ? "" : ": " + message;
    }

    record State(String header, String footer) {
        State {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(footer, "footer");
        }
    }

    private record PlayerMethods(Method isLoaded, Method getTabList) {
    }

    private record TabListMethods(Method getHeader, Method getFooter) {
    }

    static final class AccessException extends Exception {
        private static final long serialVersionUID = 1L;

        AccessException(String message) {
            super(message);
        }

        AccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ReflectionResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReflectionResolutionException(String message, ReflectiveOperationException cause) {
            super(message, cause);
        }

        @Override
        public synchronized ReflectiveOperationException getCause() {
            return (ReflectiveOperationException) super.getCause();
        }
    }
}
