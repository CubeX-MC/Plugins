package org.cubexmc.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class EconomyAccountTest {

    @Test
    fun `blank config means money is destroyed`() {
        assertSame(EconomyAccount.None, EconomyAccount.parse(null))
        assertSame(EconomyAccount.None, EconomyAccount.parse(""))
        assertSame(EconomyAccount.None, EconomyAccount.parse("   "))
    }

    @Test
    fun `a plain word is a player name`() {
        assertEquals(EconomyAccount.PlayerName("cubex_bank"), EconomyAccount.parse("cubex_bank"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(EconomyAccount.PlayerName("cubex_bank"), EconomyAccount.parse("  cubex_bank  "))
    }

    @Test
    fun `the uuid prefix pins an exact player account`() {
        val uuid = UUID.fromString("6b1a3f2c-8d4e-4f7a-9b0c-1d2e3f4a5b6c")
        assertEquals(EconomyAccount.PlayerUuid(uuid), EconomyAccount.parse("uuid:$uuid"))
        assertEquals(EconomyAccount.PlayerUuid(uuid), EconomyAccount.parse("UUID: $uuid"))
    }

    @Test
    fun `a bare uuid is accepted because no player name can look like one`() {
        val uuid = UUID.randomUUID()
        assertEquals(EconomyAccount.PlayerUuid(uuid), EconomyAccount.parse(uuid.toString()))
    }

    @Test
    fun `the name prefix hands the raw name to vault`() {
        assertEquals(EconomyAccount.RawName("cubex_bank"), EconomyAccount.parse("name:cubex_bank"))
        assertEquals(EconomyAccount.RawName("cubex_bank"), EconomyAccount.parse("NAME: cubex_bank "))
    }

    @Test
    fun `a raw name is validated like a player name`() {
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("name:") }
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("name:CubeX Bank") }
    }

    @Test
    fun `the bank prefix selects a vault bank`() {
        assertEquals(EconomyAccount.Bank("CubeX Bank"), EconomyAccount.parse("bank:CubeX Bank"))
        assertEquals(EconomyAccount.Bank("CubeX Bank"), EconomyAccount.parse("BANK: CubeX Bank "))
    }

    @Test
    fun `a malformed uuid is rejected instead of falling back to a player name`() {
        // 静默降级成 PlayerName("not-a-uuid") 会让钱进一个幽灵账户,所以这里必须炸。
        val error = assertThrows(IllegalArgumentException::class.java) {
            EconomyAccount.parse("uuid:not-a-uuid")
        }
        assertEquals(true, error.message!!.contains("uuid:not-a-uuid"))
    }

    @Test
    fun `a shortened uuid is rejected even though UUID fromString would accept it`() {
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("uuid:1-2-3-4-5") }
    }

    @Test
    fun `an empty bank name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("bank:") }
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("bank:   ") }
    }

    @Test
    fun `a player name with a space is rejected as a likely typo`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EconomyAccount.parse("CubeX Bank")
        }
        assertEquals(true, error.message!!.contains("bank:"))
    }

    @Test
    fun `an absurdly long player name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EconomyAccount.parse("a".repeat(65)) }
    }

    @Test
    fun `labels name the account kind for logs`() {
        assertEquals("none (money is destroyed)", EconomyAccount.None.label())
        assertEquals("player cubex_bank", EconomyAccount.PlayerName("cubex_bank").label())
        assertEquals("economy account name:cubex_bank", EconomyAccount.RawName("cubex_bank").label())
        assertEquals("bank CubeXBank", EconomyAccount.Bank("CubeXBank").label())
    }
}
