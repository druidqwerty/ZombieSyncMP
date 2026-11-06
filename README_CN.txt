ZombieSync MP [B42] — 多人联机平滑僵尸同步
==========================================
版本 1.1.1。源代码 (MIT): https://github.com/druidqwerty/ZombieSyncMP
Steam 创意工坊: https://steamcommunity.com/sharedfiles/filedetails/?id=3827946115

模组作用
--------
在原版 Build 42 多人联机中，每个僵尸都由某一名玩家（其“模拟端/主机”）负责模拟，其他玩家看到的都是副本。原版中这个副本移动一卡一顿：每 0.2 秒才收到一次更新，僵尸朝着过时的预测点移动，落后超过 3 格时直接瞬移（还会跳到格子角落），每次收到数据包时头部和身体还会猛然转向。此外原版还有一个 Bug：如果你画面里其他玩家的副本短暂倒地，你屏幕上的僵尸就会蹲下“进食”，而真正的僵尸其实还在继续追逐，随后又突然瞬移回原位。

安装 ZombieSync MP 后：
  * 远程僵尸沿着它在模拟端真正走过的路径绘制，保持稳定的约 0.15–0.2 秒延迟（基于发送时间戳）；
  * 在数据包之间沿着平滑曲线移动，预测偏差通过弹簧平滑融合，没有抽搐与瞬移；墙壁与碰撞保持原样；
  * 转身平滑自然，不再每次收到包都突然扭头，朝向与模拟端完全一致（包括啃咬时）；
  * 远程僵尸仅在模拟端发起啃咬时才啃咬，仅在模拟端判定进食时才开始进食；
  * 附近的玩家与僵尸使用同一套时钟绘制：位置每秒发送 10 次（原版仅在跨格、8 方向转身或每 0.6 秒发送），因此僵尸啃咬的是你画面中看到的玩家，而不是他面前的空气；
  * 遇到网络波动与服务器卡顿（保存世界、加载新地图区域）时，僵尸会先放慢动作并在之后平滑追上，而不是原地冻结再瞬移；若卡顿超过缓冲上限（默认 1.5 秒），每只移动过的僵尸只进行一次干净的位置跳转；
  * 由你模拟的僵尸发送频率提高至 4 倍（每 50 毫秒一次，原版为 200 毫秒），并受上行带宽上限保护（默认 256 KB/s，在约 250 只僵尸以内保持 50 毫秒满速，超出后间隔平滑放宽）。

倒地、受击、翻越围栏与窗户、起身均使用游戏原生动画；模组在此期间仅记录数据，动画结束后再平滑接管。

免费开源。代码由 AI 编写，方向把控、测试与时间投入由我完成。如果本模组改善了你的游戏体验，欢迎在 Boosty 支持：https://boosty.to/lrgpp

1.1.1 更新内容
--------------
* 新增简体中文 (CN) 与繁体中文 (CH) 本地化支持：涵盖主机沙盒选项（Sandbox > ZombieSync MP）与个人模组选项（选项 > 模组 > ZombieSync MP）的所有设置与提示说明，并新增中文说明文档 README_CN.txt 与 Steam 创意工坊中文页面。
* 修正 42/mod.info 中的英文描述（发送频率为默认的 4 倍，即 50 毫秒对比原版 200 毫秒）。
* ZombieSyncMP.jar 与 1.1.0 完全一致（sha256: 53550c20...），已批准过 1.1.0 的玩家无需在 ZombieBuddy 中重新授权。

1.1.0 更新内容
--------------
* 修复了好友通过互联网加入主机时僵尸来回瞬移的问题：
  - 服务器卡顿结束后会先转发卡顿开始时积压的旧数据包（已过时 2.5–4 秒），1.1.0 会保留真实时间戳并丢弃过旧的数据包，利用慢动作度过数据空窗期；超过缓冲上限的长卡顿结束后仅进行一次干净跳转。
* 全局设置移至主机沙盒选项（ZombieSync MP 页面）：全服玩家一致，管理员可在运行中的游戏里直接修改（管理面板 > 沙盒选项），2 秒内自动生效。个人选项（选项 > 模组）仅保留统计日志开关。
* Project A-Life 的 NPC 保持原版同步。

安装要求与方法
--------------
* 需要 Build 42.21+ 以及 ZombieBuddy 2.3.4+（Java 模组加载器）。
* 客户端 Java 模组：【每位玩家】均需安装，且必须加入服务器的模组列表。
* Steam 创意工坊：订阅 ZombieBuddy 与 ZombieSync MP。在服务器配置中将 ZombieSyncMP 加入 Mods=（排在 ZombieBuddy 之后），将 3827946115 加入 WorkshopItems=。
* 手动安装：从 GitHub Releases 下载 ZombieSyncMP_1.1.1.zip，删除旧的 ZombieSyncMP 文件夹后解压至 %UserProfile%\Zomboid\mods\。
* ZombieBuddy 首次加载时会询问一次是否允许新的 Java 模组 ZombieSyncMP，请点击允许。

配置说明
--------
全服设置（所有玩家一致）：建立服务器 > 服务器设置 > 编辑 > Sandbox > ZombieSync MP（专用服务器：ServerName_SandboxVars.lua 中的 SandboxVars.ZombieSyncMP）。管理员可在运行中的游戏里随时修改（管理面板 > 沙盒选项），无需重启：
  * 启用 ZombieSync (默认开启)：关闭后所有玩家使用原版同步。
  * 平滑模式：均衡 Balanced（默认，延迟约 0.2 秒）、平滑 Smooth（更顺滑，延迟增加 30–50 毫秒）、实时 Realtime（延迟最低，预测更多）。
  * 僵尸发送间隔（毫秒）：默认 50（原版为 200）。
  * 每位玩家的僵尸上行带宽上限（KB/s）：默认 256。
  * 网络波动与卡顿缓冲上限（毫秒）：默认 1500。
  * 按僵尸时钟同步绘制其他玩家、攻击动作跟随模拟端、仅在模拟端判定进食时才进食：默认开启。

个人设置（游戏选项 > 模组 > ZombieSync MP）：仅包含是否将统计信息写入 console.txt 及输出间隔。

已知问题
--------
* Tripping Zombies：多人联机中每位玩家的客户端各自独立判定绊倒，导致不同玩家看到的摔倒不一致，倒地副本每 3 格被原版瞬移一次（不装 ZombieSync 也一样）。可通过安装附属补丁 Tripping Zombies MP Fix 解决（由模拟该僵尸的客户端统一判定并同步给所有玩家）：
  https://steamcommunity.com/sharedfiles/filedetails/?id=3829034899

我的模组
--------
* SmoothRide MP — 多人联机平滑载具同步：https://steamcommunity.com/sharedfiles/filedetails/?id=3827367565
* ZombieSync MP — 多人联机平滑僵尸同步（本模组）。
* Tripping Zombies MP Fix — 多人联机同步僵尸绊倒：https://steamcommunity.com/sharedfiles/filedetails/?id=3829034899
* Wandering Hordes Performance Fix — 消除 Wandering Hordes 引起的存档卡顿与客户端掉帧：https://steamcommunity.com/sharedfiles/filedetails/?id=3829140377
* Download Unstuck — 修复主机启动时创意工坊下载卡住的问题：https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
* A-Life: Reactive Survivors — Project A-Life 幸存者 NPC 提前自卫反击：https://steamcommunity.com/sharedfiles/filedetails/?id=3829332775
* A-Life: Caravans — Project A-Life 派系车队与道路贸易：https://steamcommunity.com/sharedfiles/filedetails/?id=3829571361

© 2026 druidqwerty. MIT 许可证。
