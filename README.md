# 墨阅 Android V1.6.2

本工程是墨阅的原生 Android 实现，使用 Kotlin、Jetpack Compose、Room 与 DataStore。V1.5.0 在原有 TXT、EPUB、Markdown 和 PDF 阅读基础上增加静态 JPG/PNG/WebP 图片、CBZ 与纯图片 ZIP 漫画阅读。

V1.5.2 将章节目录改为全屏连续列表，下滑不关闭；合并多卷目录，刷新旧目录并显示抓取状态；缩小分页正文顶部留白。保留 V1.5.1 的左右分页的正文点击翻页、网页 GBK/GB18030 编码识别与完整分页目录发现；已有乱码网页会在联网时重取正文，保留原 HTML、章节 ID 与阅读位置。目录按需读取正文，不整本下载；未抓取完的目录不标记完成。

V1.5.4 补齐跨页目录、浏览器动态目录渲染、目录来源设置与诊断导出；保留目录附录并修正网页书名和章节标题提取。CBZ 导入的系统文件选择器兼容不同提供方。

V1.6.0 增加统一高亮、笔记、位置书签与摘录中心，支持筛选、编辑、删除、返回原文和导出 Markdown；书架全局搜索覆盖书名、作者、本地正文、摘录与笔记。TXT、EPUB、已缓存网页、Markdown 和 PDF 支持正文标注；图片/漫画支持位置书签及笔记。版本说明见 [V1.6.0](docs/v1.6-release.md)，实施与验收见 [V1.6 验收](docs/v1.6-delivery.md)。

V1.6.1 修复重复标记时旧颜色覆盖新颜色、原生高亮填满行间空白和 PDF 放大时标注漂移；阅读配色按颜色变化计算，减少打开设置时的重复布局及窗口属性更新。见 [V1.6.1 版本说明](docs/v1.6.1-release.md)。

V1.6.2 将中央点击唤出的工具栏状态与正文隔离，保持标注输入和选择回调稳定，避免工具栏开关重复执行正文组合及重启点击监听。保留工具栏布局、动画和阅读数据；完整设置面板首开仍可能停顿。见 [V1.6.2 修补与验证](docs/v1.6.2-verification.md)。

打开或回到 App 时自动检查 GitHub 最新稳定版，成功检查每日最多一次；设置页提供开关及手动检查。发现更新后可下载、取消或稍后处理，通过 SHA256、版本、包名与原发布签名校验后进入系统确认覆盖安装。首次使用需覆盖安装本版。功能与后续发布步骤见 [软件更新](docs/updates.md)。

图片使用固定版本 ZoomImage 1.4.0 + Coil 3.2.0，支持双指/双击缩放、旋转、适屏和适宽。漫画支持单页/连续阅读、自然页序、缩略图、页码跳转和书签；逻辑页、页内位置、缩放及旋转独立存储。Room 升级为 schema 3，增量迁移保留已有书籍、进度和旧书签，覆盖安装无需重新导入已有资料。

图片归档限制为源文件 200 MiB、2000 条目、单条目 32 MiB、累计实际解压 1 GiB；像素限制为边长 65535、面积 200 MP。漫画缓存按需生成并限制为 8 页/96 MiB，正在显示的页面在释放前保留。拒绝坏图、动态 WebP、越界路径、重复路径和混合文档 ZIP；CBR/GIF/Office 尚未接入。

## 构建环境

- JDK 17
- Android SDK 36
- Gradle 8.14.3
- Android Gradle Plugin 8.13.0
- Kotlin 2.2.21
- 最低系统 API 26

首次构建前，在工程根目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=D\:/Android Studio/Sdk
```

常用命令：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

`assembleRelease` 默认生成未签名 APK。正式交付包需使用同一发布密钥执行 `zipalign` 和 `apksigner`，否则无法覆盖安装旧版本。

## 目录

- `app/src/main/java/com/moyue/reader/core`：数据模型、Room、设置、存储和主题
- `app/src/main/java/com/moyue/reader/parser`：TXT、EPUB、网页解析器
- `app/src/main/java/com/moyue/reader/feature`：书架、导入、阅读器、详情与设置界面
- `app/src/test`：JVM 单元测试
- `app/schemas`：Room schema
