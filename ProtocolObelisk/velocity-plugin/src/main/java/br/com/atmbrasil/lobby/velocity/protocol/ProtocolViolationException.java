package br.com.atmbrasil.lobby.velocity.protocol;

import java.io.Serial;

/** A bounded, user-safe description of malformed or unsupported handshake input. */
public final class ProtocolViolationException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public ProtocolViolationException(String message) {
        super(message);
    }
}
