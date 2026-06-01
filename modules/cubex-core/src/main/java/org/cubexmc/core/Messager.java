package org.cubexmc.core;

import java.util.Collections;
import org.bukkit.command.CommandSender;

public final class Messager {

    public void send(CommandSender target, String message) {
        if (target == null || message == null) {
            return;
        }
        String[] lines = message.split("\\R", -1);
        for (String line : lines) {
            target.sendMessage(line);
        }
    }

    public void sendLines(CommandSender target, Iterable<String> messages) {
        if (target == null) {
            return;
        }
        for (String message : messages == null ? Collections.<String>emptyList() : messages) {
            send(target, message);
        }
    }
}
