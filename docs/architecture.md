# 架构与技术细节 / Architecture

本文面向想读懂或二次开发 SuperStudent 的工程师，说明 App 侧如何与 QCA 云端协作。云端 Template 的配置见 [qca-template-setup.md](qca-template-setup.md)。

## 1. 模块划分

单 Activity（`app/src/main/java/com/superstudent/app/MainActivity.kt`）+ Jetpack Compose + 多模块 Gradle：

| 模块 | 职责 |
|---|---|
| `:app` | Compose UI、导航、功能特性、DI 容器（`AppContainer`）、任务运行器（`TaskRunner`）、生成契约（`PromptTemplate`） |
| `:core:model` | 纯 Kotlin DTO / 枚举 / 结果 JSON schema + 严格校验器 `ResultValidator`（含导图 HTML 契约 v2）。依赖 kotlinx-serialization + jsoup |
| `:core:network` | QCA Forward API 的 Retrofit/OkHttp 层、SSE 观察、预签名传输、错误映射 |
| `:core:database` | Room（schema v1–v4，导出在 `core/database/schemas/`）、DataStore、Drive 仓库、各业务仓库 |
| `:core:security` | Keystore PAT 存储、PAT 校验、用户名归一化、哈希 |
| `:core:designsystem` | Compose 主题 / 令牌 / 组件 |

导航路由（`app/src/main/java/com/superstudent/app/Routes.kt`）：`login`、`shell?tab={tab}`、`package/new`、`package/{packageId}`、`results/{packageId}`。冷启动时：无 PAT 或无绑定身份 → 登录页；否则 → shell 的 STUDY Tab。

## 2. 与 QCA 的集成

### 2.1 基址与鉴权

- **基址**：`QcaConfig.BASE_URL = "https://api.qoder.com/"`（`app/.../AppContainer.kt`）。运行时还会出现第二个动态 host —— 预签名 drive URL 里的存储 host。
- **鉴权**：每个 API / SSE 请求由 OkHttp 拦截器加上 `Authorization: Bearer <PAT>`，PAT 在**请求时**惰性读取；缺凭证抛 `MissingCredentialException`。
- **PAT 存储**：Android Keystore AES-256-GCM，alias `superstudent_pat_v1`，密文文件 `credential.json` 存于 `noBackupFilesDir`，原子写（临时文件 + rename），`inspect()` fail-closed；登出删除文件 + Keystore alias。**代码里没有任何硬编码密钥** —— 唯一编译进的常量是公开的 base URL、Template ID 和 app 名。
- **日志脱敏**：调试日志始终遮蔽 `Authorization`，并丢弃预签名 URL 的 query（"那段 query 就是签名本身"）。

### 2.2 身份（Identity）

用户名 → NFKC / 小写归一化 → 确定性 `external_id = "superstudent:v1:" + sha256(username)`。登录时按 `external_id` 列身份，0 命中则创建（幂等键 `ss:create-identity:<sha256>`）。因此**同一用户名在任何设备上解析到同一云端身份**，drive 数据可跨端恢复。

### 2.3 Forward API 端点

Retrofit 接口 `QcaApi`（`core/network/.../QcaApi.kt`）覆盖：

- `api/v1/forward/identities`（list / create / get）
- `api/v1/forward/sessions`（create / get / send events / list events / cancel）
- `api/v1/forward/drives/entries | upload-url | download-url` + DELETE entries
- SSE 流：`/api/v1/forward/sessions/{id}/events/stream`

### 2.4 Drive 布局

固定根前缀 `superstudent/v1`（`core/database/.../drive/DrivePath.kt`）。路径段白名单 `[A-Za-z0-9._-]`，禁止 `..`，显示名永不进入路径。关键布局：

```
superstudent/v1/
  profile.json  index.json  progress.json
  packages/{packageId}/
    sources/{sourceId}/{sourceId}-{sha256[0:12]}.{ext}     # 冻结的源资料对象
    runs/{runId}/tmp                                        # 云端草稿（发布前清理）
    results/
      plan.json  cards.json  citations.json
      mindmap.json  mindmap.html  mindmap.png
      deck.pptx  deck.manifest.json  exercises.json
      cards/img/{cardId}-{promptHash12}.png
```

