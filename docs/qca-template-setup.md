# 在 QCA 上配置 SuperStudent Template

SuperStudent 的「智能」全部来自 QCA 平台上的一个 **Template（云端 Agent 模板）**。App 只负责下发任务、观察进度、严格验收并渲染结果，**本身不包含任何模型 / 技能 / 工具逻辑**。本文说明这个 Template 是怎么配置的，以及 App 的每个功能对应哪些 skill 和工具。

> 术语约定：本文的「drive」指 **QCA Forward API 的 drive**（云端文件存储），与 Google Drive 无关。

## 1. Template 概览

Demo 使用的 Template（名称 `SuperStudent`；其 ID 属于作者的 QCA 账号，此处不公开——你需自建一个等价 Template，见文末步骤）配置形态如下：

| 项 | 值 |
|---|---|
| 模型 model | `qmodel_38max`，reasoning effort = `medium`，context window = `200000` |
| system prompt | **空** —— 行为完全由 skills + 工具 + App 下发的任务提示词驱动 |
| MCP servers | 无 |
| 工具集 tools | `agent_toolset_20260401`（见下）+ `browser_toolset_20260714`（Browser Use） |
| 托管工具 managed tools | `drive`、`create_forward_schedule`、`delete_forward_schedule`、`list_forward_schedules` |
| skills | 6 个自定义 skill（见 §2） |
| Vault | 1 个 —— 向 skill 注入第三方凭证（如 ProcessOn、`QMIND_TOKEN`），**绝不进 App、绝不进产物** |
| environment | 云端沙箱环境（预装 `python-pptx`、qmind CLI 等） |

### 工具集 `agent_toolset_20260401`（全部 `always_allow`）

`Bash`、`Read`、`Write`、`Edit`、`Glob`、`Grep`、`WebFetch`、`WebSearch`、`ImageSearch`、`ImageGen`、`DeliverArtifacts`

对本 Demo 关键的几个：

- **`drive`（托管工具）**：云端 Agent 读写 QCA drive 的唯一通道。App 上传的资料、Agent 产出的结果文件都在 drive 的 `superstudent/v1/` 前缀下。任务提示词里约定的 drive 工具签名：
  - `mount_drive_file(path, mount_path)` —— 把源资料挂进沙箱后才能读
  - `add_drive_file(source_path, path)` —— 把沙箱产物写回 drive（沙箱内的直接写**不会**持久化，只有这个会）
  - `list_drive_entries(path, limit, page_token)`
  - `delete_drive_entry(path, recursive)`
- **`ImageGen`**：为记忆卡片生成配图（1024×1024 PNG，sRGB）。
- **`Bash`**：在沙箱内跑 `python-pptx` 生成 PPT、跑 qmind CLI 建索引、做 sha256/`unzip -l` 自检。
- **`Read`**：读取挂载进沙箱的源资料（PDF / DOCX / 图片等，Read 对图片提供视觉输入）。

## 2. 配置的 6 个 skill

| skill（display_title） | 作用 | 主要服务的 App 功能 |
|---|---|---|
| `processon-mindmap-generator` | ProcessOn 官方 AI 脑图，支持思维导图 / 逻辑图 / 组织结构图 / 鱼骨图 / 时间轴 / 树形图 / 表格图 7 种布局，可在线编辑协作 | 导图 |
| `mind-map-skill` | 从 Markdown 生成 **PNG** 思维导图（free / center / horizontal 布局） | 导图的 **PNG 兜底** |
| `pptx` | 演示文稿（`.pptx`）创建 / 编辑 / 分析 | PPT |
| `docx` | Word 文档（`.docx`）创建 / 编辑，支持修订、批注、格式保留、文本抽取 | 学习计划 / 习题（文档形态产物与源资料解析） |
| `pdf` | PDF 抽取文本 / 表格、创建、合并 / 拆分、表单处理 | 读取 PDF 源资料 / 产出 PDF |
| `qmind-knowledge` | QMind 知识工具集：知识检索、notebook 管理、批量上传、文件管理、知识编译与 lint、生成卡片 | 记忆卡片 + 资料索引（`QMIND_INDEX` 阶段） |

> **关于「导图」的重要澄清**：`processon-mindmap-generator` 与 `mind-map-skill` 都与导图相关，但 App 里真正渲染的是**可交互 SVG**（由 App 下发的 **HTML 契约 v2** 生成、在离线 WebView 里渲染，支持节点折叠/展开）。`mind-map-skill` 产出的 PNG 只是渲染回退链里的一环（HTML → PNG → 原生结构树）。详见 [architecture.md](architecture.md) 的「导图渲染」小节。

## 3. 功能 ↔ skill / 工具 映射

App 下发一次生成任务后，云端 Agent 按固定阶段推进；每个阶段会输出一行 `[STAGE:名称]` 标记，App 通过 SSE 抓到后显示进度：

