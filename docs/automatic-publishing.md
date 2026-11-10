# 自动发布 / Automatic publishing

## 已配置 / Configuration

- GitHub repository: `lhy512103/MEST`
- CurseForge project: `1715792` ([ME Spliced Terminal](https://www.curseforge.com/minecraft/mc-mods/me-spliced-terminal))
- Repository secret: `CURSEFORGE_TOKEN`（已有；只需在 GitHub Secrets 中维护，不写入代码或日志）
- Repository variables: `CURSEFORGE_PROJECT_ID=1715792`、`CURSEFORGE_MINECRAFT_VERSION=1.21.1`
- GitHub 发布使用任务自带的 `GITHUB_TOKEN`，仅发布任务拥有 `contents: write`。

本配置仅发布 GitHub Release 与 CurseForge，不包含 Modrinth。配置工作流本身不会创建 tag 或发布新版本。
Only GitHub Releases and CurseForge are configured. Adding this workflow does not itself publish a version.

## 发布新版本 / Publish a version

1. 更新 `gradle.properties` 的 `mod_version`。
2. 在 `CHANGELOG.md` 中创建唯一的 `## v版本号`（也支持 `## 版本号`）章节，将本次中英文有序更新内容放入其中；`## Unreleased` 不会作为替代。
3. 提交并推送代码，确认普通 CI 通过。
4. 为该提交创建并推送版本 tag，例如：

```powershell
# 示例：仅在版本与对应 Changelog 章节已准备好时执行。
git tag v0.0.3.2
git push origin v0.0.3.2
```

四段版本如 `v0.0.3.2` 可用；`-alpha.1`、`-beta.1`、`-rc.1` 为预发布（rc 在 CF 标为 beta）。
Tag、`mod_version`、JAR 内版本必须完全一致，不自动覆盖或猜测版本。不要仅更新 tag 而忘记整理版本章节。

The tag version must exactly match `mod_version` and the JAR descriptor. Notes are extracted only from the
matching level-two Changelog section, preserving English/Chinese and level-three headings.

## 自动链路 / Pipeline

`Release and CurseForge` 在版本 tag 推送（包括指向新提交的更新）时运行，也可在 Actions 中输入现有 tag 手动执行。

1. 校验 tag、版本章节与发布脚本测试。
2. 对 tag 指向的提交执行 `clean build`，包含 Java 测试；采用最小运行配置，不使用工作目录里残留的 JAR。
3. 只接受 `build/libs/mesplicedterminal-版本.jar`，拒绝空文件、sources/javadoc/dev、损坏 ZIP、错误 modId/版本或没有模组 class 的包。
4. 新建 GitHub Release 时先创建草稿，上传 JAR 并下载校验 SHA-256，确认后才公开；已有 Release 先上传替代附件，再替换旧附件并更新说明。
5. 再次从 GitHub Release 下载并验证同一个 JAR，将 **JAR + 当前 Release 正文**作为一次 multipart 上传提交到 CurseForge。
6. CF 返回文件 ID 后，在 Release 附上 `curseforge-publish.json` 回执，记录项目、文件 ID、JAR SHA-256 和说明摘要。

**没有匹配的非空、有效 JAR，就不会公开新 Release，也不会创建或更新 CF 文件。**
CF 同步不会改用 CI 构建 artifact、任意文件或其他版本的 JAR 来凑数。

GitHub Release creation and CF synchronization run in the same workflow because releases created with
`GITHUB_TOKEN` do not trigger a second `release` workflow. A public release is never created from notes alone.

## 修改说明、重跑与 tag 更新 / Edits and reruns

- 手动发布或编辑 GitHub Release，会触发 `Sync Release to CurseForge`；也可手动输入 tag 同步。
- 同步时仍先确认 Release 非草稿、正文非空、目标 JAR 已上传，并重新下载验证。
- 相同 JAR 和正文的正常重跑直接跳过 CF 上传；仅正文变化时更新回执中同一个 CF 文件的 Markdown Changelog，不再重复上传二进制。
- 同一 tag 构建出不同 SHA-256 的 JAR 时，CF 会创建新文件并记录新回执；CF 不能原位替换旧文件的二进制。推荐新版本使用新 tag，而不是移动已发布 tag。
- 创建新 Release 后才手动上传 JAR，上传附件本身不保证触发 release edited；应在附件上传完成后手动运行同步，或再次保存 Release 正文。
- 两个工作流按同一 tag 共用并发锁，不取消正在进行的上传。
- 如果 tag 在构建中移动，发布前的提交号复核会拒绝旧产物。

Existing CF files are updated only after the matching GitHub JAR is verified. A changed binary creates a
new CF file; a text-only edit updates the existing CF file's changelog. Do not delete publishing receipts.

## 失败与人工恢复 / Failure handling

- CF 密钥缺失、失效或没有项目权限时，CF 步骤失败；已成功附带 JAR 的 GitHub Release 保留，不回滚删除。
- 没有 JAR 的手工 Release 会使同步明确失败，不会向 CF 发布纯文本。
- 请求超时不会自动重试 CF 上传，避免服务器已接受文件却重复创建。
- **CF 接受上传与 GitHub 保存回执并非跨站事务。**若日志出现 CF 文件 ID、随后回执保存失败，请先在 CF 后台核对文件，不要直接重跑。按返回 ID 与 SHA-256恢复回执，或联系维护者处理。
- 首次启用前已有的历史 Release/CF 文件没有回执；不要盲目重跑历史版本，否则可能新增一个同版本文件。可先人工核验旧 JAR 摘要后建立回执。
- 如果同一回执 SHA-256 对应的 CF 文件已被手工删除，需核对并修复回执后重跑；不要用强制忽略错误绕过附件检查。
- CF 审核通过的时间由平台决定，API 接受上传不等同于审核完成。

## 本地无发布验证 / Offline checks

```powershell
python -m unittest discover -s .github/scripts -p 'test_*.py' -v
python .github/scripts/release_metadata.py prepare --tag v0.0.3.1 --properties gradle.properties --changelog CHANGELOG.md --output-dir build/release-check
python .github/scripts/release_metadata.py verify --metadata build/release-check/metadata.json --jar build/libs/mesplicedterminal-0.0.3.1.jar --output-json build/release-check/artifact.json
```

以上命令不会调用发布 API。正式 API 调用只在发布工作流或显式执行 `publish_release.py` 时进行。
测试覆盖无 JAR 拒绝、版本/日志不匹配、伪造包、草稿公开顺序、替换失败保留旧附件、CF multipart 文件与说明、重复运行和说明更新。

API reference: [CurseForge Upload API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api).
