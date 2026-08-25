# StateCharge 实服测试

## 基线

1. 在测试服安装最终 shadow JAR、Vault 与生产同款经济 provider，备份插件目录。
2. 准备普通玩家、管理员和收款账户，记录三方余额及 `states.yml`。
3. 分别在 Paper 与 Folia 执行关键路径；控制台不得出现异步实体访问或 region-thread 警告。

## 计费与权限

1. 开启 small，跨过一个完整结算周期后关闭；核对按实际秒数扣款、入账与关闭提示总额。
2. 开启后离线两个周期再上线；离线段不得计费，上线后继续累计。
3. 把保险阈值设到当前余额以上；收费状态应拒绝开启或在结算后自动关闭，免费状态不受影响。
4. 分别只授予 `statecharge.admin.off`、`statecharge.admin.reload`，确认命令与补全互不越权。

## 效果与恢复

1. small、giant 互斥切换，reload 删除/修改正在启用的定义，旧效果必须先移除再按新定义重放。
2. StateCharge 与 Regions 同时控制 scale；按 A→B→关 A→关 B 和 A→B→关 B→关 A 两种顺序测试。
3. 对 flight 重复上一步，并覆盖手动落地、创造/旁观模式与 `statecharge.fly.keep`。
4. 重登、换世界、死亡重生、正常停服重启后，active 状态重放且不多出一层 lease。

## 故障演练

1. 让经济 provider 拒付并抛出受控异常；状态应强制关闭，日志可定位玩家和状态，不重复扣款。
2. 保留有效 `.bak` 后损坏 `states.yml`，启动应从备份恢复。
3. 同时损坏主文件与备份，在运行服执行 reload；命令必须失败，内存中的 active 状态不得清空。
4. 修改 `timing.tick-seconds` 与 `storage.flush-interval-seconds` 后 reload，观察旧任务被取消且新周期生效。

记录服务端版本、步骤、前后余额、日志与最终存档，任何无法解释的差额或残留效果都阻断上线。
