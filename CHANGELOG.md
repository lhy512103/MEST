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
10. Fixed: Memory cards now pull and return upgrade cards from the spliced terminal's built-in network toolkit when no vanilla network tool is carried.
11. Fixed: Crafting pins now prune on close like AE2, the ME list scrollbar reserves the pin row, and the pin cap follows the visible column count.
12. Added: Non-processing encode auto-upload now targets ECO pattern storage and Lightning Tech crafting assemblers, with duplicate detection before insert.
13. Fixed: ECO auto-upload now uses PatternContainer group routing instead of compile-time neoecoae APIs.
14. Fixed: Requesting the provider list no longer crashes servers without ExtendedAE Plus.
15. Fixed: Moving items from the network into the toolkit or trash now uses AE power and only hands out what was actually extracted.
16. Fixed: Remotely opening machines next to pattern providers now respects spawn protection and claim mods, and no longer toggles levers, doors or buttons.
17. Fixed: Network tool upgrade slots now follow the tool stack itself, so cards can no longer be duplicated after moving the tool away.

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
10. 修复：没有手持原版网络工具时，内存卡会从终端自带网络工具面板的升级槽取放升级卡。
11. 修复：合成置顶按原版在关界面后回收，滚动为置顶行预留一页，上限随 ME 列表当前列数变化。
12. 新增：非处理样板编码后自动上传到 ECO 样板库和闪电科技合成装配，插入前做去重。
13. 修复：ECO 自动上传改为走 PatternContainer 分组，不再编译依赖 neoecoae API。
14. 修复：服务端没装 ExtendedAE Plus 时，请求供应器列表不再崩服。
15. 修复：从网络取物放入工具包或垃圾桶改为扣能量，并按实际取出数量放入，不再多给。
16. 修复：远程打开供应器旁的机器时遵守出生点保护和领地模组，不再触发拉杆、门和按钮。
17. 修复：工具包里网络工具的升级槽改为跟随工具本身，移走工具后不能再取出升级卡刷物品。
