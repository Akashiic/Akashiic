package com.akashic.baubleslayout.network;

import baubles.api.expanded.BaubleExpandedSlots;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** The server's ordered list of Baubles slot types. */
public class LayoutMessage implements IMessage {

    /** Generous upper bound against garbage input; BaublesExpanded itself caps at {@code slotLimit} (20). */
    private static final int MAX_SLOTS = 64;

    public String[] slots = new String[0];

    public LayoutMessage() {}

    public static LayoutMessage fromCurrentLayout() {
        LayoutMessage message = new LayoutMessage();
        message.slots = BaubleExpandedSlots.getCurrentSlotAssignments();
        return message;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, slots.length, 5);
        for (String slot : slots) {
            ByteBufUtils.writeUTF8String(buf, slot);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = ByteBufUtils.readVarInt(buf, 5);
        if (count < 0 || count > MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid Baubles slot count " + count);
        }
        slots = new String[count];
        for (int i = 0; i < count; i++) {
            slots[i] = ByteBufUtils.readUTF8String(buf);
        }
    }
}
