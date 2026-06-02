package org.cubexmc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cubexmc.core.Reloadable;

public final class ReloadChain implements Reloadable {

    private final List<Entry> entries = new ArrayList<>();

    private ReloadChain() {
    }

    public static ReloadChain create() {
        return new ReloadChain();
    }

    public ReloadChain add(String name, Reloadable reloadable) {
        if (reloadable != null) {
            entries.add(new Entry(name, reloadable));
        }
        return this;
    }

    public ReloadChain add(String name, Runnable action) {
        if (action != null) {
            add(name, (Reloadable) action::run);
        }
        return this;
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            names.add(entry.name);
        }
        return Collections.unmodifiableList(names);
    }

    @Override
    public void reload() throws Exception {
        for (Entry entry : entries) {
            entry.reloadable.reload();
        }
    }

    private static final class Entry {
        private final String name;
        private final Reloadable reloadable;

        private Entry(String name, Reloadable reloadable) {
            this.name = name == null ? "" : name;
            this.reloadable = reloadable;
        }
    }
}
