# Changelog

## v0.0.2.6

### English

1. Added: Some Useless Things and Sophisticated Backpacks as development runtime dependencies.
2. Fixed: Bumped Data Energistics to 3.2.2 so Useless Mod no longer crashes while prewarming alloy-furnace recipes.
3. Added: LDLib2 as a runtime dependency required by Data Energistics 3.2.2.
4. Fixed: Bumped Sophisticated Storage to 1.5.91 so it matches Sophisticated Core 1.5.1.
5. Fixed: Skip toolkit hand override while ServerPlayer is still constructing, so Useless Mod can no longer NPE join with invalid player data.
6. Fixed: Same join-time spectator NPE on the client LocalPlayer, which crashed Camera.setup after login.
7. Fixed: Extra-bar right-click equip is applied only on the server, so swapping diamond leggings onto Advanced AE quantum leggings no longer leaves quantum pieces in both slots.
8. Fixed: Pattern-encoding slots now use AE2's craftable query and raise the "+" overlay in z, so 3D block ingredients no longer hide the marker.
9. Fixed: Dev client now uses JEI 19.51 and skips AE2 Utility, so ExtendedAE Plus and AE2 Utility JEI mixins no longer crash on enter-world.

### 中文

1. 新增：开发运行时加入 Some Useless Things 与 Sophisticated Backpacks。
2. 修复：将 Data Energistics 升到 3.2.2，避免 Useless Mod 合金炉配方预热崩溃。
3. 新增：补上 Data Energistics 3.2.2 所需的 LDLib2 运行时依赖。
4. 修复：将 Sophisticated Storage 升到 1.5.91，对齐 Sophisticated Core 1.5.1 的 API。
5. 修复：ServerPlayer 构造未完成时不再覆盖主手，避免 Useless Mod 进档空指针并提示无效的玩家数据。
6. 修复：客户端 LocalPlayer 构造时同样的旁观检查空指针，进档后 Camera.setup 崩溃。
7. 修复：扩展栏右键装备不再客户端预测，避免钻石护腿换上量子胫甲后手上和身上都变成量子胫甲。
8. 修复：样板编码槽改用 AE2 原版可合成查询，并把 “+” 抬到物品模型之上，避免方块类材料挡住角标。
9. 修复：开发客户端改用 JEI 19.51 并暂时去掉 AE2 Utility，避免 Plus 与 Utility 的 JEI mixin 进档即崩。
