package com.velocitypowered.api.util;

import java.util.List;
import java.util.Objects;

/** Minimal immutable Forge mod-list value used by the offline compiler and tests. */
public final class ModInfo {
    public static final ModInfo DEFAULT = new ModInfo("FML", List.of());

    private final String type;
    private final List<Mod> mods;

    public ModInfo(String type, List<Mod> modList) {
        this.type = Objects.requireNonNull(type, "type");
        this.mods = List.copyOf(Objects.requireNonNull(modList, "modList"));
    }

    public String getType() {
        return type;
    }

    public List<Mod> getMods() {
        return mods;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ModInfo modInfo
                && type.equals(modInfo.type)
                && mods.equals(modInfo.mods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, mods);
    }

    @Override
    public String toString() {
        return "ModInfo{type=" + type + ", mods=" + mods + '}';
    }

    /** Minimal immutable entry in a Forge mod list. */
    public static final class Mod {
        private final String id;
        private final String version;

        public Mod(String id, String version) {
            this.id = Objects.requireNonNull(id, "id");
            this.version = Objects.requireNonNull(version, "version");
        }

        public String getId() {
            return id;
        }

        public String getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Mod mod
                    && id.equals(mod.id)
                    && version.equals(mod.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, version);
        }

        @Override
        public String toString() {
            return "Mod{id=" + id + ", version=" + version + '}';
        }
    }
}
