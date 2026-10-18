package com.akashic.baubleslayout;

import cpw.mods.fml.common.FMLCommonHandler;

/** Shared bounds check for the client-side index guards. */
public final class SlotGuard {

    private static int rejected;

    private SlotGuard() {}

    /**
     * @return true when {@code slot} is outside {@code 0..size-1} on the client and the access must be skipped. Always
     *         false on the server, whose behaviour is left exactly as BaublesExpanded wrote it.
     */
    public static boolean rejects(int slot, int size, String where) {
        if (slot >= 0 && slot < size) {
            return false;
        }
        if (!FMLCommonHandler.instance()
            .getEffectiveSide()
            .isClient()) {
            return false;
        }
        rejected++;
        if (rejected <= 5 || rejected % 100 == 0) {
            AkashicBaublesLayout.LOG.warn(
                "Ignored Baubles slot {} in {} (client has {} slots; server layout differs). Occurrences: {}",
                slot,
                where,
                size,
                rejected);
        }
        return true;
    }
}
