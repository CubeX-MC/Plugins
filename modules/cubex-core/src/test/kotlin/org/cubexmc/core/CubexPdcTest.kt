package org.cubexmc.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CubexPdcTest {

    private enum class VehicleState { PARKED, ACTIVE }

    @Test
    fun `decodes a well formed uuid`() {
        val id = UUID.randomUUID()

        assertEquals(id, CubexPdc.decodeUuid(id.toString()))
    }

    @Test
    fun `returns null for a malformed uuid instead of throwing`() {
        assertNull(CubexPdc.decodeUuid("not-a-uuid"))
        assertNull(CubexPdc.decodeUuid("1234"))
    }

    @Test
    fun `returns null for a missing or empty uuid`() {
        assertNull(CubexPdc.decodeUuid(null))
        assertNull(CubexPdc.decodeUuid(""))
    }

    @Test
    fun `decodes an enum by name`() {
        assertEquals(VehicleState.PARKED, CubexPdc.decodeEnum("PARKED", VehicleState.entries.toTypedArray()))
    }

    @Test
    fun `returns null for an enum name that no longer exists`() {
        // 旧存档里留着已删除的枚举名是常见的版本演进,不该让插件炸掉
        assertNull(CubexPdc.decodeEnum("TOWED", VehicleState.entries.toTypedArray()))
    }

    @Test
    fun `enum decoding is case sensitive, matching valueOf`() {
        assertNull(CubexPdc.decodeEnum("parked", VehicleState.entries.toTypedArray()))
    }

    @Test
    fun `returns null for a missing or empty enum name`() {
        assertNull(CubexPdc.decodeEnum(null, VehicleState.entries.toTypedArray()))
        assertNull(CubexPdc.decodeEnum("", VehicleState.entries.toTypedArray()))
    }
}