`DriveRepository`：`uploadBytes` = 请求预签名 upload-url 再 PUT（过期最多重试 3 次）；`downloadBytes` = 预签名 download-url 再 GET；删除有护栏（拒绝删根、子树删除要求深度 ≥ 4）。`PresignedTransfer` 用裸 OkHttp 对签名 URL 做 PUT/GET，400/403 → `PresignedUrlExpiredException`。

### 2.5 任务生命周期（`app/.../features/tasks/TaskRunner.kt`）

1. **提交** `submitNew`：先过凭证门 → 只放行「已上传 + 可生成」的源 → 首次 qmind notebook 构建的单飞准入护栏 → 通过 `ManifestWriter` 发布 GENERATING → `createSession`（幂等键 `ss:create-session:{taskId}-{attempt}`，metadata 记 `app / package_id / task_id / attempt`）→ 写入 sessionId 是「不可回头点」，每条失败路径都有补偿 → `sendTaskMessage` 把提示词作为**一条** `user.message` 事件发出（幂等键 `ss:send-task:{taskId}-{attempt}`）；`TURN_ALREADY_RUNNING` 当作成功重放。崩溃的提交经 `replaySubmit` 幂等重放。运行期挂在 `TaskForegroundService`（dataSync 前台服务）下。
2. **观察** `observe`：优先 SSE（60s 窗口，断线用 `Last-Event-ID` 重连）；流断则回退轮询 `listEvents`（前台退避 1/2/4/8/15s，后台 30s）。扫描 Agent 文本里的 `[STAGE:X]` 标记与最终 envelope JSON。
3. **成功门**：回合结束时 `ResultsRepository.fetch(...)` 下载全部产物并**严格校验** —— 「五个产物全部通过校验，任务才可能 SUCCEEDED」。其中 `mindmap.html` 会与 `mindmap.json` 交叉核对（`ResultValidator.acceptedMindmapHtml(..., strictV2=true)`），文档式 / 不合格的导图会**抛错并使整次运行失败**，学习包回到 READY 以便重新生成；`deck.pptx` 做二进制核验（ZIP magic、slide part 数、sha256、size）。
4. **取消**：`cancelSession` 经 `RemoteCancelConverger`（GET-before-POST + POST-after-confirm），未确认则补偿 worker 兜底。

## 3. 生成契约（`PromptTemplate.kt`）

任务提示词全中文，逐次运行由 `TaskPayload` 构建 —— 且**只**含下发给模型的字段，"绝不含 PAT、用户名、本地绝对路径"。核心约定：

- **阶段**：`PARSE → QMIND_INDEX → GENERATE_PLAN → GENERATE_CARDS → GENERATE_MINDMAP → GENERATE_DECK → GENERATE_EXERCISES → VALIDATE → PUBLISH`，每阶段输出一行 `[STAGE:名称]`。
- **沙箱草稿目录**：固定 `/data/ss-work/`；运行 tmp 在 `{outputPrefix}/runs/{taskId}-{attempt}/tmp`；结果写 `{outputPrefix}/results/`。
- **9 个必需结果文件** + 可选 `mindmap.png`；5 个 JSON 共用一个 envelope：
  `{"schemaVersion":1,"packageId":...,"generatedAt":...,"sourceRevision":"sha256:...","generator":{"templateId","sessionId","attempt"},"data":{}}`
- **安全约束**：从不索要 / 回显凭证；`QMIND_TOKEN` 来自 Vault 且只可被调用；路径仅限本任务前缀；任何产物里不得出现 PAT / token / userId 明文。
- **最终 envelope**：Agent 必须回列 9 个文件、交叉校验、删除 tmp，并把最后一条消息**恰好**输出为
  `{"status":"SUCCEEDED","stage":"PUBLISH","mindmapPng":...,"artifacts":[{path,sha256,bytes}×8],"qmind":{...}}`
  或对应的 FAILED envelope；App 侧用 `parseFinalManifest` 解析（带花括号配对、容忍混入的 `[STAGE:]` 标记）。

## 4. 导图渲染（HTML 契约 v2）

「导图」是本 Demo 技术含量最高的部分：它不是图片，而是一张**可交互的 SVG 图**，在离线 WebView 里渲染。

### 4.1 契约要点

