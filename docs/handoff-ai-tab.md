# 交接：右侧第三栏「AI」

底部导航现在是 实验 / 校准 / AI。原来的「传感器」页挪进了「校准」面板里（面板上的传感器卡片 → `SensorLabActivity`，返回键回面板）。

## 1. 它做什么

用文字或语音和模型对话，模型写出 phyphox 实验 XML，应用校验后直接存进实验列表：

- 目标选「✨ 新建实验」：新建一个实验（新 uuid 文件）。
- 目标选某个已有实验：把当前文件交给模型，模型返回**完整**的修改后文件，覆盖原文件。内置实验（assets）只读，改动会另存为副本。
- 每次写入后出现结果卡片：**打开**、**撤销**。撤销只对最新一次生效（更早的一次是后面改动的基础）。
- 写入后目标自动变成刚写的这个实验，所以可以接着说「再加一个……」。
- 模型：DeepSeek V4.1 Flash（API 模型名 `deepseek-flash`），`POST https://api.deepseek.com/chat/completions`。用户在 AI 页自己填 API 密钥（首次进入会弹窗，说明发送了什么）。**必须关闭 thinking**（`"thinking":{"type":"disabled"}`），否则整个 token 预算被推理吃掉、要等几分钟；关掉后一般 7–25 秒出结果。

## 2. 代码在哪

`app/src/main/java/de/rwth_aachen/phyphox/ai/`：

| 文件 | 作用 |
| --- | --- |
| `AiActivity` | 界面：气泡、结果卡片、目标选择、密钥弹窗、麦克风（`RecognizerIntent`，没有识别器的手机提示用键盘上的麦克风）、键盘出现时隐藏底部导航 |
| `AiController` | 单例，持有聊天记录、目标、会话、忙碌状态。底部导航切页会 finish 掉 Activity，所以状态不能放在 Activity 里。网络和校验跑在一个工作线程，校验回主线程执行 |
| `AiSession` | 一次对话：把模型返回的文件交给 `Validator`；解析器报错就把报错原文当作用户消息发回去，最多自动修正 2 次（`MAX_FIXES`）；输出被截断（代码块没关）也会让它重发 |
| `AiReply` | 把回答拆成说明文字 + xml（取最后一个含 `<phyphox` 的围栏块；围栏没关 = 截断） |
| `AiPrompt` | 系统提示 = `assets/ai/system_prompt.txt` + 这台手机的传感器/麦克风/GPS 清单 + 任务（新建，或带上要修改的当前文件） |
| `DeepSeekClient` | HTTP；401/402/429 在 `AiController` 里映射成可读提示 |
| `ExperimentStore` | 列表、读、新建、替换（旧文件备份到 `filesDir/ai_backup/`，原子写入，资源文件夹按新内容的 CRC32 改名）、撤销 |
| `AiKeyStore` | 密钥存 `phyerma_ai` 偏好文件；`res/xml/backup_rules.xml`、`data_extraction_rules.xml` 把它排除出云备份和设备迁移 |

校验用的是**真正的解析器**：`PhyphoxFile.checkXml(context, xml)`（返回 null 表示能加载，否则是解析器的原话）。它用 `Experiment.forValidation(context)` 造一个不显示的宿主，`validationOnly=true` 时权限检查一律视为已授权（只判语法，不因为没授权而拒绝）。传感器是否存在仍按这台手机真实判断——手机上没有的传感器会报「传感器不可用」，这是有意的，报错会回到模型那里。

## 3. 系统提示（`assets/ai/system_prompt.txt`）

分析模块那一节是从 `PhyphoxFile.analysisBlockParser` 里真正的输入/输出映射表抽出来的（`scratchpad` 里有当时的 `modules.py`）。要点：

- 标 `!` 的输入/输出必须写 `as=…`；其余按位置。
- `if` 用 `a`/`b`/`true`/`false` 和 `less`/`equal`/`greater` 属性，没有 `condition`，没有 `loop` 模块。
- `formula` 的输入按位置，公式里用 `[1]`、`[2]`；没有 `mod`、`pi`、`e`。
- 提示里明确禁用了不好用的模块（loop、interpolate、loess、periodicity、eventstream、reduce、imagedecode、map、info、sum、split）。
- 反复出现的错误：模块输出的容器没在 `data-containers` 里声明。提示里已强调；应用内的自动修正循环会兜底。

