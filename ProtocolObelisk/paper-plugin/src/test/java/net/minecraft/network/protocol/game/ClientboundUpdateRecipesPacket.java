package net.minecraft.network.protocol.game;

import java.util.Collection;
import java.util.List;
import net.minecraft.network.protocol.Packet;

/** Test fixture matching Paper 1.21.1's public Collection constructor. */
public final class ClientboundUpdateRecipesPacket implements Packet<Object> {
    private final List<?> recipes;

    public ClientboundUpdateRecipesPacket(Collection<?> recipes) {
        this.recipes = List.copyOf(recipes);
    }

    public List<?> recipes() {
        return recipes;
    }
}
