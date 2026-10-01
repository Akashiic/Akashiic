package com.akashic.pylonguard;

/**
 * JVM kill-switches. Both default to ON.
 *
 * -Dakashic.pylonguard=false            disables everything (no mixin is applied at all)
 * -Dakashic.pylonguard.bootfilter=false disables only the two startup filters (CrystalNetworkerMixin and
 *                                       PylonGeneratorMixin); the pylon tick guard stays on
 *
 * Accepted "off" values: false, 0, off, no (case-insensitive).
 */
public final class PylonGuardSettings {

	public static final String MASTER = "akashic.pylonguard";
	public static final String BOOT_FILTER = "akashic.pylonguard.bootfilter";

	private PylonGuardSettings() {
	}

	public static boolean enabled() {
		return isOn(MASTER);
	}

	public static boolean bootFilterEnabled() {
		return isOn(BOOT_FILTER);
	}

	private static boolean isOn(String property) {
		String v = System.getProperty(property);
		if (v == null)
			return true;
		v = v.trim().toLowerCase();
		return !(v.equals("false") || v.equals("0") || v.equals("off") || v.equals("no"));
	}
}
