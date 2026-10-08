# 上传链路失败语义契约（ZLQ-86 增量）

本文件是 ZLQ-86 冻结的上传链路失败语义的**增量说明**，按 ZLQ-110 联合设计裁定 §3.3 末与 §4.2
的要求落盘；ZLQ-130 的租约与孤儿回收口径（增量七）同样落在这里。凡与 ZLQ-86 原文冲突处，
以本文件为准；本文件未提及的条目仍按 ZLQ-86 原文执行。

增量一至五是失败语义本身；增量六是 ZLQ-110 §2 的清单写入协议，落在这里是因为
`MANIFEST_PUBLISH_FAILED` 何时成立、重试为何必须幂等，都由它决定 —— 它与 ZLQ-86 原文不冲突，
是对原文没有覆盖的写入侧做的补充冻结。增量七是 ZLQ-130 的租约与孤儿回收口径，落在这里是因为
「一行什么时候算中断、什么时候才允许被别人接管」直接决定 `PROCESS_INTERRUPTED` 与
`MANIFEST_PUBLISH_FAILED` 的边界。

代码位置：

| 契约项 | 实现 |
|---|---|
| 错误码集合与自动重试白名单 | `core/database/.../core/upload/SourceFailure.kt` |
| 失败阶段归一化 | `SourceFailure.kt` 的 `normalizeLocalReadFailure`，由 `app/.../upload/SourceAccess.kt` 在本地读取与暂存两处调用 |
| 状态 × 入口 × 文案矩阵（增量二） | `core/database/.../core/upload/SourceRowPresentation.kt` 的 `SourceRowPresenter.of`，由 `app/.../features/packages/PackageDetailScreen.kt` 调用；界面只渲染它给出的文案与入口 |
| 租约、令牌与中断判定（增量七） | `PackageRepository.uploadSource` / `fail`，`SourceDao` 的 `claimForUpload` / `renewLease` / `recordProgress` / `countOwnedAttempt` / `requestInterrupt` / `interruptAttempt`，`core/database/.../core/upload/ProcessLeaseRegistry.kt` |
| 孤儿回收单飞协调器（增量七） | `app/.../features/packages/upload/SourceUploadRecoveryCoordinator.kt`，SQL 集中在 `core/database/.../core/database/SourceRecoverySql.kt` |
| 回收与租约日志（增量七） | `core/database/.../core/upload/SourceRecoveryLog.kt` |
| 计数与退避 | `PackageRepository.fail` / `nextRetryAt`，`SourceDao.claimForUpload` / `requeueForRetry` |
| 清单唯一写入路径（增量六） | `core/database/.../core/repository/ManifestWriter.kt` 的 `withPackageLock` / `commitUploadedSource` / `publishState` / `create`，`IndexStore` |
| 清单收录规则（增量六） | `ManifestWriter.projection` / `publishedSourceIds`，对账侧 `app/.../reconcile/ManifestProjectionReconcileRule.kt` |

## 增量一：`retryable` 定义收窄（修订 ZLQ-86 §3）

ZLQ-86 原文把 `retryable` 表述为「该失败是否还能重试」。**收窄为**：

> `retryable` 只表示**调度器是否可以自动重试这一行**，不表示这一行有没有出路。

推论，均为硬约束：

1. `retryable = false` **不得**被 UI、对账器或任何调用方读作「该行无法恢复」。
2. 自动预算耗尽（`attempt_count >= MAX_ATTEMPTS`）只剥夺自动重试，**不剥夺人工入口**。
   一次真实的网络失败烧完 6 次预算后 `retryable = false`，但 `source_retry_<id>` 仍必须存在。
3. 人工入口的有无由动作矩阵（增量二）独立判定，与 `retryable` 无关。

`AUTO_RETRYABLE` 集合本身未因本次收窄而改动；改的是**谁有资格进入这个集合**（增量三）。

