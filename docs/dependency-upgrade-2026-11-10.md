# 依赖升级与兼容审计（2026-11-10）

## 范围与策略

- 目标保持 Minecraft **1.21.1 / NeoForge / Java 21**，不跨 Minecraft 或模组加载器分支；开发默认使用 **21.1.265**，发布范围独立设置为 **[21.1.238,21.2)**，不再因升级开发依赖而抬高最低要求。
- 核实原有 **83 项**版本/文件 ID 配置，更新其中 **45 项**；另外将 Create 传递引入的 FTB Chunks、FTB Teams 显式固定到较新的兼容版本。
- 对原来采用正式版的依赖优先选最新正式版；原来采用 Beta 且没有正式版的 Draconic Evolution 在同一频道更新，不能仅凭 Beta 标签判为不兼容。
- 不把 Maven 仓库最高构建号等同于正式版：Create 采用官方发布对应的 **6.0.10-280**，不选未发布的 6.0.11-320。
- 本轮只更新依赖、增加回归检查和整理开发运行图，不改变 Minecraft 版本，不创建发布 tag。

## 已完成验证

1. 新核心和更新后的兼容 API 编译通过；**335 项 JUnit 测试通过**。
2. 新增 `IntegrationApiLinkageTest`，读取真实编译字节码检查 AE2、AE2WTLib、ExtendedAE/Plus、Lightning Tech、MEGA Cells、ClientSort、Useless、JEI、EMI 的类、方法、字段及明确的反射签名，不初始化游戏类。
3. 完整编译与运行依赖成功解析；Create POM 中可疑的 Architectury 13d.0.8 已由直接声明的 13.0.11 正确覆盖，没有把该注意项误判为实际故障。
4. 在隔离目录启动最小客户端，确认到达欢迎界面，MEST、AE2、AE2WTLib 和 GuideME 成功加载，正常退出。
5. 在隔离目录启动完整 JEI 客户端，更新后的运行模组完成资源加载并正常退出；未修改原有客户端配置或存档。
6. 最终 FTB Chunks 2101.1.23 / Teams 2101.1.12 与 JEI 19.70.0.469 + EMI 1.1.24 的完整客户端组合也成功到达欢迎界面；加载清单确认两个查看器同时存在，并正常退出。
7. 仅含提交内容的独立快照完成全量构建与测试，不依赖用户已有的未提交工作台或性能修改。
8. 最终工作树实际重跑 335 项 Java 测试、8 种运行配置声明检查及 31 项发布脚本模拟测试，全部通过；没有调用真实发布 API。

这是一轮**编译/API存在性/客户端启动冒烟检查**，不是全部功能的行为认证；没有完成世界内终端操作、多人服务器、所有配方转移和远程权限的逐项回归。特别是第三方 Mixin 的运行期语义、反射返回结果和资源包效果，不能仅凭 API 链接测试判定完全正确。

## 联动约束与保留原因

- FTB Ultimine 2101.1.16、Library 2101.1.39、Architectury 13.0.11 联动；Chunks 2101.1.23 要求 Teams >=2101.1.9，因此将旧传递 Teams 2101.1.0 更新为 2101.1.12。
- AE2 19.2.19 仍在 WTLib 19.5.1 声明的 AE2 [19.2.17,20) 范围内。GuideME 更新为 21.1.22。
- JEI 19.70.0.469、WTLib 19.5.1、ExtendedAE 2.2.43、ExtendedAE Plus 1.6.4.1、MEGA Cells 4.11.0、Mekanism 10.7.19.85 等已经是目标频道的新版本，不强行换到其他 MC 分支。
- Lightning Tech 保留 2.1.3（较新 2.1.4-beta3 未选）；Polymorph 保留 1.1.0（较新 1.2.0 为 Beta）；Titanium 保留正式版 4.0.43（4.0.45/50/51/52 为 Beta）。
- Industrial Foregoing 当前已是最新 Beta 3.6.39，保留，不退到较旧的正式版 3.6.38。
- AE2 Utility 和 Data Energistics 原先就处于停用状态，未自动启用，也不将未验证的候选写成已支持；Data Energistics 最新正式版比当前停用的 Beta 更旧，不盲目降级。
- 当前审计未发现必须通过降级解决的已确认 MEST API 故障；若后续世界内操作发现问题，应以日志和复现决定修复或回退，不把所有 WARN 都归因于 MEST。