- `mindmap.json`：`title`、`layout`（CENTER / HORIZONTAL）、递归 `root`（每节点含 `nodeId`、`label`、`explanation`、**≥1 个 `citationId`**、`children`）、`visualContractVersion` **固定为 2**、`visual` 清单（`canvas {width,height}` = SVG viewBox；`nodes` 覆盖树的每个 nodeId 并给 `{x,y,width,height}`；`edges` 覆盖每个父子对）。树规模：≥4 节点、≤6 层。
- **几何硬约束**：节点间距 ≥16px；父子中心距 ≥48px；每层向外推进 ≥48px；HORIZONTAL 根在最左且 `child.x ≥ parent.x + parent.width + 48`；CENTER 根居中（±5% 画布中心）、子节点分列两侧、无节点跨越过根中心的竖线。
- `mindmap.html`：单文件、仅内联 CSS/JS、UTF-8、**≤1 MiB**；`<head>` 必含 charset、viewport、**CSP** `default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;` 与 `-webkit-text-size-adjust:100%`；禁止 `iframe/object/embed/form/applet`、meta refresh、`<link href>`、`<script src>`、CSS `@import`、任何外部 URL 属性。
- **DOM 契约**：恰好一个 `svg#ss-mindmap`（`data-contract-version="2"`、`data-layout`、`viewBox` 与 canvas 一致、`width/height=100%`、`preserveAspectRatio="xMidYMid meet"`）；每个节点一个 `g.mindmap-node[data-node-id]`（含 `data-x/y/width/height`，与 JSON 误差 ≤0.5，并含 label / explanation / 每个 citationId 文本）；**非叶节点必含 `button[data-action="toggle"][aria-expanded]`**，点击可切换子孙与连线可见性并同步 `aria-expanded`；每条边一个 `path.mindmap-edge[data-from][data-to]`（`d` 非空）；SVG 节点之外不得出现 `h1`–`h6` 或 `ul/ol/li`（"不允许用『大标题 + 文档流列表』代替图"）。

### 4.2 客户端强制校验

上述契约在客户端由 `core/model` 的 `ResultValidator` 镜像强制（`MIN_NODE_GAP=16`、`MIN_LEVEL_ADVANCE=48`、`MIN_PARENT_CHILD_CENTRE_DISTANCE=48`、`CENTER_ROOT_TOLERANCE=0.05`、`MAX_MINDMAP_HTML_BYTES=1MiB`、`COORD_EPSILON=0.5`、`REQUIRED_CSP="default-src 'none'"`，禁止标签 / 允许 scheme `data|about|blob`）。失败分类：`missing-contract | topology-mismatch | geometry-invalid | html-invalid`。导图 HTML 用 **jsoup** 解析，从不用正则。

### 4.3 离线渲染与回退链

- **离线 WebView**（`app/.../features/results/MindmapWebView.kt`）：以字符串加载、base URL 为 null、无 JS bridge、网络与文件系统不可达。
- **关键工程点**：WebView 被宿主在一个普通 `ViewGroup`（`FrameLayout`）里，使其走平台测量路径 —— 否则 CSS viewport 单位在 Compose 测量路径下会解析为 0，导致图高度塌陷为空白。
- **渲染握手**（`MindmapRenderState.kt`）：4 信号正向握手 `PAGE_FINISHED / DOM_PROBE / TOGGLE_PROBE / VISUAL_STATE`，8s 超时；toggle 点击会核验可见节点/边数是否随 `aria-expanded` 声明变化。
- **回退链**：HTML（可交互 SVG）→ PNG（`mindmap.png`）→ 原生结构树，覆盖所有 SDK 级别。

## 5. 上传与恢复

资料上传经 `UploadForegroundService` + `SourceUploadWorker`，采用租约（lease）机制做孤儿回收与断点恢复；源对象一旦写入 drive 即按 `{sourceId}-{sha256[0:12]}.{ext}` 冻结命名。启动时有 `StartupReconciler` 对本地状态与 drive 状态做对账。相关内部契约见 `docs/upload-failure-contract.md`（内部文档，含 `ZLQ-xxx` 引用）。

## 6. 安全与权限（Manifest）

权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE(_DATA_SYNC)`、`POST_NOTIFICATIONS`。两个 dataSync 前台服务（任务 + 上传）。`allowBackup=false`，通过 `network_security_config.xml` 禁用明文流量。
