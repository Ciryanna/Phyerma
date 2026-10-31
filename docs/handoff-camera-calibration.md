# 交接：「校准」面板与相机时间戳校准

日期：2026-10-30。范围：底栏中间的「校准」面板、相机时间戳校准（敲桌子那个）、校准信息写入导出 meta。
状态：**审查出的问题已全部修改并有单元测试；真机上的敲击流程尚未验证。** 改动未提交。

## 1. 定位：校准 = 设备特征信息面板

底栏中间一栏不再只是一个测试，而是「这台手机的特征信息 + 各种校准测试」：

- **这台手机**：品牌型号、Android 版本 / API / 系统皮肤、phyphox 传感器库匹配结果、重力静止值 / σ / 采样率。
- **校准测试**：目前只有「相机时间戳」一项（以后新增测试放这里）。
- 每一项都有一个「写入导出的 meta」勾选框。**勾上的项目会写进每次导出的 meta。** 默认不勾；唯一例外：第一次保存相机校准时自动勾上它（之后用户取消会被记住），避免测出来的值被悄悄漏掉。
- 「传感器」页不再放设备卡片，只留传感器列表。

代码位置（`app/src/main/java/de/rwth_aachen/phyphox/`）：

| 文件 | 作用 |
|---|---|
| `device/CalibrationActivity.java` + `res/layout/activity_calibration.xml` | 面板本身 |
| `device/CameraClockTestActivity.java` + `res/layout/activity_camera_clock_test.xml` | 相机时间戳敲击测试（原先的 `CalibrationActivity`） |
| `device/CameraClockPairing.java` | 配对数学（纯函数：`shakePeaks` / `pair` / `median` / `framePeriodNs`） |
| `device/CalibrationStore.java` | SharedPreferences `phyphox`：按镜头存偏移 + 各项的导出开关 `calib_include_<item>` |
| `device/ProfileMeta.java` | 把勾选的项目整理成 meta 行 |
| `DataExport.java` | 有勾选项时导出 zip 里多一个 `meta/calibration.csv` |
| `RemoteServer.java` | 网页端 `/meta` 同样带 `calibration` 对象 |
| `camera/CameraInput.kt` | 每次（重新）启动相机时读取当前镜头的偏移，交给渲染器和 `VideoInput` |
| `camera/VideoInput.java` | `video.json` 恒带 `cameraClock` 块 |

## 2. 相机时间戳校准怎么工作

1. 用户敲机身，加速度计出现尖峰，相机画面同时抖动。
2. 每次敲击把「加速度起点」和「亮度跳变的那一帧」配成一对：`bias = 帧时间戳 + 半帧 - 加速度起点`。
3. 所有配对列成复选框，建议值是选中项中位数取负，可手改，点「应用」后存入 `CalibrationStore`。
4. 实验里相机（重新）启动时读取该镜头的偏移，加到每个帧时间戳上（`<camera>` 的 `t` 和视频 PTS 同一个值）。

符号链条、两遍配对（中位数锚点）、每一对可排除，审查时已确认是对的。

## 3. 审查问题的处理结果

| 编号 | 问题 | 处理 |
|---|---|---|
| 3.1 | 文案写「至少 3 次、自动应用」，代码要 5 对、手动应用；另有 8 个闪光灯法残留字符串 | 文案改为 5 次 + 手动「应用」（en / zh / zh-rCN）；`few_taps` 带 `%1$d`/`%2$d`；残留字符串已删除 |
| 3.2 | `SENSOR_DELAY_GAME`（~50 Hz）让起点系统性偏晚 ~10 ms | 改 `SENSOR_DELAY_FASTEST` |
| 3.3 | 导出里看不出应用了多少偏移 | `video.json` 恒有 `cameraClock {calibrated, lens, biasNs, calibratedAtMs, samples, note}`；勾选后 `meta/calibration.csv` 和远程 `/meta` 也有 |
| 3.4 | 重测时残留旧勾选项 | `startMeasurement()` 清空并隐藏配对列表和应用按钮 |
| 3.5 | 注释里同一台 vivo 三个不同数字 | 统一在 `CalibrationStore` 的注释里：数字来自不同测量，不是规格 |
| 3.6 | 只有一个全局偏移，无清除入口 | 按镜头分别存（后置沿用旧键 `calib_camera_bias_ns`，旧校准仍有效；前置加 `_front`）；面板有「清除」按钮；测试页可选前 / 后置 |

