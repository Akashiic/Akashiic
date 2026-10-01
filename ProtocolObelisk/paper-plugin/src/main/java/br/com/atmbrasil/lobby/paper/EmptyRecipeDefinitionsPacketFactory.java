package br.com.atmbrasil.lobby.paper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Resolves Paper's exact 1.21.1 recipe packet constructor without linking against NMS. */
final class EmptyRecipeDefinitionsPacketFactory {
    private final Class<?> packetClass;
    private final Constructor<?> constructor;

    private EmptyRecipeDefinitionsPacketFactory(
            Class<?> packetClass,
            Constructor<?> constructor) {
        this.packetClass = Objects.requireNonNull(packetClass, "packetClass");
        this.constructor = Objects.requireNonNull(constructor, "constructor");
    }

    static EmptyRecipeDefinitionsPacketFactory resolve(ClassLoader classLoader)
            throws ReflectiveOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Class<?> packetClass = Class.forName(
                RecipePacketSuppressor.RECIPE_DEFINITIONS_PACKET_CLASS,
                false,
                classLoader);
        Constructor<?> constructor = packetClass.getConstructor(Collection.class);
        EmptyRecipeDefinitionsPacketFactory factory =
                new EmptyRecipeDefinitionsPacketFactory(packetClass, constructor);
        factory.create();
        return factory;
    }

    Object create() {
        try {
            Object packet = constructor.newInstance(List.of());
            if (!packetClass.isInstance(packet)) {
                throw new IllegalStateException(
                        "empty recipe constructor returned an unexpected packet class");
            }
            return packet;
        } catch (InstantiationException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "could not create an empty Paper recipe-definition packet", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException(
                    "Paper rejected an empty recipe-definition packet", cause);
        }
    }

    boolean producesPacketType(Class<?> expectedType) {
        return Objects.requireNonNull(expectedType, "expectedType").isAssignableFrom(packetClass);
    }
}
