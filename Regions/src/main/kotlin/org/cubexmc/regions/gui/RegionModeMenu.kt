package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

/**
 * The gameplay-mode editor. Only the settings that belong to the selected mode are rendered, and the
 * raw `key=value` entry stays reserved for super-administrators.
 */
internal class RegionModeMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    private val modeSlots = linkedMapOf(
        10 to "free_event",
        11 to "run_race",
        12 to "dual_pvp",
        13 to "boat_race",
        14 to "union_war",
        15 to "horse_race",
        16 to "hide_and_seek",
    )

    fun open(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        val values = mode.values
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.MODE, region.id),
            54,
            text.component("gui.mode.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        for ((slot, type) in modeSlots) {
            inventory.setItem(slot, items.mode(type, region.mode?.type))
        }

        val defaultVehicle = GuiValues.defaultVehicle(mode.type)
        val here = GuiValues.formatLocation(player.location)
        inventory.setItem(19, setting(Material.PLAYER_HEAD, "gui.mode.min-players", values["min-players"] ?: "2"))
        inventory.setItem(20, setting(Material.SKELETON_SKULL, "gui.mode.max-players", values["max-players"] ?: text.text("gui.common.unlimited")))
        inventory.setItem(21, setting(Material.BELL, "gui.mode.require-ready", values["require-ready"] ?: "true"))
        inventory.setItem(22, setting(Material.CHEST, "gui.mode.replace-gear", values["replace-gear"] ?: "true"))
        inventory.setItem(23, setting(Material.BEACON, "gui.mode.min-unions", values["min-unions"] ?: "2"))
        inventory.setItem(24, setting(Material.MINECART, "gui.mode.vehicle", items.describeVehicle(values["vehicle"] ?: defaultVehicle)))
        inventory.setItem(25, text.item(Material.ENDER_PEARL, "gui.mode.set-respawn", mapOf("location" to here)))
        inventory.setItem(
            26,
            setting(Material.TRIPWIRE_HOOK, "gui.mode.start-vehicle", items.describeVehicle(values["start-vehicle"] ?: values["vehicle"] ?: defaultVehicle)),
        )
        inventory.setItem(28, text.item(Material.IRON_SWORD, "gui.mode.kit-iron"))
        inventory.setItem(29, text.item(Material.BOW, "gui.mode.kit-bow"))
        inventory.setItem(30, text.item(Material.DIAMOND_SWORD, "gui.mode.kit-diamond"))
        inventory.setItem(31, text.item(GuiIcons.CLEAR, "gui.mode.kit-clear"))
        inventory.setItem(
            32,
            setting(Material.REDSTONE, "gui.mode.finish-vehicle", items.describeVehicle(values["finish-vehicle"] ?: values["vehicle"] ?: defaultVehicle)),
        )
        inventory.setItem(
            33,
            text.item(Material.LODESTONE, "gui.mode.respawn", mapOf("value" to (values["respawn"] ?: values["outside"] ?: text.text("gui.common.unset")))),
        )
        inventory.setItem(34, text.item(Material.LAVA_BUCKET, "gui.mode.respawn-clear"))
        inventory.setItem(36, text.item(Material.GREEN_WOOL, "gui.mode.set-start", mapOf("value" to (values["start"] ?: text.text("gui.common.unset")))))
        inventory.setItem(37, text.item(Material.RED_WOOL, "gui.mode.set-finish", mapOf("value" to (values["finish"] ?: text.text("gui.common.unset")))))
        inventory.setItem(
            38,
            text.item(
                Material.YELLOW_WOOL,
                "gui.mode.add-checkpoint",
                mapOf(
                    "count" to GuiValues.checkpointCount(values["checkpoints"]).toString(),
                    "vehicle" to items.describeVehicle(values["vehicle"] ?: defaultVehicle),
                    "location" to here,
                ),
            ),
        )
        inventory.setItem(39, text.item(Material.SHEARS, "gui.mode.clear-checkpoints"))
        inventory.setItem(40, setting(Material.TARGET, "gui.mode.require-start", values["require-start"] ?: "true"))
        inventory.setItem(41, setting(Material.ENDER_EYE, "gui.mode.teleport-start", values["teleport-start"] ?: "false"))
        inventory.setItem(42, setting(Material.LEVER, "gui.mode.start-mode", values["start-mode"] ?: "vote"))
        inventory.setItem(43, setting(Material.SLIME_BALL, "gui.mode.radius", values["radius"] ?: "2.5"))
        inventory.setItem(44, judgeItem(values))
        inventory.setItem(45, setting(Material.ENDER_EYE, "gui.mode.seekers", values["seekers"] ?: text.text("gui.common.auto")))
        inventory.setItem(46, setting(Material.CLOCK, "gui.mode.hide-seconds", values["hide-seconds"] ?: "30"))
        inventory.setItem(47, setting(Material.RECOVERY_COMPASS, "gui.mode.round-seconds", values["round-seconds"] ?: "300"))
        inventory.setItem(48, text.item(Material.PAPER, "gui.mode.advanced"))
        inventory.setItem(49, items.back())
        inventory.setItem(50, setting(Material.PLAYER_HEAD, "gui.mode.found-becomes-seeker", values["found-becomes-seeker"] ?: "true"))
        inventory.setItem(51, setting(Material.CLOCK, "gui.mode.timeout-seconds", values["timeout-seconds"] ?: "300"))

        val allowed = GuiSlots.modeConfiguration(mode.type).toMutableSet()
        if (plugin.authority().isSuperAdmin(player)) allowed.add(48)
        for (slot in GuiSlots.MODE_CONFIGURATION) {
            if (!allowed.contains(slot)) inventory.setItem(slot, null)
        }
        player.openInventory(inventory)
    }

    private fun setting(material: Material, key: String, value: String) =
        text.item(material, key, mapOf("value" to value))

    fun click(player: Player, regionId: String, slot: Int, rightClick: Boolean) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        if (slot in GuiSlots.MODE_CONFIGURATION && slot != 48 && !GuiSlots.modeConfiguration(mode.type).contains(slot)) {
            return
        }
        if (slot == 48 && !plugin.authority().isSuperAdmin(player)) return
        modeSlots[slot]?.let { type ->
            val values = if (region.mode?.type == type) region.mode.values else GuiValues.defaultModeValues(type)
            return gui.saveAndReopen(player, region.copy(mode = ModeConfig(type, values))) { open(player, regionId) }
        }
        val values = mode.values
        val updated: Map<String, String> = when (slot) {
            19 -> GuiValues.adjustInt(values, "min-players", 2, rightClick, min = 1)
            20 -> GuiValues.adjustInt(values, "max-players", 0, rightClick, min = 0, removeAtZero = true)
            21 -> GuiValues.toggleBool(values, "require-ready", true)
            22 -> GuiValues.toggleBool(values, "replace-gear", true)
            23 -> GuiValues.adjustInt(values, "min-unions", 2, rightClick, min = 2)
            24 -> GuiValues.cycleVehicle(values, "vehicle", GuiValues.defaultVehicle(mode.type))
            25 -> LinkedHashMap(values).apply { this["respawn"] = GuiValues.formatLocation(player.location) }
            26 -> GuiValues.cycleVehicle(values, "start-vehicle", values["vehicle"] ?: GuiValues.defaultVehicle(mode.type))
            28, 29, 30 -> GuiValues.applyKit(values, slot)
            31 -> GuiValues.clearKit(values)
            32 -> GuiValues.cycleVehicle(values, "finish-vehicle", values["vehicle"] ?: GuiValues.defaultVehicle(mode.type))
            34 -> LinkedHashMap(values).apply {
                remove("respawn")
                remove("outside")
            }
            36 -> LinkedHashMap(values).apply { put("start", GuiValues.formatLocation(player.location)) }
            37 -> LinkedHashMap(values).apply { put("finish", GuiValues.formatLocation(player.location)) }
            38 -> appendCheckpoint(values, mode.type, player)
            39 -> LinkedHashMap(values).apply {
                remove("checkpoints")
                remove("checkpoint-vehicles")
            }
            40 -> GuiValues.toggleBool(values, "require-start", true)
            41 -> GuiValues.toggleBool(values, "teleport-start", false)
            42 -> LinkedHashMap(values).apply {
                this["start-mode"] = if (this["start-mode"].equals("judge", ignoreCase = true)) "vote" else "judge"
            }
            43 -> GuiValues.adjustDouble(values, "radius", 2.5, rightClick, min = 1.0)
            44 -> return if (rightClick) {
                save(player, region, GuiValues.toggleJudge(values, player.uniqueId))
            } else {
                promptJudge(player, region)
            }
            45 -> GuiValues.adjustInt(values, "seekers", 1, rightClick, min = 1)
            46 -> GuiValues.adjustInt(values, "hide-seconds", 30, rightClick, min = 0, step = 10)
            47 -> GuiValues.adjustInt(values, "round-seconds", 300, rightClick, min = 0, removeAtZero = true, step = 60)
            48 -> return promptModeValue(player, region)
            49 -> return gui.openDetail(player, regionId)
            50 -> GuiValues.toggleBool(values, "found-becomes-seeker", true)
            51 -> GuiValues.adjustInt(values, "timeout-seconds", 300, rightClick, min = 60, step = 60)
            else -> return
        }
        save(player, region, updated)
    }

    private fun appendCheckpoint(values: Map<String, String>, modeType: String, player: Player): Map<String, String> =
        LinkedHashMap(values).apply {
            val existing = this["checkpoints"].orEmpty()
            val here = GuiValues.formatLocation(player.location)
            this["checkpoints"] = if (existing.isBlank()) here else "$existing;$here"
            val existingVehicles = this["checkpoint-vehicles"].orEmpty()
            val checkpointVehicle = this["vehicle"] ?: GuiValues.defaultVehicle(modeType)
            this["checkpoint-vehicles"] =
                if (existingVehicles.isBlank()) checkpointVehicle else "$existingVehicles;$checkpointVehicle"
        }

    private fun save(player: Player, region: RegionDefinition, values: Map<String, String>) {
        val mode = region.mode ?: ModeConfig("free_event")
        gui.saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { open(player, region.id) }
    }

    /** 名单存的是 UUID，展示时换回名字——否则这一格就是一串没人看得懂的十六进制。 */
    private fun judgeItem(values: Map<String, String>): ItemStack {
        val judges = GuiValues.parseJudges(values)
        val names = judges.map { id ->
            Bukkit.getOfflinePlayer(id).name ?: id.toString()
        }
        return text.item(
            Material.NAME_TAG,
            "gui.mode.judges",
            mapOf("value" to names.joinToString(", ").ifBlank { text.text("gui.common.none") }),
        )
    }

    private fun promptJudge(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.judge") { raw ->
            val name = raw.trim()
            val mode = region.mode ?: ModeConfig("free_event")
            if (name.equals("clear", ignoreCase = true)) {
                return@promptLine save(player, region, GuiValues.clearJudges(mode.values))
            }
            val target = resolvePlayer(name)
            if (target == null) {
                text.send(player, "gui.mode.judge-unknown", mapOf("name" to name))
                return@promptLine open(player, region.id)
            }
            save(player, region, GuiValues.toggleJudge(mode.values, target))
        }
    }

    /**
     * 在线玩家优先，否则只认服务器已经缓存过的档案。
     *
     * 刻意不用 `Bukkit.getOfflinePlayer(name)`：那个方法对没见过的名字会去请求 Mojang API，
     * 既阻塞主线程，又会给打错的名字凭空造出一个 UUID——那意味着把发令权发给一个不存在的人。
     */
    private fun resolvePlayer(name: String): UUID? {
        if (name.isBlank()) return null
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return cached.uniqueId
    }

    private fun promptModeValue(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.mode-value") { raw ->
            val args = GuiValues.splitArgs(raw)
            val mode = region.mode ?: ModeConfig("free_event")
            val values = LinkedHashMap(mode.values)
            if (args.size == 2 && args[0].equals("clear", ignoreCase = true)) {
                values.remove(args[1])
            } else {
                val pair = args.firstOrNull()?.let { GuiValues.parsePair(it) }
                if (pair == null) {
                    text.send(player, "gui.mode.key-value-required")
                    open(player, region.id)
                    return@promptLine
                }
                values[pair.first] = pair.second
            }
            gui.saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { open(player, region.id) }
        }
    }
}