顺带修复：保存的样本数是勾选的配对数（不是敲击数）；接受小数逗号输入；`lumaSeries` / `lumaTimes` 访问加 `synchronized`；测试页适配边到边显示（返回按钮不再压在状态栏上）。

## 4. 导出里新增的内容

勾选后 `meta/calibration.csv`（`"property","value"`）例如：

```
"camera_clock.back.bias_ms","200.000"
"camera_clock.back.samples","7"
"camera_clock.back.measured_at","..."
"device.os_skin","..."
"device.db_match","..."
"device.db_note","reference only; not applied to any reading"
```

勾了但没测过的项会写 `camera_clock.status = not calibrated; …`，不会沉默。库里的参考值（`device.db_*`）只作记录，**不会被用来修正任何读数**（项目规则：校正必须用户可见）。

## 5. 已知限制（不一定要修）

- 每一对约 ±半帧不确定度，靠多次取中位数压；强光下曝光远短于帧周期，会多出最多半帧偏差。
- 相机对着很平的天花板时，亮度抖动可能达不到 `LUMA_JUMP = 1.8`，检测会失败。
- 相机时钟整个错位（几十秒以上）时，渲染器里 `timestampDelta > 60 s` 的兜底会把校准抵消，且这种机型的敲击页配不上对（窗口只有 900 ms）。

## 6. 验证情况

- 单元测试：`CameraClockPairingTest`（配对数学）、`CalibrationMetaTest`（勾选开关、按镜头存储与清除、旧键兼容、`ProfileMeta` 行、导出 `meta/calibration.csv` 仅在勾选时出现、`video.json` 的 `cameraClock`）。全套单元测试通过。
- 模拟器（API 36，中文）：面板、勾选后持久化、敲击测试页、传感器页均已看过。
- **没有验证**：真实的「敲击 → 相机抖动」配对。模拟器的虚拟场景不会对敲击产生真实画面抖动，量出来的值没有物理意义。需要真机（vivo V2452A 等）测一遍；前置相机同样没有真机数据。

## 7. 仓库与环境状态

- 分支 `master`，远程 `origin` = `https://github.com/Ciryanna/Phyerma.git`（HTTPS；这台机器的 SSH 公钥被拒）。
- 最近提交 `c5976e74`（一键导出，单一 CSV zip）。本次改动和本文件**尚未提交**。
- 构建：Gradle 需要 `JAVA_HOME`，用 Android Studio 自带的 JDK：`C:\Program Files\Android\Android Studio\jbr`。
  - 单元测试：`.\gradlew.bat :app:testRegularDebugUnitTest`
  - 调试包：`.\gradlew.bat :app:assembleRegularDebug`
- 模拟器：AVD `Phyerma_API36`（SDK 在 `C:\Users\elwin\AppData\Local\Android\Sdk`），无窗口启动：`emulator -avd Phyerma_API36 -no-window -no-audio -gpu swiftshader_indirect`。`adb exec-out screencap -p` 截图，`uiautomator dump` 取控件坐标；点击要用 dump 里的真实像素坐标（截图是缩小过的）。

## 8. 其他待办（与校准无关）

- 网页远程下载的 `Content-Disposition` 里中文文件名是乱码；改成标准的 `filename*=UTF-8''…`（`RemoteServer.handleExport`）。
- 导出的数值现在是科学计数法（`3.000000000E0`），若要更干净的普通小数，会影响所有已有导出，需要先确认。
- 清掉 `.gitmodules` 里残留的 `paho.mqtt.android`。
- `experiments` 子模块里的「电梯受力」实验提交 `83f5ade` 已推到 fork 的 `master`；`remote` 子模块的紫色主题提交 `2670d37` 同理。