改提示或解析器之后，用 `AiGeneratedLoadTest` 回归：把模型生成的 `.phyphox` 放进一个目录，设置环境变量 `PHYERMA_AI_GEN_DIR`，`./gradlew.bat :app:testRegularDebugUnitTest --tests '*AiGeneratedLoadTest*' --rerun-tasks`，每个文件打印 `AIGEN <name> -> OK|FAIL: …`（没设环境变量时跳过，CI 不需要网络和密钥）。最近一轮（8 个不同需求，不带自动修正）：5/8 一次通过，其余 3 个全是「容器未声明」，自动修正循环可以处理。

## 4. 验证情况

- 单元测试：`AiReplyTest`、`AiSessionTest`（重试、回滚、截断、历史上限）、`DeepSeekClientTest`、`ExperimentStoreTest`（新建/替换/撤销、资源文件夹跟随）、`PhyphoxFileCheckXmlTest`。全套通过。
- 模拟器（API 36，中文）端到端：弹出密钥窗口 → 保存 → 输入英文需求 → 真实 DeepSeek 调用 → 应用内校验通过 → 实验出现在列表并能打开 → 再说「改成绿色并加标准差」→ 修改成功，聊天在切页后保留。
- 发现并修复：键盘弹出后输入栏消失（`layout_above` 锚点是 GONE 的底部导航，RelativeLayout 会把它放到 0 位置）。现在键盘出现时输入栏改为贴底。
- **没有验证**：语音输入（模拟器没有识别器）；真机上的键盘/手势导航边距；模型回答的物理是否正确（只验证文件能加载，不验证实验设计是否合理，用户需要自己跑一下）。

## 5. 隐私与安全

- 发送内容：用户消息、被选中的实验文件、这台手机的传感器清单。弹窗里已说明。
- 密钥只存本机，不进备份。开发时测试用过的密钥**没有**写入仓库、测试或提交。

## 6. 可能的后续

- 流式输出（现在等整段回答）。
- 让用户在 AI 页选择模型（目前固定 `deepseek-flash`，更强的是 `deepseek-v4-pro`，但更慢）。
- 对「容器未声明」做确定性自动补全，省一轮往返。

## 7. 切页动画

三个底部栏目是三个独立 Activity，系统默认的滑动切换会让相同的页眉/底栏跟着跳。现在：`BottomNavHelper.fade(activity)` 在每次切页后调用 `overridePendingTransition(tab_fade_in, tab_fade_out)`（140 ms 交叉淡入淡出，`res/anim/`）；`CalibrationActivity`、`AiActivity` 重写 `finish()` 用同一动画，所以返回键也一致。校准页首次打开原来要在主线程解析 3 MB 的设备库（模拟器上约 1.1 s），现在放到后台线程，卡片先显示系统信息，匹配结果随后补上（首次打开约 0.4 s）。

## 8. 语音输入

麦克风按钮的逻辑（`AiActivity.listen()`）：
1. 设置了「语音转文字密钥」→ 应用内录音（`VoiceRecorder`，16 kHz 单声道，最长 60 秒；点第二次结束）→ `SpeechClient` 把 WAV 以 multipart 发到 `https://api.siliconflow.cn/v1/audio/transcriptions`（模型 `FunAudioLLM/SenseVoiceSmall`，OpenAI 兼容接口）→ 文字填进输入框。
2. 没有密钥 → 先试系统识别器（`RecognizerIntent`）；手机上没有识别器（没有 Google 服务的国产机常见）→ 弹出密钥窗口，说明录音会发到 api.siliconflow.cn。
3. 长按麦克风随时修改/删除语音密钥。密钥与 DeepSeek 密钥同在 `phyerma_ai` 偏好文件里，同样不进备份。

验证：`SpeechClientTest`（WAV 头、multipart 上传走本地套接字、错误解析）；模拟器上走通了 授权 → 录音（按钮变红）→ 上传 → 假密钥得到服务端 401 并提示「语音服务不接受这个密钥」，说明网络和接口形状是通的。**没有验证**：用真实密钥得到转写文字（没有可用的语音密钥）；想换服务商只需改 `SpeechClient.ENDPOINT`/`MODEL`。
