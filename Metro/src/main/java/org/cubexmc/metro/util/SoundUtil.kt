package org.cubexmc.metro.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.bukkit.Instrument
import org.bukkit.Note
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * 声音工具类，用于处理音符序列的播放
 */
object SoundUtil {
    /**
     * 为指定玩家播放音符序列
     *
     * @param plugin 插件实例
     * @param player 目标玩家
     * @param noteSequence 音符序列
     */
    @JvmStatic
    fun playNoteSequence(plugin: JavaPlugin, player: Player?, noteSequence: List<String>?) {
        playNoteSequence(plugin, player, noteSequence, 0)
    }

    /**
     * 为指定玩家播放音符序列，带初始延迟
     *
     * @param plugin 插件实例
     * @param player 目标玩家
     * @param noteSequence 音符序列
     * @param initialDelay 整个音符序列的初始延迟（ticks）
     */
    @JvmStatic
    fun playNoteSequence(plugin: JavaPlugin, player: Player?, noteSequence: List<String>?, initialDelay: Int) {
        if (player == null || !player.isOnline || noteSequence == null || noteSequence.isEmpty()) {
            return
        }

        var totalDelay = initialDelay.toLong() // 加入初始延迟

        for (noteData in noteSequence) {
            val parts = noteData.split(",")
            if (parts.size < 4) {
                continue // 跳过格式不正确的音符
            }

            val type = parts[0].trim()

            try {
                val tone = parts[1].trim().toInt()
                val volume = parts[2].trim().toFloat()
                val instrumentName = parts[3].trim()

                // 获取延迟时间（如果提供）
                val delay = if (parts.size > 4) parts[4].trim().toInt() else 0
                totalDelay += delay.toLong()

                // 使用延迟任务播放音符
                SchedulerUtil.regionRun(
                    plugin,
                    player.location,
                    Runnable {
                        if ("NOTE" == type) {
                            playNote(player, tone, volume, instrumentName)
                        } else if ("CUSTOM" == type) {
                            // 自定义声音的播放逻辑，如果需要
                            player.playSound(player.location, instrumentName, volume, getNoteFrequency(tone))
                        }
                    },
                    totalDelay,
                    -1,
                )
            } catch (e: NumberFormatException) {
                // 忽略格式不正确的音符
            }
        }
    }

    /**
     * 为玩家播放单个音符
     */
    private fun playNote(player: Player, tone: Int, volume: Float, instrumentName: String) {
        val instrument = getInstrument(instrumentName)
        val note = getNote(tone)

        if (instrument != null && note != null) {
            player.playNote(player.location, instrument, note)
        }
    }

    /**
     * 获取音符盒乐器类型
     */
    private fun getInstrument(name: String): Instrument? =
        try {
            Instrument.valueOf(name)
        } catch (e: IllegalArgumentException) {
            Instrument.PIANO // 默认为钢琴
        }

    /**
     * 根据音高获取音符
     */
    private fun getNote(tone: Int): Note? =
        try {
            // 确保音高在有效范围内
            Note(max(0, min(tone, 24)))
        } catch (e: IllegalArgumentException) {
            Note(12) // 默认为中音C
        }

    /**
     * 根据音高获取频率（用于自定义声音）
     */
    private fun getNoteFrequency(tone: Int): Float =
        2.0.pow((tone - 12) / 12.0).toFloat()
}
