# 墨阅 Android V1

本工程是墨阅 V1.0.0 的原生 Android 实现，使用 Kotlin、Jetpack Compose、Room 与 DataStore。

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