```
PARSE → QMIND_INDEX → GENERATE_PLAN → GENERATE_CARDS → GENERATE_MINDMAP
      → GENERATE_DECK → GENERATE_EXERCISES → VALIDATE → PUBLISH
```

| 阶段 / App 功能 | 主要用到的 skill / 工具 | 产出文件（drive `superstudent/v1/packages/{pkg}/results/`） |
|---|---|---|
| **PARSE** 解析源资料 | `Read` + `pdf` / `docx`（抽取文本、定位页码 / 幻灯片 / 段落） | —— |
| **QMIND_INDEX** 资料索引 | `qmind-knowledge`（bind/create notebook、source upload、retrieve 探针）+ `Bash` | —— |
| **GENERATE_PLAN** 学习计划 | 模型推理（+ `docx` 文档形态）；主题 / 难度 / 优先级 / 任务 / 引用 | `plan.json` |
| **GENERATE_CARDS** 记忆卡片 | `qmind-knowledge`（生成卡片）+ `ImageGen`（可选配图，并发 1） | `cards.json`（+ `cards/img/{cardId}-{promptHash12}.png`） |
| **GENERATE_MINDMAP** 导图 | 按 App 内联的 **HTML 契约 v2** 生成 `svg#ss-mindmap`；`processon-mindmap-generator` / `mind-map-skill`（PNG 兜底） | `mindmap.json` + `mindmap.html`（+ `mindmap.png`） |
| **GENERATE_DECK** PPT | `Bash` 跑 **`python-pptx`（1.0.2，沙箱预装）** 生成 16:9 幻灯片 | `deck.pptx` + `deck.manifest.json` |
| **GENERATE_EXERCISES** 习题 | 模型推理（+ `docx`/`pdf` 文档形态）；单选 / 计算 + 分步解析 | `exercises.json` |
| **VALIDATE / PUBLISH** | `drive`（`list_drive_entries` / `add_drive_file`）回列文件、交叉校验、清理 tmp、输出最终 envelope JSON | `citations.json`（贯穿全部产物的引用表） |

> **PPT 的一个反直觉细节**：Template 虽然绑定了 `pptx` skill，但任务契约**显式要求用 `python-pptx` 直接生成**，并**禁用** `pptx` skill 的 html2pptx / Playwright 路径 —— 因为该沙箱环境缺少 Playwright 浏览器二进制。这说明「绑定了某 skill」不等于「一定走该 skill 的默认路径」：真正的产物形态由 App 下发的契约决定。

### 闭环范式：App 定契约 → 云端履约 → App 严格验收

App 的功能**不是**「调用某个 skill」这么简单。核心在于一段结构化的中文任务提示词
（`app/src/main/java/com/superstudent/app/features/tasks/PromptTemplate.kt`），
它精确约定了：

- 每个产物的 **JSON schema**（字段、枚举、数量下限，如卡片 ≥5、习题 ≥3、计划主题 ≥3）；
- 导图的 **几何与 DOM 契约**（节点间距、层级推进、`svg#ss-mindmap` 结构、CSP、折叠按钮）；
- **文件命名与路径**（9 个必需结果文件、drive 前缀、卡片图命名规则）；
- **引用溯源**（每个产物条目至少 1 个 `citationId`，且必须能在 `citations.json` 解析）。

云端 Agent 用自己的 skill + 工具去**满足这份契约**；App 侧再用 `:core:model` 的 `ResultValidator`
**严格验收**（例如导图 HTML 必须满足 v2 契约，否则整次任务判失败、学习包回退到可重新生成状态）。
这份「App 定契约、云端履约、App 严格验收」的闭环，正是本 Demo 想展示的 QCA 集成范式。

## 4. 自建 Template 的步骤

1. 在 QCA 平台（Forward）创建一个 Template，命名如 `SuperStudent`。
2. 选择模型 `qmodel_38max`（或等价模型），effort `medium`，context 200k。system prompt 可留空。
3. 绑定上面 6 个 skill。若你的账号没有同名 skill，需先创建 / 导入等价能力的 skill；ProcessOn、qmind 等依赖对应的 **Vault 凭证**。
4. 配置工具集 `agent_toolset_20260401`（启用 Bash / Read / Write / Edit / Glob / Grep / WebFetch / WebSearch / ImageSearch / ImageGen / DeliverArtifacts，permission 建议 `always_allow`），视需要启用 Browser Use。
5. **启用托管工具 `drive`（必需）** —— 产物读写全靠它。
6. 准备一个云端沙箱 environment，预装 `python-pptx==1.0.2`、qmind CLI 等；把第三方凭证放进 Vault 并绑定到 Template。
7. 拿到 Template ID，替换进 App：`app/src/main/java/com/superstudent/app/AppContainer.kt` 里的 `TEMPLATE_ID` 常量。
8. 用你自己的 PAT 在 App 登录页登录。

> Vault / environment / skill 的具体可用性取决于你的 QCA 账号与订阅。本文给出的是 Demo 使用的配置**形态**，作为你自建时的参考，而非可一键复制的清单。