## 增量二：按钮判定改为动作矩阵（修订 ZLQ-86 §6）

ZLQ-86 原文规定行内按钮「依据 `retryable` 与 `errorCode` 判定」。**改为**：唯一判定入口是纯函数

```kotlin
SourceRowPresenter.of(
    row: SourceAssetEntity,
    nowMillis: Long,
    deleting: Boolean = false,
    retrievalPending: Boolean = false,
): SourceRowPresentation   // stateText / errorText / actions / busy / tone
```

ZLQ-130 把判定从「只出入口集合」升级为「状态 × 入口 × 文案」一张矩阵（设计 §4），并把它从 app 模块
下沉到 `core:database`：**文案与入口必须由同一个函数同时给出**，否则两者会各自演化 —— 已经出现过
`error_message` 里存着一版文案、界面渲染着另一版的情况。`stateText` 既用于渲染，也是失败落库时写进
`error_message` 的值，所以存量文案与当前文案不会各说各话；`errorText` 只在存量文案与当前文案**不同**时
才带上，用于保住旧版本写入的失败原因。

`retryable` **不是**它的参数。`state` 优先于 `errorCode`；`errorCode` 决定给「重试上传」还是
「重新选择文件」。原先的 `localAccessMode == NONE` 判据已删除：一行是否还有本地字节可读，由失败阶段
归一化（增量三）写进 `errorCode` 表达，界面不再第二次推断。

| 状态 | 必须入口 | 不出现 |
|---|---|---|
| `PENDING` / `UPLOADING`（未中断） | 删除 | 重试上传、重新选择文件 |
| `UPLOADING` 但已判定中断（增量七） | 重试上传 + 删除 | 重新选择文件 |
| 未知 state | 删除（防御分支，不得成死胡同） | — |
| `UPLOADED` | 删除 | 重试上传、重新选择文件 |
| 删除墓碑已立（`delete_pending` 或检索侧仍 pending） | **只有**重试删除 | 其余全部 |
| `LOCAL_ONLY` / `FAILED` + `URI_PERMISSION_REQUIRED` / `READ_FAILED` | 重新选择文件 + 删除 | 重试上传 |
| `LOCAL_ONLY` / `FAILED` + `HASH_MISMATCH` | **只有**删除；文案指导学生删除后重新添加合规资料（ZLQ-131 PM 裁定，本批不得另拟） | 重试上传、重新选择文件 |
| `FAILED` + `FILE_TOO_LARGE` / `UNSUPPORTED_FORMAT` / `REQUEST_REJECTED` | 重新选择文件 + 删除 | 重试上传 |
| `FAILED` + `AUTH_EXPIRED` | 重新登录 + 删除 | 重试上传 |
| `FAILED` + `ACCESS_DENIED` | 删除；文案点名「联系管理员」 | 重试上传、重新选择文件 |
| `FAILED` + `NOT_FOUND` | 重试上传 + 删除；文案是「云端目录不存在，请重试」 | 重新选择文件 |
| `FAILED` + 临时网络码，自动预算未耗尽 | 重试上传 + 删除 | 重新选择文件 |
| `FAILED` + 临时网络码，预算耗尽、`retryable = false` | **仍保留**重试上传 + 删除 | 重新选择文件 |

三条 ZLQ-130 追加的硬约束：

- **R4**：任何文案**不得**出现「等待上传」。行要么在上传中，要么已经失败并给出出路，「等待」把
  一个需要学生动作的死行说成系统在排队。
- **R5②**：预算耗尽态的文案**不得**出现「自动」或「正在」。告诉学生自动路径已经停了，却用
  「稍后自动重试」「正在重新获取」措辞，就是缺陷本体。
