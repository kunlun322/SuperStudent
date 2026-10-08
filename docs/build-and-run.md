# 构建与运行 / Build and Run

从零到「在真机上跑通一次生成」的完整步骤。

## 0. 前置条件

| 依赖 | 版本 / 说明 |
|---|---|
| JDK | **17**（AGP 9 要求 JDK 17+；`compileOptions` 也锁定 Java 17） |
| Android SDK | 需安装 **platform 37**（`compileSdk = 37`）；`minSdk = 28`、`targetSdk = 36` |
| Gradle | 用仓库自带 wrapper（**9.8.0**），无需本地安装 |
| 一个 QCA 账号 | 用于自建 Template、获取 PAT（见下） |
| 一台 Android 真机 / 模拟器 | API 28+ |

> **网络提示（国际贡献者注意）**：wrapper 的 Gradle 发行版指向腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`），`settings.gradle.kts` 里 Aliyun maven 镜像排在 `google()` / `mavenCentral()` 前面。在中国大陆网络下更快；若在境外构建缓慢，可把这两处改回官方源。

## 1. 在 QCA 上准备云端（**必做**，否则无法生成）

App 本身不含任何模型/技能逻辑，生成能力全部来自云端 Template。请先按 **[qca-template-setup.md](qca-template-setup.md)** 在 QCA 上：

1. 自建一个 Template（配好等价的 6 个 skill + 工具集 + 托管工具 `drive` + Vault + 沙箱环境）。
2. 记下它的 **Template ID**（形如 `tmpl_xxx`）。
3. 准备一个 **PAT（访问令牌）**。

## 2. 把 Template ID 填进 App

仓库里编译进的是 Demo 作者的 Template ID，**必须替换成你自己的**：

```
app/src/main/java/com/superstudent/app/AppContainer.kt
```

找到常量 `TEMPLATE_ID`（`QcaConfig.TEMPLATE_ID`），改成你第 1 步拿到的 `tmpl_xxx`。
（同一文件里的 `BASE_URL = "https://api.qoder.com/"` 一般无需改动。）

## 3. 构建 Debug APK

```bash
cd <仓库根目录>
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

- Debug 包用 Android 标准 debug 签名，**不涉及 release 密钥**。
- Release 签名是可选的：`app/build.gradle.kts` 通过 `signingProp("SS_STORE_FILE" / "SS_STORE_PASSWORD" / "SS_KEY_ALIAS" / "SS_KEY_PASSWORD")` 从 gitignored 的 `local.properties`（或环境变量）读取；未配置时 release 变体保持未签名，而不会退回 debug key。**仓库里不含任何签名密钥或口令。**

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 若设备上已装有**签名不同**的同包名（`com.superstudent.app`）旧版本，覆盖安装会失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；先 `adb uninstall com.superstudent.app` 再装。

## 4. 首次运行与登录

1. 打开 App，进入登录页。
2. 输入 **访问令牌 PAT**（`pt-...`，密码框遮蔽）与 **学生用户名**（3–40 字符）。
3. 登录成功后：PAT 存进 Android Keystore（本机安全存储，不上云、不进日志）；用户名归一化后哈希成确定性云端身份，App 会从 drive 恢复该身份已有的学习包。

> 构建出的 APK **不内嵌任何凭证**：不登录 PAT，App 无法进行任何云端操作。

## 5. 跑通一次生成

1. **创建学习包**：填标题 + 选学习目标（期末复习 / 课前预习 / 日常巩固 / 课堂展示），可选章节范围。
2. **添加资料**：文件选择器 / 图片选择器 / 粘贴文本。上传经前台服务 + WorkManager，带租约恢复。
3. **触发生成**：在学习包详情页启动生成任务；App 通过 SSE 实时显示阶段进度（`PARSE → ... → PUBLISH`）。
4. **查看结果**：任务成功后进入结果页，五个 Tab —— 学习计划 / 记忆卡片 / 导图 / PPT / 习题。
   - **导图**需渲染可交互 SVG（节点折叠/展开）。注意：只有**新生成**的包才走 HTML 契约 v2；历史旧包可能只显示 PNG 或原生结构树，属预期回退，不是 bug。
   - 结果可经系统 SAF 导出。

## 6. 运行测试

```bash
./gradlew test          # 全模块 JVM 单元测试
```

- 仅 **单元测试**（各模块 `src/test`，约 57 个测试文件、数百个 `@Test`）；仓库**不含** `src/androidTest`，故 `connectedAndroidTest` 无用例可跑。
- 部分测试是「源码文本护栏」，会用花括号配对扫描 `TaskRunner.kt` 等源文件断言调用归属/顺序，并通过向上查找 `settings.gradle.kts` 定位仓库根 —— 因此**必须在 checkout 内运行**。
- 数据库迁移测试用 `org.xerial:sqlite-jdbc` 在 JVM 上跑真实 SQLite。

## 7. 版本标识

`app/build.gradle.kts`：`applicationId = "com.superstudent.app"`，本快照为 `versionCode = 10` / `versionName = "1.0.0-rc10"`。versionCode 遵循单调递增规则（避免 Room 降级崩溃）；文件内注释记录了 rc9 被扣留等内部发布管理信息，属 Demo 演进痕迹。

## 常见问题

- **生成一直失败 / 报导图校验不通过**：多半是云端 Template 未配好（缺 skill、缺 `drive` 托管工具、沙箱缺 `python-pptx`），或 Template ID 没换成你自己的。对照 [qca-template-setup.md](qca-template-setup.md) 逐项检查。
- **登录报网络错误**：确认设备能访问 `https://api.qoder.com/`，PAT 有效且未过期。
- **导图是空白**：确认 App 为包含 WebView 宿主修复的版本（本快照已含）；旧包无 `mindmap.html` 时会回退 PNG / 原生树。
