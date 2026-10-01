package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import me.neznamy.tab.api.TabAPI;
import org.junit.jupiter.api.Test;

final class TabStateAccessorTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("e1f69e79-0432-33bb-ad07-2077994de007");

    @Test
    void readsOnlyTheFinalTrackedStateFromThePublicTabApi() throws Exception {
        TabAPI.setInstance(new TabAPI(new TestTabPlayer(
                true,
                new TestTabList(new TestComponent("§aHeader"), new TestComponent("§7Footer")))));
        TabStateAccessor accessor = TabStateAccessor.resolve(TabAPI.class.getClassLoader());

        Optional<TabStateAccessor.State> result = accessor.read(PLAYER_ID);

        assertTrue(result.isPresent());
        assertEquals("§aHeader", result.orElseThrow().header());
        assertEquals("§7Footer", result.orElseThrow().footer());
    }

    @Test
    void unavailableOrNotYetLoadedTabPlayersProduceNoSnapshot() throws Exception {
        TabStateAccessor accessor = TabStateAccessor.resolve(TabAPI.class.getClassLoader());

        TabAPI.setInstance(new TabAPI(null));
        assertTrue(accessor.read(PLAYER_ID).isEmpty());

        TabAPI.setInstance(new TabAPI(new TestTabPlayer(false, new TestTabList(null, null))));
        assertTrue(accessor.read(PLAYER_ID).isEmpty());
    }

    public record TestComponent(String value) {
        public String toLegacyText() {
            return value;
        }
    }

    public record TestTabList(Object header, Object footer) {
        public Object getHeader() {
            return header;
        }

        public Object getFooter() {
            return footer;
        }
    }

    public record TestTabPlayer(boolean loaded, Object tabList) {
        public boolean isLoaded() {
            return loaded;
        }

        public Object getTabList() {
            return tabList;
        }
    }
}
