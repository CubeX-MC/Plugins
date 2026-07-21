package org.cubexmc.regions.capability

object BuiltInRegionCapabilities {
    fun registerAll(catalog: CapabilityCatalog) {
        registerSources(catalog)
        registerModes(catalog)
        registerFlags(catalog)
        registerEffects(catalog)
        registerActions(catalog)
        registerConditions(catalog)
    }

    private fun registerSources(catalog: CapabilityCatalog) {
        catalog.register(descriptor(
            CapabilityKind.SOURCE,
            "lands",
            parameters = listOf(string("land", required = true), string("area", allowBlank = false)),
            requiredPlugins = setOf("Lands"),
        ))
        catalog.register(descriptor(
            CapabilityKind.SOURCE,
            "cuboid",
            risk = CapabilityRisk.MEDIUM,
            strict = false,
            parameters = listOf(
                string("id"), string("name"), string("world", required = true),
                decimal("min-x", required = true), decimal("min-y", required = true), decimal("min-z", required = true),
                decimal("max-x", required = true), decimal("max-y", required = true), decimal("max-z", required = true),
            ),
        ))
    }

    private fun registerModes(catalog: CapabilityCatalog) {
        val common = listOf(
            integer("min-players", min = 1.0),
            integer("max-players", min = 0.0),
            integer("min-unions", min = 2.0),
            bool("require-ready"),
            bool("replace-gear"),
            string("kit"), string("armor"), string("offhand"),
            string("respawn"), string("outside"), string("spectator"),
            enum("vehicle", VEHICLES),
            enum("start-vehicle", VEHICLES),
            enum("finish-vehicle", VEHICLES),
            string("checkpoint-vehicles"),
            string("start"), string("finish"), string("checkpoints"),
            bool("require-start"), bool("teleport-start"),
            enum("start-mode", setOf("vote", "judge")),
            decimal("radius", min = 0.1),
            decimal("start-radius", min = 0.1),
            decimal("checkpoint-radius", min = 0.1),
            decimal("finish-radius", min = 0.1),
            decimal("vote-start-percent", min = 0.0, max = 1.0),
            string("judges"),
            integer("seekers", min = 1.0),
            decimal("seeker-ratio", min = 0.05, max = 0.8),
            integer("hide-seconds", min = 0.0),
            integer("round-seconds", min = 0.0),
            integer("timeout-seconds", min = 1.0),
            integer("max-duration-seconds", min = 1.0),
            integer("duration-seconds", min = 1.0),
            bool("found-becomes-seeker"),
            string("seeker-kit"), string("hider-kit"),
            string("reward-source"), string("reward-contract"),
        )
        listOf(
            "free_event",
            "dual_pvp",
            "union_war",
            "run_race",
            "boat_race",
            "horse_race",
            "hide_and_seek",
        ).forEach { id ->
            catalog.register(descriptor(CapabilityKind.MODE, id, parameters = common, strict = false))
        }
    }

    private fun registerFlags(catalog: CapabilityCatalog) {
        val rule = listOf(enum("value", setOf("allow", "deny", "pass"), required = true))
        listOf("pvp", "fly", "vanish", "item_drop", "item_pickup").forEach { id ->
            catalog.register(descriptor(CapabilityKind.FLAG, id, parameters = rule))
        }
        catalog.register(descriptor(
            CapabilityKind.FLAG,
            "commands",
            parameters = listOf(
                enum("value", setOf("allow", "deny", "pass", "allowlist", "blocklist"), required = true),
                enum("mode", setOf("allow", "deny", "pass", "allowlist", "blocklist")),
                string("values", aliases = setOf("commands")),
            ),
        ))
    }

