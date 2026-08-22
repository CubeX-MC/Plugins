package org.cubexmc.economy

import java.util.Locale
import java.util.UUID

/**
 * `economy.account` 的解析结果 —— 玩家消费掉的钱要转到哪里。
 *
 * CubeX 的经济是**内循环**的:插件收走的钱不销毁,而是转进服务器的银行账户。
 * 这个类型只负责把配置里那一行字符串变成一个明确的目标,不碰 Vault、不碰 Bukkit,
 * 因此可以脱离服务器单测 —— 配置解析写错的代价是"钱进了错的账户",必须有测试兜着。
 *
 * 支持的写法(前缀不区分大小写):
 *
 * | 配置值 | 含义 |
 * |---|---|
 * | 空 / 缺省 | [None] —— 扣款后不入账,货币被销毁(接入本模块之前各插件的旧行为) |
 * | `uuid:1a2b...` 或裸 UUID | [PlayerUuid] —— 按 UUID 精确指定玩家账户 |
 * | `name:cubex_bank` | [RawName] —— 把名字**原样交给 Vault**,由经济插件自己认账户 |
 * | `cubex_bank` | [PlayerName] —— 先解析成 UUID 再入账 |
 * | `bank:CubeXBank` | [Bank] —— Vault 的 bank 账户,需要经济插件支持 bank |
 *
 * ### 三种"玩家账户"写法怎么选
 *
 * - [PlayerUuid] **最稳**:没有任何解析,不依赖 profile 缓存,也不会因为改名而漂移。
 *   只要能拿到 UUID 就用它。
 * - [RawName] 适合**从不登录的虚拟银行账户**:名字直接进 Vault 的 name 重载,
 *   由经济插件用它自己的 name↔账户映射去认(EssentialsX / CMI 都维护这样一张表)。
 *   代价是我们无从核对钱进了哪个账户 —— 以经济插件的判断为准。
 * - [PlayerName] 会先把名字解析成 UUID 再入账,好处是启动日志能打出**具体哪个账户**收钱,
 *   坏处是要依赖服务器的 profile 缓存 / profile 查询(见 [OfflinePlayerLookup])。
 */
sealed interface EconomyAccount {

    /** 人类可读的短标签,用于日志与 `/<插件> 状态` 之类的输出。 */
    fun label(): String

    /** 不入账:扣款后货币直接消失。 */
    data object None : EconomyAccount {
        override fun label(): String = "none (money is destroyed)"
    }

    /** 按 UUID 精确指定的玩家账户。 */
    data class PlayerUuid(val uuid: UUID) : EconomyAccount {
        override fun label(): String = "player uuid:$uuid"
    }

    /** 名字原样交给 Vault 的 name 重载,由经济插件认账户。 */
    data class RawName(val name: String) : EconomyAccount {
        override fun label(): String = "economy account name:$name"
    }

    /** 先解析成 UUID 再入账的玩家账户。解析路径见 [OfflinePlayerLookup]。 */
    data class PlayerName(val name: String) : EconomyAccount {
        override fun label(): String = "player $name"
    }

    /** Vault 的 bank 账户。 */
    data class Bank(val name: String) : EconomyAccount {
        override fun label(): String = "bank $name"
    }

    companion object {
        const val UUID_PREFIX: String = "uuid:"
        const val NAME_PREFIX: String = "name:"
        const val BANK_PREFIX: String = "bank:"

        /** 玩家名的宽松上限:真实 MC 名最长 16,留出余量但挡住"整句话粘进配置"的手滑。 */
        private const val MAX_PLAYER_NAME_LENGTH = 64

        /**
         * 解析一行 `economy.account` 配置。
         *
         * @throws IllegalArgumentException 配置值有语法错误(UUID 解析不了、账户名为空、玩家名带空格)。
         *   调用方应当捕获它、记一条 SEVERE、然后按 [None] 继续 —— 配置写错不该让整个插件起不来,
         *   但也绝不能猜一个账户把钱转过去。
         */
        @JvmStatic
        fun parse(raw: String?): EconomyAccount {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return None

            val lower = value.lowercase(Locale.ROOT)
            if (lower.startsWith(UUID_PREFIX)) {
                return PlayerUuid(requireUuid(value.substring(UUID_PREFIX.length).trim(), value))
            }
            if (lower.startsWith(NAME_PREFIX)) {
                return RawName(requirePlayerName(value.substring(NAME_PREFIX.length).trim(), value))
            }
            if (lower.startsWith(BANK_PREFIX)) {
                val name = value.substring(BANK_PREFIX.length).trim()
                require(name.isNotEmpty()) { "economy.account 的 bank 名不能为空: '$raw'" }
                return Bank(name)
            }

            // 裸 UUID:玩家名里不可能出现连字符,所以这个形态没有歧义。
            parseUuidOrNull(value)?.let { return PlayerUuid(it) }

            return PlayerName(requirePlayerName(value, raw.orEmpty()))
        }

        private fun requirePlayerName(candidate: String, raw: String): String {
            require(candidate.isNotEmpty()) { "economy.account 的账户名不能为空: '$raw'" }
            require(candidate.none(Char::isWhitespace)) {
                "economy.account 的玩家名不能含空格;要指定 Vault bank 请写 'bank:<名字>': '$raw'"
            }
            require(candidate.length <= MAX_PLAYER_NAME_LENGTH) {
                "economy.account 的玩家名过长(上限 $MAX_PLAYER_NAME_LENGTH): '$raw'"
            }
            return candidate
        }

        private fun requireUuid(candidate: String, raw: String): UUID =
            parseUuidOrNull(candidate)
                ?: throw IllegalArgumentException("economy.account 的 UUID 解析失败: '$raw'")

        private fun parseUuidOrNull(candidate: String): UUID? = try {
            // UUID.fromString 会接受 "1-2-3-4-5" 这种缺位形态,长度检查把它挡在外面。
            if (candidate.length == 36) UUID.fromString(candidate) else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
