# Wake Up

A NeoForge mod for Minecraft 1.21.11. Sleeping through the night has a chance to trap
the whole server in a **timed dream**; when the dream ends, the server **rolls back** to
the moment of waking up. Nested dreams (梦中梦 / dream-within-a-dream) are supported via a
snapshot stack.

## 玩法 / Gameplay

1. 玩家上床，跳过夜晚（多人时需**所有玩家都入睡**，见下方"多人"）。
2. 起床那一刻，以概率 `dreamChance` 触发做梦：**服务器保存"情境"快照**，进入第 1 层梦境，启动随机时长计时器。
3. 梦境中一切正常游玩，但改动都是"临时的"。
4. 计时结束 → 苏醒 → 服务器**回滚到快照**（世界时间/天气 + 所有玩家状态）。
5. 梦中再次睡觉并跳过夜晚 → 可进入**更深一层**（快照栈 +1，外层计时暂停）。
6. 苏醒按"后进先出"逐层弹出：先醒最深层 → 回滚该层快照 → 继续上一层，直到全部醒完。

## 配置 / Config

配置文件生成在 `config/wakeup-server.toml`：

| 键 | 默认 | 说明 |
|----|------|------|
| `dreamChance` | `0.5` | 触发做梦的概率（0.0–1.0） |
| `dreamMinSeconds` | `300` | 梦境最短时长（秒） |
| `dreamMaxSeconds` | `1200` | 梦境最长时长（秒），每次在 [min,max] 内随机 |
| `wakeOnDeath` | `true` | 全员同时死亡时是否醒梦（单机=该玩家死亡） |

## 多人 / Multiplayer

- 多人时依赖原版默认 `playersSleepingPercentage=100`（所有玩家入睡才跳过夜晚）。
- 梦境是**服务器级**的：快照包含世界状态 + 所有在线玩家 + 所有已加载实体。
- 梦中若**所有玩家退出**，倒计时**暂停**；有玩家重连后继续。
- 梦中**单个玩家死亡不会醒梦**（按正常死亡处理）；只有**所有在线玩家同时死亡**才醒梦回滚（单机即"该玩家死亡"）。
- 若某玩家在梦结束时处于离线，会在其下次登录时补做回滚。

## 测试命令 / Test Commands

> 仅**创造模式**可用（服务端控制台不受限）。

| 命令 | 作用 |
|------|------|
| `/wakeup status` | 查看是否做梦、层数、剩余 tick |
| `/wakeup force` | 开关"下次睡觉必定做梦"（再执行一次关闭） |
| `/wakeup dream` | 立即进梦（拍快照 + 进入一层梦境，可重复执行测嵌套） |
| `/wakeup wake` | 立即醒梦（弹栈 + 回滚） |

## 构建 / Build

要求：JDK 21。

```bash
gradlew build          # Windows 用 gradlew.bat
```

产物在 `build/libs/wakeup-1.0.0.jar`。

开发运行：

```bash
gradlew runClient      # 启动客户端（单机测试）
gradlew runServer      # 启动服务端
```

## 代码结构 / Code layout

```
src/main/java/com/wakeup/wakeup/
  WakeUp.java         # @Mod 入口，注册 config
  WakeUpConfig.java   # 配置项（NeoForge Config API）
  ServerSnapshot.java # 捕获/恢复整服"情境"（世界 + 所有玩家）
  WakeUpSavedData.java# 持久化状态：梦境栈 + 离线回滚队列（随世界存档保存）
  DreamManager.java   # 核心逻辑：进梦/计时/苏醒/嵌套栈
  DreamEvents.java    # 事件接线：睡眠、tick、死亡、登录、服务器启动
```

## 已知注意 / Notes

- 本工程已针对 **NeoForge 21.11.45（Minecraft 1.21.11）** 编译通过（`gradlew build` 成功，产物 `build/libs/wakeup-1.0.0.jar`）。
- 1.21.6+ 起 NBT 与若干命名发生变更，代码已按新 API 编写：`ResourceLocation→Identifier`、
  `CompoundTag.getXxx` 返回 `Optional`（改用 `getXxxOr`）、`ItemStack` 序列化走 `CODEC`、
  `SavedData` 走 `SavedDataType`、`kill()` 需 `ServerLevel`、`teleportTo` 为 8 参。
- 多人时依赖原版默认 `playersSleepingPercentage=100`（"所有玩家入睡才跳过夜晚"），模组不再显式改写该 gamerule。