- **`NOT_FOUND` 的映射必须钉死**（ZLQ-136 C2 追加硬约束）：只有 HTTP 404 到达
  `QcaErrorKind.NOT_FOUND` / `SESSION_NOT_FOUND`，两者才落 `SourceErrorCode.NOT_FOUND`；
  404 带 `error.code = identity_not_found` 的仍归 `IDENTITY_INVALID` → `ACCESS_DENIED`。
  否则学生会被告知「云端目录不存在」，而真正要做的是重新登录。两层分别由
  `core:network` 的 `NotFoundStatusMappingTest` 与 `core:database` 的
  `SourceFailureClassifierTest` 钉住 —— `retrofit` 对 `core:database` 不可见，任何一层单独都判不了。

不变式：**任何不在执行中的行至少有一个出口**。「只剩删除」的死胡同是 ZLQ-105 的缺陷本体，
`SourceRowPresenterTest` 对 state × errorCode 全枚举扫描以钉住它，并逐条复刻上表的文案与入口集合。

「重新选择文件」与重试次数**没有先后关系**：源不可读时第一次执行就该出现；真实网络失败时本地源
仍可读，烧完预算也不应让学生去换文件。

## 增量三：分类边界按失败阶段，不按异常继承

根因级结论（ZLQ-110 §3.1）：缺陷不是异常被吞、也不是包装层丢了 cause，而是**读取阶段缺少异常
归一化** + **分类器按继承关系而非失败阶段分类**。`FileNotFoundException` 是 `IOException` 子类，
于是被 `is IOException -> NETWORK_UNAVAILABLE` 的兜底吞掉。

冻结的边界：

| 发生阶段 / 异常 | errorCode → 终态 | 自动重试 |
|---|---|---|
| URI 空、`openInputStream` 返回 null、`FileNotFoundException`、`SecurityException`、`APP_COPY` 文件不存在 | `URI_PERMISSION_REQUIRED` → `LOCAL_ONLY` | 否，**首次即终止，不 enqueue Worker** |
| 本地 InputStream 其它读失败（含暂存复制阶段） | `READ_FAILED` → `LOCAL_ONLY` | 否，首次即终止 |
| 本地内容超上限、格式不支持、哈希不符 | `FILE_TOO_LARGE` / `UNSUPPORTED_FORMAT` / `HASH_MISMATCH` → `FAILED`（`HASH_MISMATCH` 经 `markLocalOnly` 落 `LOCAL_ONLY`） | 否 |
| **本地字节已成功读取之后**，QCA 预签名 / PUT / manifest 阶段的 DNS、连接、超时、408/429/5xx | `NETWORK_UNAVAILABLE` 或既有临时服务码 → `FAILED` | 是 |

硬性口径：**不得再因为某异常「是 `IOException` 子类」就判网络。** `is IOException ->
NETWORK_UNAVAILABLE` 的兜底只对预签名/PUT/manifest 阶段成立；本地读取与暂存两处的失败一律先经
`normalizeLocalReadFailure` 归一，再交给分类器。

同一个 `SocketTimeoutException` 因此在两个阶段有相反判定：读取阶段永久（`READ_FAILED`），
云端阶段临时（`TIMEOUT`）。这是刻意的，`SourceFailureClassifierTest` 用它作对照，防止「把所有失败
都改成不可重试」这类过度修正。

远程文档提供方在读取阶段抛出的泛化 `IOException` 也先归 `READ_FAILED`：当前没有可靠协议区分
「云盘 provider 暂时断网」与「对象已删除」，宁可首次就给人工恢复入口，也不制造 6 次误导性云端重试。

## 增量四：`attempt_count` 与文案口径（修订 ZLQ-86 §3）

- `attempt_count` = **当前自动重试周期内已实际执行的次数，含用户触发的首次执行**。
- 永久性本地读取失败只执行一次：claim 后 `attempt_count = 1`、`retryable = false`、
  `next_retry_at = null`，不 enqueue Worker。
- 临时网络失败沿用最多 `MAX_ATTEMPTS = 6` 次的预算与指数退避。
- **文案不得把「1 次首次执行 + 5 次自动执行」说成「自动重试 6 次」**；预算耗尽时的后缀是
  「已自动重试 `MAX_ATTEMPTS - 1` 次」。
