package org.cubexmc.core

import java.util.regex.Pattern
import org.bukkit.command.CommandSender

class Messager {
    fun send(target: CommandSender?, message: String?) {
        if (target == null || message == null) return

        for (line in LINE_BREAK.split(message, -1)) {
            target.sendMessage(line)
        }
    }

    fun sendLines(target: CommandSender?, messages: Iterable<String>?) {
        if (target == null) return

        for (message in messages ?: emptyList()) {
            send(target, message)
        }
    }

    private companion object {
        val LINE_BREAK: Pattern = Pattern.compile("\\R")
    }
}
