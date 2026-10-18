package com.akashic.baubleslayout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.container.InventoryBaubles;
import baubles.common.lib.PlayerHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Client-side half: adopts the server's slot layout for the duration of a connection and puts the locally computed
 * layout back once the client disconnects. Everything here runs on the client thread, the same thread that processes
 * Baubles' own sync packets, so the static layout is never mutated under their feet.
 */
public final class ClientLayout {

    private static Field assignedSlotsField;
    private static Field clientInventoriesField;
    private static boolean reflectionFailed;

    /** Layout computed by this client at startup; null until the first server layout is applied. */
    private static String[] localLayout;
    private static volatile boolean restorePending;

    private ClientLayout() {}

    public static void applyServerLayout(String[] serverSlots) {
        restorePending = false;
        if (!resolveReflection()) {
            return;
        }
        if (localLayout == null) {
            localLayout = BaubleExpandedSlots.getCurrentSlotAssignments();
        }

        int limit = Math.min(serverSlots.length, BaubleExpandedSlots.slotLimit);
        List<String> layout = new ArrayList<>(limit);
        List<String> unknownTypes = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String type = serverSlots[i];
            // A type no client mod registered keeps its slot (so every index still lines up) but stays empty.
            if (BaubleExpandedSlots.isTypeRegistered(type) && !BaubleExpandedSlots.unknownType.equals(type)) {
                layout.add(type);
            } else {
                layout.add(BaubleExpandedSlots.unknownType);
                unknownTypes.add(type);
            }
        }
        if (serverSlots.length > limit) {
            AkashicBaublesLayout.LOG.warn(
                "Server sent {} Baubles slots; keeping the first {} (BaublesExpanded limit).",
                serverSlots.length,
                limit);
        }

        String[] before = BaubleExpandedSlots.getCurrentSlotAssignments();
        if (Arrays.equals(before, layout.toArray(new String[0]))) {
            return;
        }
        setLayout(layout);
        AkashicBaublesLayout.LOG.info(
            "Adopted the server Baubles layout: {} slots (this client had {}). Server: {} Local: {}{}",
            layout.size(),
            before.length,
            layout,
            Arrays.toString(before),
            unknownTypes.isEmpty() ? "" : " Types unknown to this client (slot kept empty): " + unknownTypes);
    }

    static void restoreLocalLayout() {
        if (localLayout == null || !resolveReflection()) {
            return;
        }
        if (!Arrays.equals(localLayout, BaubleExpandedSlots.getCurrentSlotAssignments())) {
            setLayout(Arrays.asList(localLayout));
            AkashicBaublesLayout.LOG
                .info("Disconnected: restored the local Baubles layout ({} slots).", localLayout.length);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setLayout(List<String> layout) {
        try {
            List<String> assigned = (List<String>) assignedSlotsField.get(null);
            assigned.clear();
            assigned.addAll(layout);

            // Inventories already cached for this connection were sized for the old layout.
            Map<String, InventoryBaubles> inventories = (Map<String, InventoryBaubles>) clientInventoriesField
                .get(null);
            for (InventoryBaubles inventory : inventories.values()) {
                if (inventory.stackList.length != layout.size()) {
                    inventory.stackList = Arrays.copyOf(inventory.stackList, layout.size());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            AkashicBaublesLayout.LOG.error("Could not apply the Baubles slot layout", e);
        }
    }

    private static boolean resolveReflection() {
        if (assignedSlotsField != null) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            assignedSlotsField = BaubleExpandedSlots.class.getDeclaredField("assignedSlots");
            assignedSlotsField.setAccessible(true);
            clientInventoriesField = PlayerHandler.class.getDeclaredField("playerBaublesClient");
            clientInventoriesField.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            assignedSlotsField = null;
            AkashicBaublesLayout.LOG
                .error("Unsupported BaublesExpanded version; layout sync disabled (index guards stay active)", e);
            return false;
        }
    }

    public static final class Events {

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            // Netty thread: only flag it, the client tick applies it.
            restorePending = true;
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START && restorePending) {
                restorePending = false;
                restoreLocalLayout();
            }
        }
    }
}