- 人工点「重试上传」开启**新周期**：`requeueForRetry` 把 `attempt_count` 归零、`retryable` 置 1、
  清空 `error_code` / `next_retry_at` / `attempt_token` / `lease_until`，随后重新 claim。
  `sourceId`、`sha256`、`drive_path` **保持不变**，所以重传覆盖同一个 Drive 对象，不产生第二个。

## 增量五：存量死胡同行的对账口径（ZLQ-110 §3.4）

对历史 `FAILED / NETWORK_UNAVAILABLE / retryable = 0` 的行，**不得凭旧 `error_code` 直接改写** ——
那个 code 本身就是错的，用它筛选等于用缺陷筛缺陷。对账器改为**逐个重新探测本地定位符**：

- 打不开 / 无授权 → 归一为 `LOCAL_ONLY` + `URI_PERMISSION_REQUIRED`，UI 恢复「重新选择文件」。
- 仍可读 → **保留**其网络失败不动，靠增量二恢复人工「重试上传」。

**不自动重置并重跑**，否则会把真实已耗尽的网络失败重新拉进无限循环。探测只在
`NET_CAPABILITY_VALIDATED` 联网已验证后进行：离线时云盘 provider 的探测失败与「文件已删除」
无法区分，此时应延后而非改写。

对账规则挂在**唯一**的应用启动对账入口 `StartupReconciler` 上（`app/.../reconcile/`），
与其它业务规则共用身份就绪触发、单飞互斥与失败延后，但各自保有自己的 DAO 条件与状态机。
最终只能有一个启动对账入口，不得出现第二个独立全表 Worker。

## 增量六：清单写入协议与 `index.json` revision 口径（ZLQ-110 §2，ZLQ-104 缺陷本体）

### 六之一：`package.json` / `index.json` 只有一个写入路径

上传提交、生成开始/结束、删除、修复对账、新建学习包，全部经
`ManifestWriter.withPackageLock(identityId, packageId)` 串行提交。任何调用方**不得**把锁外取到的快照
递进来 —— `GENERATING` 被回写成 `READY`、刚提交的资料被下一个写入者丢掉，都是同一个原因：
发布用的是一次过期的读。

包级锁**在锁内重读 Room**。上传只替换 `sources`：`status` / `latestTaskId` 传 null 表示「沿用刚读到的
业务态」，所以生成任务在 PUT 期间把包推进到 `GENERATING` 时，上传提交不会把它拉回来。

`TaskRunner` 的生成开始/结束原先自带一份 `toSourceRefs` 投影并绕过锁发布，现已并入 `ManifestWriter`。
`SourceAssetEntity -> SourceRefJson` 全仓只有一份投影（`ManifestWriter.sourceRef`），不再出现
「同一行按谁最后写清单而有两个 MIME」。

### 六之二：提交协议的顺序，两端都不能挪

```
锁内：countOwnedAttempt 复核令牌 → 计算投影（含本次候选）→ 写 package.json
      → 写 index.json → 回读校验 → markUploaded
```

- `markUploaded` **不得提前**：Room 先说成功、恢复真相还没落地，正是 §2.2 要防的那一半。
- `markUploaded` **不得移到锁外**：先释放锁会让下一个资料在「上一个还没标 UPLOADED」时重算投影，
  把已经在 Drive 上的对象从清单里挤掉 —— ZLQ-104 的缺陷原样晚一个资料复现。
  `ManifestWriterTest` 用 150ms 的 `markUploaded` 延迟把这个窗口撑开钉住；将 `markUploaded`
  移出锁后该用例立刻从 `[src-a, src-b]` 退化成 `[src-b]`（已做过对照验证）。

### 六之三：收录规则不得放宽

