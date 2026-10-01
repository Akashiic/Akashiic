package com.velocitypowered.api.event.connection;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import java.util.Objects;

public final class PluginMessageEvent {
    private final ChannelMessageSource source;
    private final ChannelMessageSink target;
    private final ChannelIdentifier identifier;
    private final byte[] data;
    private ForwardResult result = ForwardResult.forward();

    public PluginMessageEvent(
            ChannelMessageSource source,
            ChannelMessageSink target,
            ChannelIdentifier identifier,
            byte[] data) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    public ChannelIdentifier getIdentifier() {
        return identifier;
    }

    public ChannelMessageSource getSource() {
        return source;
    }

    public ChannelMessageSink getTarget() {
        return target;
    }

    public byte[] getData() {
        return data.clone();
    }

    public ForwardResult getResult() {
        return result;
    }

    public void setResult(ForwardResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    public static final class ForwardResult {
        private static final ForwardResult FORWARD = new ForwardResult(true);
        private static final ForwardResult HANDLED = new ForwardResult(false);

        private final boolean allowed;

        private ForwardResult(boolean allowed) {
            this.allowed = allowed;
        }

        public static ForwardResult forward() {
            return FORWARD;
        }

        public static ForwardResult handled() {
            return HANDLED;
        }

        public boolean isAllowed() {
            return allowed;
        }
    }
}
