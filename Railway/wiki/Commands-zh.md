# 命令参考

主指令: `/rw`

---

## 线路管理

| 命令 | 描述 |
| :--- | :--- |
| `/rw line create <line_id> <显示名称>` | 创建新线路 |
| `/rw line delete <line_id> confirm` | 删除指定线路 |
| `/rw line list` | 列出所有线路 |
| `/rw line rename <line_id> <新名称>` | 重命名线路 |
| `/rw line setcolor <line_id> <颜色代码>` | 设置线路颜色 |
| `/rw line setterminus <line_id> <名称>` | 设置终点方向描述 |
| `/rw line setmaxspeed <line_id> <速度>` | 设置线路最大运行速度 |
| `/rw line setprice <line_id> <价格>` | 设置单一定价 |
| `/rw line setprice <line_id> flat <基础价>` | 设置固定票价 |
| `/rw line setprice <line_id> distance <基础价> <每方块> [封顶]` | 按距离计价 |
| `/rw line setprice <line_id> interval <基础价> <每站> [封顶]` | 按站数计价 |
| `/rw line setprice reset <line_id>` | 重置为默认单一票价 |
| `/rw line priceinfo <line_id>` | 查看线路定价详情 |
| `/rw line setstatus <line_id> <normal&#124;suspended&#124;maintenance>` | 设置运营状态 |
| `/rw line addstop <line_id> <stop_id> [位置索引]` | 将停靠区添加到线路 |
| `/rw line delstop <line_id> <stop_id>` | 从线路中移除停靠区 |
| `/rw line stops <line_id>` | 查看线路的所有停靠区 |
| `/rw line addportal <line_id> <portal_id>` | 允许线路使用传送门 |
| `/rw line delportal <line_id> <portal_id>` | 从线路中移除传送门 |
| `/rw line portals <line_id>` | 查看线路启用的传送门 |
| `/rw line info <line_id>` | 查看线路详细信息及权限 |
| `/rw line recordroute <line_id>` | 录制或保存路线轨迹 |
| `/rw line clearroute <line_id> confirm` | 清除已录制的路线 |
| `/rw line routeinfo <line_id>` | 查看路线录制状态 |
| `/rw line protect <line_id> <on&#124;off>` | 启停铁轨保护 |
| `/rw line clonereverse <源ID> <新ID> [后缀]` | 反向克隆线路 |
| `/rw line trust <line_id> <玩家>` | 授予线路管理权限 |
| `/rw line untrust <line_id> <玩家>` | 移除线路管理权限 |
| `/rw line owner <line_id> <玩家>` | 转移线路所有权 |

## 停靠区管理

| 命令 | 描述 |
| :--- | :--- |
| `/rw stop create <stop_id> <显示名称>` | 选区后创建新停靠区 |
| `/rw stop delete <stop_id> confirm` | 删除停靠区及其所有配置 |
| `/rw stop list` | 列出所有停靠区 |
| `/rw stop rename <stop_id> <新名称>` | 重命名停靠区 |
| `/rw stop info <stop_id>` | 查看停靠区详细信息 |
| `/rw stop setcorners <stop_id>` | 更新空间对角点 |
| `/rw stop setpoint [stopId] [朝向角度]` | 设置精确停靠点 |
| `/rw stop addtransfer <stop_id> <换乘线路ID>` | 添加可换乘线路 |
| `/rw stop deltransfer <stop_id> <换乘线路ID>` | 移除可换乘线路 |
| `/rw stop listtransfers <stop_id>` | 查看可换乘线路 |
| `/rw stop settitle <stop_id> <类型> <键> <文本内容>` | 设置自定义 Title 显示 |
| `/rw stop deltitle <stop_id> <类型> [键]` | 删除自定义 Title 设置 |
| `/rw stop listtitles <stop_id>` | 查看自定义 Title 配置 |
| `/rw stop tp <stop_id>` | 传送到指定停靠区 |
| `/rw stop trust <stop_id> <玩家>` | 授予停靠区管理权限 |
| `/rw stop untrust <stop_id> <玩家>` | 移除停靠区管理权限 |
| `/rw stop owner <stop_id> <玩家>` | 转移停靠区所有权 |
| `/rw stop link <allow&#124;deny> <stop_id> <line_id>` | 管理线路接入白名单 |

## 传送门管理

| 命令 | 描述 |
| :--- | :--- |
| `/rw portal create <portal_id>` | 创建新的矿车传送门入口 |
| `/rw portal setdest <portal_id>` | 设置传送门目标位置 |
| `/rw portal link <portal_id_1> <portal_id_2>` | 双向配对两个传送门 |
| `/rw portal delete <portal_id> confirm` | 删除传送门 |
| `/rw portal list` | 列出所有传送门 |
| `/rw portal trust <portal_id> <玩家>` | 授予传送门管理权限 |
| `/rw portal untrust <portal_id> <玩家>` | 移除传送门管理权限 |
| `/rw portal owner <portal_id> <玩家>` | 转移传送门所有权 |
| `/rw portal reload` | 重新加载传送门配置 |

## 列车服务管理

| 命令 | 描述 |
| :--- | :--- |
| `/rw line setheadway <线路ID> <秒>` | 设置自动发车间隔 |
| `/rw line setdwell <线路ID> <tick>` | 设置停站时间（20 tick=1秒） |
| `/rw line setcarts <线路ID> <数量>` | 设置编组车厢数（1-32） |
| `/rw line enableservice <线路ID>` | 启用列车自动服务 |
| `/rw line disableservice <线路ID>` | 禁用列车自动服务 |
| `/rw line serviceinfo <线路ID>` | 查看线路服务配置 |

## 系统管理

| 命令 | 描述 |
| :--- | :--- |
| `/rw gui` | 打开图形管理界面 |
| `/rw reload` | 重新加载配置和数据文件 |