清单只收录 `UPLOADED 且 drivePath 非空` 的行，**外加**本次 PUT 刚落地、令牌仍有效的**那一个**候选。
`UPLOADING` 可能还没 PUT，`FAILED` 可能是格式 / 权限 / 哈希失败，两者都不得被宣称为「Drive 上已存在」。
令牌在锁内用 `countOwnedAttempt` 复核：租约被更新的尝试接管后，旧协程无权发布自己的投影。

`countOwnedAttempt` 是一条新 `@Query`，**不是迁移**：Room 的 identity hash 覆盖表结构而非语句，
落它的时候 `version` 仍是 3，`Entities.kt` / `SsDatabase.kt` / `Migrations.kt` / `schemas/` 均未改动。
（本条口径不因 ZLQ-130 的 v4 迁移而失效 —— v4 是**加列**，与「加语句不改 identity hash」是两回事。
反过来说，任何只因新增或搬动 `@Query` 就去改 `version` 的提交都是错的。）

### 六之四：`index.json` 的 revision 计「内容变更」，不计「写入次数」

- 内容未变时**既不重写对象，也不递增 revision**，条目的 `updatedAt` 也不动。
- 三处会让空操作看起来像变更的抖动已分别消除：`package.json` 比较时忽略 `updatedAt`；
  `index.json` 条目内容未变时沿用旧条目（保住它自己的 `updatedAt`）；`index.packages` 按 `packageId`
  排序后比较、`package.json` 的 `sources` 按 `addedAt` 排序，避免「追加候选导致顺序位移」被判成变更。
- 这是「第二次冷启动内容不变、revision 不再增长」（AC-C #7）成立的前提：启动对账每次冷启动都跑，
  空操作若也递增，index 会被无限灌水。

### 六之五：两文件窗口靠收敛，不靠提前报成功

`package.json` 与 `index.json` 不是事务写入，QCA 也不提供条件 PUT（无 ETag / CAS）。因此本契约
**不承诺**跨设备竞态已解决，只承诺本设备可收敛：

1. **过渡态**：`index.json` 写失败或回读不一致 → `ManifestPublishException` →
   `MANIFEST_PUBLISH_FAILED`「文件已上传，但资料清单更新失败，请重试」。文案必须承认对象已在 Drive 上，
   否则学生会去换文件。此时 `markUploaded` **尚未执行**，行仍停在 `UPLOADING` 并保留令牌。
2. **幂等重发**：重传覆盖同一个 Drive 对象（`sourceId` / `sha256` / `drive_path` 不变），
   重新发布的清单是幂等的而非累加的。
3. **回读校验**：写完 `index.json` 再读回，要求 `revision` 不倒退、且该包的 `sourceIds` 与刚提交的
   `package.json` 一致；不一致按发布失败处理。写被 ack 但对象没动（丢写、被其它设备覆盖）在这里被抓住。
4. **冷启动对账**：`ManifestProjectionReconcileRule` 以 `index.json` 为比较基准。`index.json` 在同一次
   提交里写在 `package.json` **之后**，所以它只可能滞后、不可能超前 —— 一次读即可发现全部分歧，
   包括「`package.json` 已领先而 index 写失败」那一种。比较集合取自
   `ManifestWriter.publishedSourceIds`，与发布用的是同一条收录规则，不是第二份过滤器。

冷启动对账**修不了**、也不打算用变通手段修的一类：卸载重装后 Room 已无行，Drive 对象名只带截断哈希，
取不到原始 `displayName` 与完整 `sha256`；据此伪造 SourceRef 等于把编造的元数据写进恢复契约。
这一条属于发布说明，不在本契约的保证范围内。

## 增量七：上传租约与孤儿回收（ZLQ-130）

### 七之一：一行同一时刻只能有一个属主

