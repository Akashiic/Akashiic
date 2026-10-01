package com.velocitypowered.api.event;
import java.util.concurrent.CompletableFuture;
public interface EventTask {
    static EventTask resumeWhenComplete(CompletableFuture<?> future) { return null; }
}
