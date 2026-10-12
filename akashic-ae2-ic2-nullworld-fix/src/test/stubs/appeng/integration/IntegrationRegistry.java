package appeng.integration;

public class IntegrationRegistry {
    public static final IntegrationRegistry INSTANCE = new IntegrationRegistry();
    public static Object ic2;

    public boolean isEnabled(IntegrationType type) {
        return true;
    }

    public Object getInstance(IntegrationType type) {
        return ic2;
    }
}
