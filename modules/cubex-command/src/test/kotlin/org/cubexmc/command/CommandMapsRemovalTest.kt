package org.cubexmc.command

import java.util.Collections
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandMapsRemovalTest {

    private class FakeCommand(name: String) : Command(name) {
        override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = true
    }

    private val mine = FakeCommand("gems")
    private val theirs = FakeCommand("gems")

    @Test
    fun `removes every label pointing at the command`() {
        val known = mutableMapOf<String, Command>(
            "gems" to mine,
            "rulegems:gems" to mine,
            "other" to theirs,
        )

        val removed = CommandMaps.removeMatching(known, mine)

        assertEquals(setOf("gems", "rulegems:gems"), removed.toSet())
        assertEquals(setOf("other"), known.keys)
    }

    @Test
    fun `never removes a label another plugin won by identity`() {
        // 同名但不是同一个对象:抢到标签的是别人,不能把它删掉
        val known = mutableMapOf<String, Command>("gems" to theirs)

        val removed = CommandMaps.removeMatching(known, mine)

        assertTrue(removed.isEmpty())
        assertSame(theirs, known["gems"])
    }

    @Test
    fun `survives a map that refuses removal instead of throwing`() {
        // Paper 26.x 交回来的 knownCommands 就是这种:改不动。
        // 旧实现在这里抛 UnsupportedOperationException,把 /rg reload 整条命令带崩。
        val known = Collections.unmodifiableMap(mutableMapOf<String, Command>("gems" to mine))

        val removed = CommandMaps.removeMatching(known, mine)

        assertTrue(removed.isEmpty())
        assertEquals(setOf("gems"), known.keys)
    }

    @Test
    fun `reports nothing when the command is not registered at all`() {
        assertTrue(CommandMaps.removeMatching(mutableMapOf(), mine).isEmpty())
    }
}
