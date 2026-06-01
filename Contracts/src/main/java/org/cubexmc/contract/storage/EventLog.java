package org.cubexmc.contract.storage;

import org.cubexmc.contract.ContractPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class EventLog {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private final ContractPlugin plugin;
    private final File file;

    public EventLog(ContractPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "events.log");
    }

    public void append(String contractId, String type, String detail) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        String line = FORMAT.format(Instant.now())
            + " | " + contractId
            + " | " + type
            + " | " + sanitize(detail)
            + System.lineSeparator();
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to append event log: " + ex.getMessage());
        }
    }

    private String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replace("\r", " ").replace("\n", " ").replace("|", "/");
    }
}
