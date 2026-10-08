# 软件更新

首次使用自动更新功能需覆盖安装包含此功能的版本。之后打开或回到 App 时会检查新版本；成功检查每天最多一次，失败后至少间隔一小时重试。设置 → 软件更新提供开关和手动检查。自动检查失败不弹窗；阅读时发现的更新提示会等回到书架或设置页显示。

发现新版后显示版本、大小和更新说明，由用户点击下载。下载可取消、重试；完整下载且通过校验后才能点击安装。首次安装需要在 Android 的“允许来自此来源的应用”页面授权，返回后继续安装；拒绝时保留下载包。系统安装仍需用户确认。直接覆盖安装，不要卸载旧版。

更新渠道固定为公开的 fshfish88-lab/moyue GitHub Releases：

- 更新信息：`https://github.com/fshfish88-lab/moyue/releases/latest/download/update.json`
- 版本比较使用 APK 的 versionCode；同版、旧版和不兼容系统不会提示安装。
- APK 限制 150 MiB，校验 SHA256、实际版本和包名、发布签名。下载只允许指定仓库地址及 GitHub 的 HTTPS 资产跳转。
- 不上传书籍和阅读记录，不在 APK 中保存 GitHub 令牌或发布密钥。没有常驻后台服务，App 不运行时不会检查或静默安装。

## 发布后续版本

增加 versionCode，构建并用既有发布密钥签名。在成品目录中准备 `Moyue-x.y.z.apk`、`Moyue-x.y.z-source.zip` 和更新说明。推送并核对 main 后，从工程目录运行：

```powershell
$env:JAVA_HOME = 'D:\Android Studio\Jdk\jdk-17.0.20.1+1'
.\scripts\publish-release.ps1 -Version x.y.z -DeliveryDirectory '成品目录的绝对路径' -NotesPath '更新说明的绝对路径'
```

可先加 `-PrepareOnly` 从实际 APK 生成并核对本地更新信息，确认 versionCode 和 minSdk 后再上传。

脚本读取实际 APK 的包名、版本、最低系统和哈希，生成 update.json 与 SHA256.txt，并创建或更新草稿。脚本要求原发布证书。附件远端哈希不一致时会停止，保留草稿。完成验收后使用同一命令加 `-Publish` 发布为 Latest；发布完成后检查公开更新信息和实际下载。已发布 APK 不覆盖，修改应用需增加版本号重新发布；如仅修正更新信息，应先核对实际 APK 并单独修复元数据。保持仓库公开，否则手机端匿名查询不可用。