## 已观察到的上游警告

完整环境存在可选 Mixin 目标缺失、Modern UI 资源路径大小写、FTB 实体图标与部分第三方模型资源警告；更新后的 Allthemodium 还会引用未安装 Lootr 的模型加载器。这些未阻断本轮客户端启动，但不等于相关资源或第三方功能已验证正常。Polymorph 对新 JEI 旧数据包目标的警告也需在世界内配方选择场景继续确认。

## 精确版本表

`*_file` 是 CurseForge 文件 ID，不是版本号；显示名称来自对应文件元数据。保留项的更高 Beta/停用候选不代表选用。

| 配置项 | 升级前 | 当前选择 | 候选显示版本 | 决策 | 证据 |
| --- | --- | --- | --- | --- | --- |
| `advancedae_file` | `8564177` | `8564177` | AdvancedAE-1.6.12-1.21.1 | 保留当前 | [来源](https://api.curseforge.com/v1/mods/1084104/files/8564177) |
| `ae2_crystal_science_file` | `8112039` | `8498888` | AE2 Crystal Science 1.21.1-1.2.1 | 已更新 | [来源](https://api.curseforge.com/v1/mods/1435174/files/8498888) |
| `ae2_import_export_card_file` | `8033654` | `8961599` | ae2importexportcard-1.21.1-1.9.1.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/982512/files/8961599) |
| `ae2_lightning_tech_file` | `9374836` | `9374836` | AE2 Lightning Tech 2.1.3 (neoforge 1.21.1) | 保留当前 | [来源](https://api.curseforge.com/v1/mods/1527395/files/9374836) |
| `ae2_utility_file` | `8698335` | `8698335` | 候选仅供参考：AE2 Utility 1.8.1 | 继续停用；未改变配置 | [来源](https://api.curseforge.com/v1/mods/1521605/files/9538182) |
| `ae2_version` | `19.2.17` | `19.2.19` | 19.2.19 | 已更新 | [来源](https://repo.maven.apache.org/maven2/org/appliedenergistics/appliedenergistics2/maven-metadata.xml) |
| `ae2_wcwt_file` | `8692608` | `9395030` | wcwt-1.3.11.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/1546802/files/9395030) |
| `ae2jei_integration_file` | `7727898` | `7727898` | ae2jeiintegration-1.2.1.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/1074338/files/7727898) |
| `ae2wt_terminals_file` | `8450398` | `8450398` | ae2wtlib-19.5.1.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/459929/files/8450398) |
| `ae2wtlib_version` | `19.5.1` | `19.5.1` | 19.5.1 | 保留当前 | [来源](https://modmaven.dev/de/mari_023/ae2wtlib/maven-metadata.xml) |
| `aeinfinitybooster_file` | `8377914` | `9483395` | 2.1.2 for Neoforge 1.21.1 | 已更新 | [来源](https://api.curseforge.com/v1/mods/559313/files/9483395) |
| `allthecompressed_file` | `7361502` | `7361502` | allthecompressed-1.21.1-4.4.0.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/514045/files/7361502) |
| `allthemodium_file` | `7974403` | `9143638` | allthemodium-3.2.1_mc_1.21.1.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/364466/files/9143638) |
| `apotheosis_file` | `8182695` | `8993983` | Apotheosis 1.21.1-8.9.0 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/apotheosis/files/8993983) |
| `apothic_attributes_file` | `7445079` | `9006294` | Apothic-Attributes 1.21.1-2.11.0 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/apothic-attributes/files/9006294) |
| `apothic_enchanting_file` | `8107222` | `9095862` | Apothic-Enchanting 1.21.1-1.6.4 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/apothic-enchanting/files/9095862) |
| `apothic_spawners_file` | `7492121` | `8469405` | Apothic-Spawners 1.21.1-1.4.0 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/apothic-spawners/files/8469405) |
| `appleskin_file` | `7854442` | `7854442` | appleskin-neoforge-mc1.21-3.0.9.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/appleskin/files/7854442) |
| `applied_create_file` | `7945860` | `9306615` | Applied Create 1.1.8 for NeoForge 1.21.1 | 已更新 | [来源](https://api.curseforge.com/v1/mods/1474758/files/9306615) |
| `applied_flux_file` | `7429565` | `9288549` | AppliedFlux-1.21-2.1.7-neoforge.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/965012/files/9288549) |
| `applied_mekanistics_file` | `7096962` | `7096962` | 1.6.3 | 保留当前 | [来源](https://api.curseforge.com/v1/mods/574300/files/7096962) |
| `architectury_version` | `13.0.8` | `13.0.11` | 13.0.11 | 已更新 | [来源](https://maven.shedaniel.me/dev/architectury/architectury-neoforge/maven-metadata.xml) |
| `ato_file` | `7825464` | `7825464` | alltheores-3.2.0_neoforge_1.21.1.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/405593/files/7825464) |
| `balm_file` | `8424824` | `9406246` | balm-neoforge-1.21.1-21.0.67.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/balm/files/9406246) |
| `bookshelf_file` | `7606240` | `7606240` | bookshelf-neoforge-1.21.1-21.1.81.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/228525/files/7606240) |
| `brandons_core_file` | `7130990` | `7130990` | Brandons Core 1.21.1-3.2.1.309 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/brandons-core/files/7130990) |
| `clientsort_file` | `8172893` | `8978780` | v3.89.2+1.21.1-NeoForge | 已更新 | [来源](https://api.curseforge.com/v1/mods/1049891/files/8978780) |
| `cloth_config_file` | `5729127` | `5729127` | [NeoForge 1.21] v15.0.140 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/cloth-config/files/5729127) |
| `codechicken_lib_file` | `7281427` | `8491811` | CodeChicken Lib 1.21.1-4.6.1.529 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/codechicken-lib-1-8/files/8491811) |
| `configured_file` | `7276577` | `7276577` | Configured 2.6.3 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/configured/files/7276577) |
| `controlling_file` | `6368976` | `6368976` | Controlling-neoforge-1.21.1-19.0.5.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/controlling/files/6368976) |
| `create_version` | `6.0.8-168` | `6.0.10-280` | 6.0.10-280 | 已更新 | [来源](https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1) |
| `cucumber_file` | `8035802` | `8035802` | Cucumber-1.21.1-8.0.16.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/272335/files/8035802) |
| `curios_version` | `9.5.1+1.21.1` | `9.5.1+1.21.1` | 9.5.1+1.21.1 | 保留当前 | [来源](https://maven.theillusivec4.top/top/theillusivec4/curios/curios-neoforge/maven-metadata.xml) |
| `data_energistics_file` | `8841396` | `8841396` | 候选仅供参考：Data Energistics 1.21.1-1.0.0 | 继续停用；未改变配置 | [来源](https://api.curseforge.com/v1/mods/1565514/files/8203941) |
| `draconic_evolution_file` | `7584459` | `8797042` | Draconic Evolution 1.21.1-3.1.4.633 | 已更新；维持原 Beta 频道 | [来源](https://www.curseforge.com/minecraft/mc-mods/draconic-evolution/files/8797042) |
| `emi_version` | `1.1.23+1.21.1` | `1.1.24+1.21.1` | 1.1.24+1.21.1 | 已更新 | [来源](https://maven.terraformersmc.com/releases/dev/emi/emi-neoforge/maven-metadata.xml) |
| `enchantment_descriptions_file` | `8240827` | `8693034` | enchdesc-neoforge-1.21.1-21.1.11.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/enchantment-descriptions/files/8693034) |
| `ex_deorum_file` | `8384464` | `8384464` | Ex Deorum 3.12 | 保留当前 | [来源](https://api.curseforge.com/v1/mods/901420/files/8384464) |
| `extendedae_file` | `9391213` | `9391213` | ExtendedAE-1.21-2.2.43-neoforge.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/892005/files/9391213) |
| `extendedae_plus_file` | `9343779` | `9343779` | ExtendedAE-Plus 1.6.4.1 [NeoForge 1.21.1] | 保留当前 | [来源](https://api.curseforge.com/v1/mods/1337639/files/9343779) |
| `ftb_chunks_version` | `2101.1.1 (transitive)` | `2101.1.23` | 2101.1.23 | 已更新 | [来源](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-neoforge/maven-metadata.xml) |
| `ftb_library_version` | `2101.1.31` | `2101.1.39` | 2101.1.39 | 已更新 | [来源](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/maven-metadata.xml) |
| `ftb_teams_version` | `2101.1.0 (transitive)` | `2101.1.12` | 2101.1.12 | 已更新 | [来源](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-teams-neoforge/maven-metadata.xml) |
| `ftb_ultimine_version` | `2101.1.13` | `2101.1.16` | 2101.1.16 | 已更新 | [来源](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-ultimine-neoforge/maven-metadata.xml) |
| `geckolib_file` | `7707149` | `8893490` | NeoForge 1.21.1 4.9.3 | 已更新 | [来源](https://api.curseforge.com/v1/mods/388172/files/8893490) |
| `glodium_file` | `5821676` | `5821676` | Glodium-1.21-2.2-neoforge.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/957920/files/5821676) |
| `guideme_version` | `21.1.16` | `21.1.22` | 21.1.22 | 已更新 | [来源](https://repo.maven.apache.org/maven2/org/appliedenergistics/guideme/maven-metadata.xml) |
| `hostile_neural_networks_file` | `8321864` | `8525006` | Hostile-Neural-Networks 1.21.1-6.5.1 | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/hostile-neural-networks/files/8525006) |
| `imblocker_file` | `8167852` | `9033584` | IMBlocker-v5.6.2.1-neoforge | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/imblocker/files/9033584) |
| `industrial_foregoing_file` | `8370717` | `8370717` | industrialforegoing-1.21-3.6.39.jar | 保留当前；当前已是最新 Beta | [来源](https://api.curseforge.com/v1/mods/266515/files/8370717) |
| `inventory_essentials_file` | `8362286` | `9035078` | inventoryessentials-neoforge-1.21.1-21.1.20.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/inventory-essentials/files/9035078) |
| `jade_version` | `15.10.0+neoforge` | `15.10.6+neoforge` | 15.10.6+neoforge | 已更新 | [来源](https://api.modrinth.com/v2/project/jade/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D) |
| `jech_file` | `8117474` | `9163765` | jecharacters-1.21.1-neoforge-4.5.30.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters/files/9163765) |
| `jei_version` | `19.70.0.469` | `19.70.0.469` | 19.70.0.469 | 保留当前 | [来源](https://maven.blamejared.com/mezz/jei/jei-1.21.1-neoforge/maven-metadata.xml) |
| `just_dire_things_file` | `7463040` | `7463040` | JustDireThings-1.5.7 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/just-dire-things/files/7463040) |
| `just_dyna_things_file` | `7851999` | `9056387` | 1.20.7.1 | 已更新 | [来源](https://api.curseforge.com/v1/mods/1189855/files/9056387) |
| `kotlinforforge_version` | `5.7.0` | `5.12.0` | 5.12.0 | 已更新 | [来源](https://thedarkcolour.github.io/KotlinForForge/thedarkcolour/kotlinforforge-neoforge/maven-metadata.xml) |
| `ldlib_file` | `8866144` | `9495128` | LDLib2-1.21.1-2.2.46.a-neoforge | 已更新 | [来源](https://api.curseforge.com/v1/mods/626676/files/9495128) |
| `logistics_network_file` | `8381956` | `9432523` | LogisticsNetworks - 1.21.1-1.18.0 | 已更新 | [来源](https://api.curseforge.com/v1/mods/1448257/files/9432523) |
| `mega_cells_file` | `7952621` | `7952621` | 4.11.0 (NeoForge 1.21.1) | 保留当前 | [来源](https://api.curseforge.com/v1/mods/622112/files/7952621) |
| `mekanism_file` | `7904058` | `7904058` | Mekanism-1.21.1-10.7.19.85.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/mekanism/files/7904058) |
| `mekanism_version` | `1.21.1-10.7.19.85` | `1.21.1-10.7.19.85` | 1.21.1-10.7.19.85 | 保留当前 | [来源](https://modmaven.dev/mekanism/Mekanism/maven-metadata.xml) |
| `merequester_file` | `7844592` | `8787722` | MERequester-NeoForge-1.21.1-1.5.0 | 已更新 | [来源](https://api.curseforge.com/v1/mods/688367/files/8787722) |
| `modern_ui_file` | `8206075` | `8206075` | ModernUI-NeoForge-1.21.1-3.13.0.1 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/modern-ui/files/8206075) |
| `modernfix_version` | `4SPHwiDB` | `ZDZENbHH` | ZDZENbHH | 已更新 | [来源](https://api.modrinth.com/v2/project/modernfix/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D) |
| `mouse_tweaks_file` | `5637846` | `5637846` | [1.21+ NeoForge] Mouse Tweaks 2.26.1 | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/mouse-tweaks/files/5637846) |
| `mystical_agradditions_file` | `7802027` | `9041182` | MysticalAgradditions-1.21.1-8.0.15.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/256247/files/9041182) |
| `mystical_agriculture_file` | `8344249` | `9291239` | MysticalAgriculture-1.21.1-8.0.29.jar | 已更新 | [来源](https://api.curseforge.com/v1/mods/246640/files/9291239) |
| `neo_version` | `21.1.238` | `21.1.265` | 21.1.265 | 已更新 | [来源](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml) |
| `parchment_mappings_version` | `2024.11.17` | `2024.11.17` | 2024.11.17 | 保留当前 | [来源](https://maven.parchmentmc.org/org/parchmentmc/data/parchment-1.21.1/maven-metadata.xml) |
| `patchouli_file` | `7730942` | `7730942` | Patchouli-1.21.1-93-NEOFORGE.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/patchouli/files/7730942) |
| `placebo_file` | `6926281` | `9153039` | Placebo 1.21.1-9.9.4 | 已更新 | [来源](https://api.curseforge.com/v1/mods/283644/files/9153039) |
| `pneumaticcraft_version` | `8.2.9+mc1.21.1` | `8.2.24+mc1.21.1` | 8.2.24+mc1.21.1 | 已更新 | [来源](https://modmaven.dev/me/desht/pneumaticcraft/pneumaticcraft-repressurized/maven-metadata.xml) |
| `polymorph_file` | `6794589` | `6794589` | polymorph-neoforge-1.1.0+1.21.1.jar | 保留当前 | [来源](https://api.curseforge.com/v1/mods/388800/files/6794589) |
| `prickle_file` | `6961457` | `6961457` | prickle-neoforge-1.21.1-21.1.11.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/prickle/files/6961457) |
| `productive_bees_file` | `8022994` | `8987967` | productivebees-1.21.1-13.14.0.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/productivebees/files/8987967) |
| `searchables_file` | `5831692` | `5831692` | Searchables-neoforge-1.21.1-1.0.2.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/searchables/files/5831692) |
| `some_useless_things_file` | `8962878` | `9454907` | Useless Mod 1.21.1-2.4.6.5 | 已更新 | [来源](https://api.curseforge.com/v1/mods/1401600/files/9454907) |
| `sophisticated_backpacks_file` | `8845926` | `9524812` | sophisticatedbackpacks-1.21.1-3.26.21.2249.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks/files/9524812) |
| `sophisticated_core_file` | `8838842` | `9524669` | sophisticatedcore-1.21.1-1.5.32.2464.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/sophisticated-core/files/9524669) |
| `sophisticated_storage_file` | `8687896` | `9431872` | sophisticatedstorage-1.21.1-1.6.16.2222.jar | 已更新 | [来源](https://www.curseforge.com/minecraft/mc-mods/sophisticated-storage/files/9431872) |
| `spark_file` | `6225208` | `6225208` | 1.10.124 (NeoForge 1.21.1) | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/spark/files/6225208) |
| `thunderbolt_core_file` | `9363797` | `9363797` | Thunderbolt Core 2.0.3 (neoforge 1.21.1) | 保留当前 | [来源](https://api.curseforge.com/v1/mods/1631756/files/9363797) |
| `titanium_file` | `7951516` | `7951516` | titanium-1.21-4.0.43.jar | 保留当前 | [来源](https://www.curseforge.com/minecraft/mc-mods/titanium/files/7951516) |

## 复验方式

```powershell
.\gradlew.bat build -PmestRuntimeProfile=minimal
.\gradlew.bat build -PmestRuntimeProfile=full
.\gradlew.bat test --tests com.lhy.mest.compat.IntegrationApiLinkageTest --rerun
.\gradlew.bat runClient -PmestRuntimeProfile=minimal
.\gradlew.bat runClient -PmestRuntimeProfile=full
.\gradlew.bat runClientEmi -PmestRuntimeProfile=full
```

本轮实际启动使用本地审计 init 脚本将游戏目录重定向到 `.git/mest-dependency-upgrade/runs/`，不使用项目原有存档。普通运行配置见 [运行配置说明](runtime-profiles.md)，完整行为验证清单见 [发布检查清单](release-checklist.md)。
