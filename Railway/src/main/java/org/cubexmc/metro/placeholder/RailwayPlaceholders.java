package org.cubexmc.metro.placeholder;

import org.bukkit.OfflinePlayer;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.LineService;
import org.cubexmc.metro.train.TrainInstance;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.List;

public class RailwayPlaceholders extends PlaceholderExpansion {

    private final Metro plugin;

    public RailwayPlaceholders(Metro plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty() ? "railway" : String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getIdentifier() {
        return "railway";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isEmpty()) return "";
        String[] parts = params.split("_");
        if (parts.length == 0) return "";
        String type = parts[0].toLowerCase();

        if ("eta".equals(type)) {
            // %railway_eta_line_l1_stop_s3%
            String lineId = findValue(parts, "line");
            String stopId = findValue(parts, "stop");
            if (lineId == null || stopId == null) return "";
            int etaSec = estimateEtaSeconds(lineId, stopId);
            if (etaSec < 0) return "--:--";
            return formatSeconds(etaSec);
        }

        if ("next".equals(type) || "nextstop".equals(type)) {
            // %railway_next_line_l1_from_s3%
            String lineId = findValue(parts, "line");
            String fromId = findValue(parts, "from");
            if (lineId == null || fromId == null) return "";
            return nextStopName(lineId, fromId);
        }

        return "";
    }

    private String findValue(String[] parts, String key) {
        for (int i = 0; i < parts.length - 1; i++) {
            if (key.equalsIgnoreCase(parts[i])) {
                return parts[i + 1];
            }
        }
        // Also support simple pattern eta_l1_s3
        if ("line".equals(key) && parts.length >= 3) return parts[1];
        if ("stop".equals(key) && parts.length >= 3) return parts[2];
        if ("from".equals(key) && parts.length >= 3) return parts[2];
        return null;
    }

    private int estimateEtaSeconds(String lineId, String stopId) {
        LineService service = plugin.getLineServiceManager().getService(lineId);
        if (service == null) {
            return -1;
        }
        // Prefer active trains' ETA
        int best = Integer.MAX_VALUE;
        long now = org.cubexmc.metro.util.SchedulerUtil.getCurrentTick();
        for (TrainInstance t : service.getActiveTrains()) {
            double eta = t.estimateEtaSecondsToStop(stopId, now, plugin.getTravelTimeEstimator());
            if (eta != Double.POSITIVE_INFINITY) {
                int sec = (int) Math.round(eta);
                if (sec < best) best = sec;
            }
        }
        if (best != Integer.MAX_VALUE) return best;
        // Fallback to headway-based estimate
        return plugin.getLineServiceManager().estimateNextEtaSeconds(lineId, stopId);
    }

    private String nextStopName(String lineId, String fromId) {
        Line line = plugin.getLineManager().getLine(lineId);
        if (line == null) return "";
        List<String> stops = line.getOrderedStopIds();
        if (stops == null || stops.isEmpty()) return "";
        int idx = stops.indexOf(fromId);
        if (idx < 0) return "";
        boolean loop = plugin.getLineServiceManager().getService(lineId) != null
                && plugin.getLineServiceManager().getService(lineId).isLoopLine();
        int nextIdx = idx + 1;
        if (nextIdx >= stops.size()) {
            if (loop) nextIdx = 0; else return "";
        }
        String nextId = stops.get(nextIdx);
        Stop s = plugin.getStopManager().getStop(nextId);
        return (s != null && s.getName() != null && !s.getName().isEmpty()) ? s.getName() : nextId;
    }

    private String formatSeconds(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}


