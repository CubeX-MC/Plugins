# StateCharge

付费限时状态插件:玩家用 Vault 货币购买**限时变小 / 变大 / 飞行**等状态,在线计时、可叠加续费。
状态由 `config.yml` 配置驱动——服主可增删状态、改价格时长,无需改代码。

- 平台:Paper 1.21.x(体型用 1.20.5+ 属性 API `Attribute.SCALE`,无需 ProtocolLib);Folia 支持
- 经济:Vault(硬依赖,无 provider 时插件自动禁用)
- 语言:`lang/zh_CN.yml` / `lang/en_US.yml`(MiniMessage)

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/statecharge list` | 查看可购买的状态 | `statecharge.use` |
| `/statecharge status` | 查看生效中的状态与剩余时间 | `statecharge.use` |
| `/statecharge buy <状态> [份数]` | 购买(默认 1 份) | `statecharge.use` + 该状态配置的 permission(若有) |
| `/statecharge admin give <玩家> <状态> <秒数>` | 免费发放(叠加) | `statecharge.admin.give` |
| `/statecharge admin clear <玩家> [状态]` | 清除状态(缺省全部) | `statecharge.admin.clear` |
| `/statecharge admin reload` | 重载配置/语言/数据 | `statecharge.admin.reload` |

别名:`/sc`。

## 配置(config.yml)

```yaml
states:
  small:                          # 状态 id,用于命令与权限
    enabled: true                 # false = 不可购买(admin give 仍可用)
    display: "变小"               # 展示名,纯文本
    price: 100.0                  # 每份价格
    unit-seconds: 1800            # 每份时长(秒)
    max-stack-seconds: 21600      # 累计上限,0 = 不限
    permission: ""                # 空 = 不检查购买权限
    conflict-group: scale         # 同组互斥;显式 "" 关闭
    effect:
      type: scale                 # scale(参数 scale: 0.1..16.0) / fly(参数 auto-start)
      scale: 0.5
```

- 计时:在线每秒扣 1 秒;离线暂停、时长保留;重复购买叠加(受 max-stack 限制)。
- 到期提醒:`notifications.expiry-warning-seconds` 阈值聊天提示 + 最后 N 秒 actionbar 倒计时。
- 数据:`plugins/StateCharge/states.yml`(自动备份 `states.yml.bak`)。

## 设计

详见 [DESIGN.md](DESIGN.md)。范围外(v1):GUI、BossBar、PlaceholderAPI、MySQL、现实时间倒计时。
