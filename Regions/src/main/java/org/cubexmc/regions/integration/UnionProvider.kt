package org.cubexmc.regions.integration

import org.cubexmc.regions.model.UnionRef
import java.util.UUID

interface UnionProvider {
    val type: String
    fun isAvailable(): Boolean
    fun getUnion(playerId: UUID): UnionRef?
    fun areSameUnion(a: UUID, b: UUID): Boolean
    fun areAllied(a: UUID, b: UUID): Boolean
    fun areEnemies(a: UUID, b: UUID): Boolean
    fun placeholder(playerId: UUID, key: String): String?
}