`source_asset` 在 v3 已有 `attempt_token`（**每次尝试**一个）与 `lease_until`（ZLQ-110 落的）。
v3 → v4 迁移再加八列与一个回收索引：`lease_owner_id`（**每个进程**一个）、`lease_heartbeat_at`、
`upload_started_at`、`last_progress_at`、`interrupt_requested_at`、`remote_generation`、
`delete_pending`、`delete_requested_at`，索引 `index_source_asset_recovery`
(`upload_state`, `delete_pending`, `lease_until`, `next_retry_at`)。迁移是纯加列，无破坏性回退；
存量行的新列为 NULL，一行没有任何租约列时按**已中断**呈现（见七之三），这正是 v3 遗留孤儿的呈现方式。

租约参数冻结为：租约 120 s，心跳 30 s，进度落库节流 15 s / 1 MiB 先到者，无进展判据 10 min，
自动预算 `MAX_ATTEMPTS = 6`。三者的比值是回收规则的前提（租约 = 4× 心跳，心跳 = 2× 节流间隔，
无进展 = 20× 心跳），`UploadLeaseCadenceTest` 钉住参数本身与这些比值，任何一处改动都会让
「租约过期 ≈ 已错过 4 次心跳」这句话失效。

心跳每 30 s 一次：有新进度就 `recordProgress`（刷 `last_progress_at`），没有就 `renewLease`。
两者都带令牌与属主复核，**返回 0 行即视为租约已失**（`LEASE_LOST`），当前协程立即取消，
不得继续 PUT —— 这是「不并发写同一个对象」在客户端侧的全部保证。

### 七之二：进度节流不得把「协程还活着」冒充成字节进展

`last_progress_at` 只在**真的有字节移动**时才刷新。节流判据是纯函数
`PackageRepository.shouldFlushProgress(gateAt, gateBytes, at, sent)`：字节闸门
`sent - gateBytes >= 1 MiB`，或时间闸门 `at - gateAt >= 15 s` **且 `sent > gateBytes`**。
时间闸门上的这个附加条件是硬约束：一个卡死的 socket 仍会持续回调，若时间闸门只看流逝时间，
回调循环就会每 15 s 盖一次 `last_progress_at`，把七之三条件 4 静默废掉。

阶段切换同样刷新进度：读完本地字节并算完哈希后一次，PUT 落地后一次。50 MiB 的读取与哈希是
**无声的工作**，不盖时间戳的话慢设备会一直停在 claim 的时间点上，被 10 min 规则误判为卡死。

### 七之三：什么时候算中断，什么时候才允许接管

`SourceRowPresenter.isInterrupted` 判定「这行已经不是该等下去的上传」，四个条件任一成立即中断：
显式收到停止请求（`interrupt_requested_at` 非空）、**没有任何租约列**、`lease_until < now`、
或最后一次进展距今超过 10 min。

**呈现为中断 ≠ 可以回收。** 回收只在设计 §3.2 的四个条件下发生，且**只能由 recovery coordinator
执行**（单飞，同一时刻最多一趟）；界面**不得**自己 `tryLock` 探活，也不得据「租约过期」直接抢行：

| 条件 | 判定依据 | 动作 | 日志 reason |
|---|---|---|---|
| 1 | 无属主记录 | 释放，可被重新 claim | `ORPHAN_NO_OWNER` |
| 2 | 另一属主的**内核文件锁可被本进程取得** | 该进程确已消失，释放 | `ORPHAN_DEAD_OWNER` |
| 3 | 属主是本进程但已无在册尝试 | 释放 | `ORPHAN_NO_OWNER` |
| 4 | 属主存活，但整整 10 min 无进展 | 释放 | `NO_PROGRESS` |
| **不得回收** | 属主存活、执行锁仍被持有、本趟未取得 source 执行锁 | **只写 `interrupt_requested_at`**，界面呈现「上传中断」 | `OWNER_ALIVE` |

