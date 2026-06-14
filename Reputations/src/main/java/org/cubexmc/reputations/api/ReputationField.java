package org.cubexmc.reputations.api;

/**
 * A reputation field a plugin contributes, e.g. {@code Contract:completed}. Plugins register their
 * fields once on enable; the stored per-player values persist across restarts. The key is
 * {@code namespace:id}; neither part may contain {@code '.'} or {@code ':'}.
 */
public final class ReputationField {

    private final String namespace;
    private final String id;
    private final String displayName;
    private final String description;
    private final String icon;
    private final double defaultValue;
    private final boolean higherIsBetter;

    private ReputationField(Builder builder) {
        this.namespace = builder.namespace;
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.icon = builder.icon;
        this.defaultValue = builder.defaultValue;
        this.higherIsBetter = builder.higherIsBetter;
    }

    public String namespace() {
        return namespace;
    }

    public String id() {
        return id;
    }

    /** Fully-qualified key: {@code namespace:id}. */
    public String key() {
        return namespace + ":" + id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Bukkit Material name used as the icon in the viewer GUI. */
    public String icon() {
        return icon;
    }

    public double defaultValue() {
        return defaultValue;
    }

    /** When true, larger values are "better" (used only for display hints). */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    public static Builder builder(String namespace, String id) {
        return new Builder(namespace, id);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReputationField && ((ReputationField) other).key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return "ReputationField(" + key() + ")";
    }

    public static final class Builder {
        private final String namespace;
        private final String id;
        private String displayName;
        private String description = "";
        private String icon = "PAPER";
        private double defaultValue = 0.0;
        private boolean higherIsBetter = true;

        private Builder(String namespace, String id) {
            this.namespace = requirePlain(namespace, "namespace");
            this.id = requirePlain(id, "id");
            this.displayName = id;
        }

        public Builder displayName(String value) {
            this.displayName = value == null || value.isEmpty() ? id : value;
            return this;
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        public Builder icon(String materialName) {
            this.icon = materialName == null || materialName.isEmpty() ? "PAPER" : materialName;
            return this;
        }

        public Builder defaultValue(double value) {
            this.defaultValue = value;
            return this;
        }

        public Builder higherIsBetter(boolean value) {
            this.higherIsBetter = value;
            return this;
        }

        public ReputationField build() {
            return new ReputationField(this);
        }

        private static String requirePlain(String value, String name) {
            if (value == null || value.isEmpty() || value.indexOf('.') >= 0 || value.indexOf(':') >= 0) {
                throw new IllegalArgumentException(name + " must be non-empty and contain no '.' or ':'");
            }
            return value;
        }
    }
}
