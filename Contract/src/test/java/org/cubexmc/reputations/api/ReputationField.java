package org.cubexmc.reputations.api;

/** Test fixture matching the optional provider's public reflection surface. */
public record ReputationField(
    String namespace,
    String id,
    String displayName,
    String description,
    String icon,
    boolean higherIsBetter
) {
    public String key() {
        return namespace + ":" + id;
    }

    public static Builder builder(String namespace, String id) {
        return new Builder(namespace, id);
    }

    public static final class Builder {
        private final String namespace;
        private final String id;
        private String displayName;
        private String description = "";
        private String icon = "PAPER";
        private boolean higherIsBetter = true;

        private Builder(String namespace, String id) {
            this.namespace = namespace;
            this.id = id;
            this.displayName = id;
        }

        public Builder displayName(String value) {
            displayName = value;
            return this;
        }

        public Builder description(String value) {
            description = value;
            return this;
        }

        public Builder icon(String value) {
            icon = value;
            return this;
        }

        public Builder higherIsBetter(boolean value) {
            higherIsBetter = value;
            return this;
        }

        public ReputationField build() {
            return new ReputationField(namespace, id, displayName, description, icon, higherIsBetter);
        }
    }
}