    private fun registerEffects(catalog: CapabilityCatalog) {
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "scale",
            parameters = listOf(decimal("value", true, 0.1, 4.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "potion",
            parameters = listOf(
                string("effect", required = true, aliases = setOf("name")),
                integer("duration-ticks", min = 1.0),
                integer("lease-duration-ticks", min = 1.0),
                integer("amplifier", min = 0.0, max = 255.0),
                bool("ambient"), bool("particles"), bool("icon"),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "walk_speed",
            parameters = listOf(decimal("value", true, -1.0, 1.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "fly_speed",
            parameters = listOf(decimal("value", true, -1.0, 1.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "allow_flight",
            parameters = listOf(
                bool("value", aliases = setOf("allow")),
                integer("lease-duration-ticks", min = 1.0),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "glowing",
            parameters = listOf(bool("value"), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "invisibility_suppression",
            parameters = listOf(integer("lease-duration-ticks", min = 1.0)),
        ))
    }

    private fun registerActions(catalog: CapabilityCatalog) {
        catalog.register(descriptor(CapabilityKind.ACTION, "message", parameters = listOf(string("text", true, setOf("message")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "broadcast", parameters = listOf(string("text", true, setOf("message")))))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "title",
            parameters = listOf(
                string("title", allowBlank = true), string("subtitle", allowBlank = true),
                integer("fade-in", min = 0.0), integer("stay", min = 0.0), integer("fade-out", min = 0.0),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "sound",
            parameters = listOf(
                string("sound", true, setOf("name")),
                decimal("volume", min = 0.0), decimal("pitch", min = 0.0, max = 2.0),
            ),
        ))
        catalog.register(descriptor(CapabilityKind.ACTION, "console_command", CapabilityRisk.HIGH, listOf(string("command", true))))
        catalog.register(descriptor(CapabilityKind.ACTION, "player_command", CapabilityRisk.MEDIUM, listOf(string("command", true))))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "effect_apply",
            parameters = listOf(
                string("effect", true, setOf("effect-type")),
                enum("scope", setOf("while_inside", "while-inside", "timed", "until_mode_end", "until-mode-end")),
            ),
            strict = false,
        ))
        catalog.register(descriptor(CapabilityKind.ACTION, "effect_clear"))
        catalog.register(descriptor(CapabilityKind.ACTION, "teleport", CapabilityRisk.MEDIUM, listOf(string("location", true, setOf("value", "to")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "heal", parameters = listOf(decimal("amount", min = 0.0))))
        catalog.register(descriptor(CapabilityKind.ACTION, "feed", parameters = listOf(integer("amount", min = 0.0, max = 20.0), decimal("saturation", min = 0.0, max = 20.0))))
        catalog.register(descriptor(CapabilityKind.ACTION, "extinguish"))
        catalog.register(descriptor(CapabilityKind.ACTION, "give_item", CapabilityRisk.MEDIUM, listOf(string("item", true, setOf("value")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "take_item", CapabilityRisk.MEDIUM, listOf(string("item", true, setOf("value")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "set_metadata", parameters = listOf(string("key", true), string("value"))))
        catalog.register(descriptor(CapabilityKind.ACTION, "clear_metadata", parameters = listOf(string("key", true))))
        catalog.register(descriptor(CapabilityKind.ACTION, "cleanup_region", risk = CapabilityRisk.MEDIUM))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "mode_command",
            risk = CapabilityRisk.MEDIUM,
            parameters = listOf(enum("command", setOf("ready", "end", "stop"), required = true, aliases = setOf("value"))),
        ))
    }

    private fun registerConditions(catalog: CapabilityCatalog) {
        catalog.register(descriptor(CapabilityKind.CONDITION, "permission", parameters = listOf(string("value", true, setOf("node")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "region", parameters = listOf(string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "mode", parameters = listOf(string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "chance", parameters = listOf(decimal("value", true, 0.0, 100.0, setOf("percent")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "has_union"))
        catalog.register(descriptor(CapabilityKind.CONDITION, "union", parameters = listOf(string("value", true, setOf("id")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "metadata", parameters = listOf(string("key", true), string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "session_metadata", parameters = listOf(string("key", true), string("value", true))))
    }

    private fun descriptor(
        kind: CapabilityKind,
        id: String,
        risk: CapabilityRisk = CapabilityRisk.LOW,
        parameters: List<ParameterDescriptor> = emptyList(),
        requiredPlugins: Set<String> = emptySet(),
        strict: Boolean = true,
    ): CapabilityDescriptor = CapabilityDescriptor(kind, id, CapabilityStatus.STABLE, risk, parameters, requiredPlugins, strict)

    private fun string(
        key: String,
        required: Boolean = false,
        aliases: Set<String> = emptySet(),
        allowBlank: Boolean = false,
    ) = ParameterDescriptor(key, ParameterType.STRING, required, aliases, allowBlank = allowBlank)

    private fun integer(key: String, required: Boolean = false, min: Double? = null, max: Double? = null) =
        ParameterDescriptor(key, ParameterType.INTEGER, required, min = min, max = max)

    private fun decimal(
        key: String,
        required: Boolean = false,
        min: Double? = null,
        max: Double? = null,
        aliases: Set<String> = emptySet(),
    ) = ParameterDescriptor(key, ParameterType.DECIMAL, required, aliases, min = min, max = max)

    private fun bool(key: String, aliases: Set<String> = emptySet()) =
        ParameterDescriptor(key, ParameterType.BOOLEAN, aliases = aliases)

    private fun enum(
        key: String,
        values: Set<String>,
        required: Boolean = false,
        aliases: Set<String> = emptySet(),
    ) = ParameterDescriptor(key, ParameterType.ENUM, required, aliases, values)

    private val VEHICLES = setOf(
        "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot",
        "any", "vehicle", "any_vehicle", "any-vehicle", "boat", "horse", "minecart",
        "pig", "strider", "camel", "donkey", "mule", "llama",
        "pass", "ignore", "any_state", "any-state",
    )
}
