# 发布验证清单 / Release checklist

本清单区分自动化验证与游戏内验证；构建通过不能代替领地权限、多人操作、材质或缺少可选模组的实际启动检查。
Build success is not a substitute for in-game permission, multiplayer, rendering or optional-mod checks.

## 1. 记录版本 / Record versions

- [ ] 记录 Minecraft、NeoForge、Java、AE2、AE2WTLib 与参与测试的附属模组精确版本。
- [ ] 使用新建的临时世界，不把完整整合包存档放入最小依赖环境。
- [ ] 核对 `main` 的提交号、构建产物版本与实际加载的 JAR，避免旧版本残留。

## 2. 自动化检查 / Automated checks

运行配置与八种查看器组合见 [运行配置说明](runtime-profiles.md)。

```powershell
.\gradlew.bat build -PmestRuntimeProfile=full
.\gradlew.bat build -PmestRuntimeProfile=minimal
.\gradlew.bat test --rerun
```

- [ ] 全部测试通过，记录测试数量与失败数。
- [ ] full/minimal × 无查看器/JEI/EMI/双查看器的声明检查通过。
- [ ] 检查解析后的最小运行依赖与加载器实际模组列表，不仅检查直接依赖声明。
- [ ] 检查中英文翻译键、配方进度路径和资源引用。

## 3. 可选依赖与终端 / Optional mods and terminals

- [ ] 不安装 ExtendedAE Plus 时，在普通无线合成终端搜索栏输入，确认不崩溃（#10）。
- [ ] 不安装闪电科技时打开拼接终端；安装受支持版本后测试频率卡与自动连接按钮（#7、#8）。
- [ ] 分别启动最小客户端和专用服务器，确认无客户端类误加载与 Mixin 注入错误。
- [ ] JEI/EMI 配方转移到合成、处理、锻造、切石模式均正确。

## 4. 物品与快捷键 / Items and hotkeys

- [ ] 仅携带拼接终端时，“将手中物品存入 ME 网络”按上游规则处理主手（#5）。
- [ ] 同时携带无线合成终端与拼接终端时，保留原终端处理优先级；网络满、部分接收与零接收不丢物品。
- [ ] 空主手、受选取替换保护的物品、失联终端均不误收纳。
- [ ] 网络及副手已满且垃圾桶隐藏时，Shift 点击、区域移动与拖拽不进入垃圾槽（#6）。
- [ ] 通过预设、编辑器或自动隐藏关闭垃圾面板后保持已有内容，只有显式清空操作才删除内容。
- [ ] 多人同时取放驱动器元件，旧列表、网络变化或元件替换不会复制或丢失物品。

## 5. 布局与扩展面板 / Layout and extension panels

- [ ] 拖动、缩放、拼接、分离、固定、浮窗覆盖和 GUI 缩放符合预期。
- [ ] 编辑器确认、取消、返回终端和窗口缩放不重复创建或泄漏扩展面板资源。
- [ ] 扩展面板开启、关闭、可见性、客户端 tick 与键盘回调符合契约；隐藏面板不接收按键。
- [ ] 布局/预设导入导出往返一致，坏文件和超大文件拒绝，旧备份保留。
- [ ] 主布局保存与预设保存失败后可重试；隔离失败不会覆盖原文件。
- [ ] PR #2 的侧栏纹理与邻接修复合入前，补齐 sprite 并单独验证浮窗邻接、不同高度与编码模式。

## 6. 远程权限与性能 / Remote permissions and performance

- [ ] 同维度受支持容器可远程打开并点击槽位；未适配机器、双箱与跨维度访问被拒绝。
- [ ] 锁变更、撤销领地权限、终端丢失、网络变化、目标替换或卸载立即使会话失效。
- [ ] 菜单自定义有效性返回失败时，不因距离豁免而继续开放。
- [ ] 连续输入、清空查询、切换视口和关闭面板不会发布旧搜索结果。
- [ ] 在真实大型网络与多玩家环境测量；可用服务器 JVM 参数 `-Dmest.driveSearch.metrics=true` 记录扫描量和耗时。
- [ ] 记录第三方元件枚举的最大耗时；每 tick 时间预算是软限制，不把纯预算单测当作 TPS 压测结果。

## 7. 发布与反馈 / Release and feedback

- [ ] Changelog 使用简短中英文有序列表，只记录用户可见的新增、修复、移除和优化。
- [ ] 每个 Issue 回复区分“已在 main 修复”“已发布版本”“尚未复现/需要信息”，不把代码合入说成已经发布。
- [ ] PR 审查核对实际提交 SHA、资源与测试；不得因 Git 显示无冲突就跳过审查。
- [ ] 发布附件从已验证提交构建，注明仍未执行的游戏内验证场景。

以上勾选项是发布前的待验证事项，不表示本次已全部执行。
Unchecked items are required release checks, not claims that they have already been performed.
