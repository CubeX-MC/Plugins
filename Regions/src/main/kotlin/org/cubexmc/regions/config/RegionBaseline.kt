package org.cubexmc.regions.config

import org.cubexmc.config.MigrationPlan

/** 一个受版本管理的数据文件及其当前基线版本。 */
data class BaselineFile(
    val path: String,
    val versionKey: String,
    val version: Int,
)

/**
 * Regions 的数据基线：首个公开版本之后，这里的版本号就是**对外契约**，
 * 任何格式变化都必须同时在对应的 [MigrationPlan] 里加一条迁移步骤（见 `PLAN.md` §5.2）。
 *
 * 这里只保留版本表；迁移的执行、备份、回滚与报告都交给 `cubex-config` 的 `MigrationRunner`——
 * 此前 Regions 自己只做"版本对不上就报错"的校验，没有任何迁移能力。
 */
object RegionBaseline {

    val files: List<BaselineFile> = listOf(
        BaselineFile("config.yml", "config-version", 4),
        BaselineFile("regions.yml", "regions-version", 4),
        BaselineFile("templates.yml", "templates-version", 1),
        BaselineFile("lang/zh_CN.yml", "lang-version", 6),
        BaselineFile("lang/en_US.yml", "lang-version", 6),
    )

    /**
     * 每个基线文件一份迁移计划。
     *
     * 目前**没有任何步骤**：首个公开版本就是起点。缺版本键的文件按 [BaselineFile.version] 补写
     * （首发基线本身），版本更旧的文件因为没有可用步骤而失败——与此前"直接报错"的行为一致，
     * 但现在带备份、回滚与 `MigrationReport`，服主能看清是哪个文件卡住了。
     *
     * 以后改格式：把版本号 +1，并在这里 `addStep(...)`。
     */
    fun plans(): List<MigrationPlan> =
        files.map { baseline ->
            MigrationPlan.yaml("Regions ${baseline.path}", baseline.path)
                .versionKey(baseline.versionKey)
                .missingVersion(baseline.version)
                .targetVersion(baseline.version)
        }
}
