# 墨阅 Android V1.5.2

本工程是墨阅的原生 Android 实现，使用 Kotlin、Jetpack Compose、Room 与 DataStore。V1.5.0 在原有 TXT、EPUB、Markdown 和 PDF 阅读基础上增加静态 JPG/PNG/WebP 图片、CBZ 与纯图片 ZIP 漫画阅读。

V1.5.2 将章节目录改为全屏连续列表，下滑不关闭；合并多卷目录，刷新旧目录并显示抓取状态；缩小分页正文顶部留白。保留 V1.5.1 修复左右分页的正文点击翻页、网页 GBK/GB18030 编码识别与完整分页目录发现；已有乱码网页会在联网时重取正文，保留原 HTML、章节 ID 与阅读位置。目录按需读取正文，不整本下载；未抓取完的目录不标记完成。

图片使用固定版本 ZoomImage 1.4.0 + Coil 3.2.0，支持双指/双击缩放、旋转、适屏和适宽。漫画支持单页/连续阅读、自然页序、缩略图、页码跳转和书签；逻辑页、页内位置、缩放及旋转独立存储。Room 保持 schema 2，覆盖安装无需重新导入已有资料。

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
