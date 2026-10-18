package com.akashic.baubleslayout.network;

import com.akashic.baubleslayout.ClientLayout;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/** Runs on the client thread (1.7.10 processes FML channel packets in the main packet loop). */
public class LayoutMessageHandler implements IMessageHandler<LayoutMessage, IMessage> {

    @Override
    public IMessage onMessage(LayoutMessage message, MessageContext ctx) {
        ClientLayout.applyServerLayout(message.slots);
        return null;
    }
}
