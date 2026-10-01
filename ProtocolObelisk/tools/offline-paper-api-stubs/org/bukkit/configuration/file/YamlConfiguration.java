package org.bukkit.configuration.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Minimal read-only YAML subset used by the offline configuration tests. */
public final class YamlConfiguration extends FileConfiguration {
    private final Map<String, Object> values;

    private YamlConfiguration(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public static YamlConfiguration loadConfiguration(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        ArrayList<Section> sections = new ArrayList<>();
        try {
            BufferedReader lines = reader instanceof BufferedReader buffered
                    ? buffered
                    : new BufferedReader(reader);
            String line;
            while ((line = lines.readLine()) != null) {
                parseLine(line, sections, values);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not read offline YAML fixture", exception);
        }
        return new YamlConfiguration(values);
    }

    private static void parseLine(
            String line,
            List<Section> sections,
            Map<String, Object> values) {
        int first = firstNonSpace(line);
        if (first == line.length() || line.charAt(first) == '#') {
            return;
        }
        if (line.indexOf('\t', 0) >= 0) {
            throw new IllegalArgumentException("tabs are unsupported in offline YAML fixtures");
        }
        int separator = line.indexOf(':', first);
        if (separator < 0) {
            throw new IllegalArgumentException("malformed offline YAML fixture line: " + line);
        }
        String name = line.substring(first, separator).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty offline YAML key");
        }
        while (!sections.isEmpty()
                && sections.getLast().indent() >= first) {
            sections.removeLast();
        }
        String rawValue = line.substring(separator + 1).trim();
        if (rawValue.isEmpty()) {
            sections.add(new Section(first, name));
            return;
        }
        StringBuilder path = new StringBuilder();
        for (Section section : sections) {
            path.append(section.name()).append('.');
        }
        path.append(name);
        values.put(path.toString(), scalar(rawValue));
    }

    private static int firstNonSpace(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static Object scalar(String value) {
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        try {
            return value.indexOf('.') >= 0
                    ? Double.valueOf(value)
                    : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return unquote(value);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    @Override
    public Object get(String path) {
        return values.get(path);
    }

    public boolean isBoolean(String path) {
        return get(path) instanceof Boolean;
    }

    public boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        Object value = get(path);
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    @Override
    public ConfigurationSection getConfigurationSection(String path) {
        String prefix = path + '.';
        boolean present = values.keySet().stream().anyMatch(key -> key.startsWith(prefix));
        return present ? new PrefixedSection(values, prefix) : null;
    }

    @Override
    public double getDouble(String path, double fallback) {
        Object value = get(path);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    @Override
    public int getInt(String path, int fallback) {
        Object value = get(path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    @Override
    public String getString(String path) {
        Object value = get(path);
        return value instanceof String string ? string : null;
    }

    private record Section(int indent, String name) {
    }

    private record PrefixedSection(Map<String, Object> values, String prefix)
            implements ConfigurationSection {
        private PrefixedSection {
            values = Map.copyOf(values);
            Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public Object get(String path) {
            return values.get(prefix + path);
        }

        @Override
        public boolean getBoolean(String path, boolean fallback) {
            Object value = get(path);
            return value instanceof Boolean booleanValue ? booleanValue : fallback;
        }

        @Override
        public double getDouble(String path, double fallback) {
            Object value = get(path);
            return value instanceof Number number ? number.doubleValue() : fallback;
        }
    }
}
