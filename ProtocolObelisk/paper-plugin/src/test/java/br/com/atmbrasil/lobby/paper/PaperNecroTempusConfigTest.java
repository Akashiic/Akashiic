package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PaperNecroTempusConfigTest {
    @Test
    void missingAdditiveSectionUsesTheValidatedCompanionDefaults() {
        PaperNecroTempusConfig config = PaperNecroTempusConfig.defaults();

        assertTrue(config.enabled());
        assertEquals(2, config.refreshTicks());
        assertEquals(16_384, config.maximumTextBytes());
        assertEquals(64, config.maximumLines());
        assertEquals(30_000, config.maximumCompressedNbtBytes());
        assertTrue(config.sendRemoveOnDisable());
    }

    @Test
    void explicitValuesAreRetainedWithoutAnIndependentDebugOverride() {
        PaperNecroTempusConfig config = PaperNecroTempusConfig.fromValues(
                false, 200, 60_000, 256, (long) Short.MAX_VALUE, false);

        assertFalse(config.enabled());
        assertEquals(200, config.refreshTicks());
        assertEquals(60_000, config.maximumTextBytes());
        assertEquals(256, config.maximumLines());
        assertEquals(Short.MAX_VALUE, config.maximumCompressedNbtBytes());
        assertFalse(config.sendRemoveOnDisable());
    }

    @Test
    void booleansAndIntegerBoundsAreStrict() {
        assertThrows(IllegalArgumentException.class, () ->
                PaperNecroTempusConfig.fromValues("true", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                PaperNecroTempusConfig.fromValues(null, "2", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                PaperNecroTempusConfig.fromValues(null, 0, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                PaperNecroTempusConfig.fromValues(null, null, null, null, 32_768, null));
        assertThrows(IllegalArgumentException.class, () ->
                PaperNecroTempusConfig.fromValues(null, null, null, null, null, 1));
    }
}