属主存活性用 OS 级 `FileChannel` 排他锁判定（`ProcessLeaseRegistry`，锁文件在 `noBackupFilesDir`）：
**能锁上别人的锁文件本身就是那个进程已经消失的证据**。同一 JVM 内的重入会抛
`OverlappingFileLockException`，一律按「存活」处理，不得当成死亡。

条件 2 的存活进程复检由 fake-clock 测试 `liveProcessRechecksAtEarliestLeaseDeadline` 钉住。
**不得**播种一个一开始就已死的属主来「证明」回收生效 —— 那证明的是锁能取得，不是回收判据正确。

### 七之四：删除是墓碑，不是第二次删除事务

删除先立 Room 墓碑（`delete_pending`），再走 `source-delete-<sourceId>` 唯一 Work，
`ExistingWorkPolicy.KEEP`。墓碑一旦立起就**覆盖整张矩阵**：这行只欠「重试删除」，
重试走的是同一个 Work 名，不产生第二个删除事务，也不产生第二次云端 DELETE 之外的副作用。

### 七之五：日志是事后唯一的证据，字段表冻结

`source_recovery` / `source_lease` / `source_delete` 三种行**只在
`core/upload/SourceRecoveryLog.kt` 一处格式化**。触发集与 reason 集都是封闭枚举、显式 `wire` 值：
字符串要进人读的日志，**不得**用反射名（`this::class.simpleName` 之类）—— R8 会重命名它，
线上读到的值和代码里写的值就不是一回事；也**不得**为保住反射名去加 keep 规则。

`lease_owner_id` 与 `attempt_token` 是唯一带识别性的两个值，一律经 `hash8`（SHA-256 前 8 位）落日志，
缺失记 `-` 而非空串的哈希。日志中**不得**出现 PAT、`Authorization`、预签名 URL、完整本地 URI、
文件字节或属主 UUID 原文。`SourceRecoveryLogTest` 逐条钉住行形状、`-` 语义、hash8 的稳定性与可区分性、
封闭取值集，以及「没有任何生产文件自己手搓一行日志」。

### 七之六：服务端 fencing 本批不做（ZLQ-136 C1 / C3 / C5）

设计 §8 的「服务端 stage 先行」（`beginSourceAttempt` / `commitSourceAttempt` / `getSourceAttempt` /
`deleteSource`、单调 generation、清单 revision CAS、持久墓碑）**本批不立项、不实现**。因此：

- **AC-5 记「降级验收 = 最终一致，允许短暂重复/覆盖风险」**，本批既不判通过也不判失败，标「已知平台缺口」。
- 客户端**只落保守路径**：**不得实现「立即抢活租约」**。上表「不得回收」分支在本批**持续成立**，
  即 `lease_until < now` 但外进程属主锁仍被持有且 source 执行锁未取得时，只写
  `interrupt_requested_at`，**不得并发 PUT**。
- `remote_generation` 列随 v4 迁移落库，但**本批恒为 NULL**：**禁止**写任何本地替代值或伪 generation
  （C3）。AC-1 的迁移前后不变量清单不含这一列。
- 依赖服务端契约的三个测试 `beginSourceAttemptReplayIsIdempotent`、
  `staleCommitCannotPromoteOrAdvanceRevision`、`deleteTombstonePermanentlyRejectsOldGeneration`
  本批标**不可执行**，既不判通过也不判失败，且**不得以客户端 fake 服务端实现冒充通过**（C5）。
- 客户端侧四个 fencing 测试保留为必测并已通过：`lateGenerationCannotCommitAfterReclaim`、
  `concurrentRecoveryClaimsExactlyOnce`、`staleGenerationCannotAdvanceManifestRevision`、
  `deleteTombstoneFencesInFlightCommit`。其结论只能写成**「本地 fencing 闭合（AC-5a）」**，
  不得写成「AC-5 通过」。
- **不得以「本地单测模拟锁」冒充远端原子性证明**：本地锁证明的是本设备内的互斥，跨设备竞态
  仍按增量六之五「靠收敛，不靠提前报成功」处理。
