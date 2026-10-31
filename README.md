# Phyerma

**A fork of phyphox, with AI in the lab, and a real fit for the phone.**

Phyerma keeps [phyphox](https://phyphox.org), the open-source physics app from RWTH Aachen that people use around the world: a phone as a pendulum, a magnetometer, a sonar. Two things are being added on top of that.

**Fit more phones, especially phones from China.** Sensor errors have already shown up on them. An experiment that trusts the wrong reading teaches the wrong result. The fix is for that phone — Xiaomi, OPPO, vivo, Honor, Huawei, and the phones around them — and you can see it. It stays off until you turn it on. HarmonyOS is a different system, so it gets its own track.

**AI inside the experiment.** This is the part meant to be used every time you open the lab. Describe the measurement in ordinary language, and the app drafts it on the phone: the sensors, the analysis, the screen. You check the draft and save it there, with no desktop editor. Press a graph, a formula, or a button, and it tells you what the number is, what a sane result looks like, and what this phone tends to do.

Source: [github.com/Ciryanna/Phyerma](https://github.com/Ciryanna/Phyerma) · Application id: `com.phyerma.app` · License: [GPL-3.0](LICENSE)

中文说明在 [下方](#中文)。路线图（含尚未进安装包的功能）在 [ROADMAP.md](ROADMAP.md)。

## Today

This build already does the following.

- **Experiments.** Ready-made physics experiments from the phyphox library: acceleration, rotation, magnetic field, light, pressure, proximity, sound, GPS, camera, depth, and Bluetooth instruments. Import a `.phyphox` file or a QR code. One tap exports a zip of plain CSV files, with device and timing metadata and any recorded video or sync audio alongside.
- **This phone.** A device page with brand, model, and the system skin (HyperOS, MIUI, ColorOS, OriginOS, Funtouch, MagicOS, HarmonyOS / EMUI, Flyme, One UI, and others).
- **A baseline, not a hidden scale factor.** The page matches this model against a local snapshot of the crowd-sourced [phyphox sensor database](https://phyphox.org/sensordb/). Rest-g, noise, and rate are shown as a reference. They are not applied to experiment readings.
- **Sensor lab.** Every sensor Android exposes, in SI units, with a two-second window, measured noise, measured rate, and the range Android claims — labeled as a claim.
- **Remote screen.** Opt-in. A browser on the same network can view and control the experiment. Nothing to install on the computer.
- **Chinese and English.**
- **Quiet by default.** No Google Play Services, Firebase, Crashlytics, or advertising SDK. Sensor readings stay on the phone unless you export them, turn on remote access, or start an experiment that itself opens a network connection.

Refresh the bundled database snapshot with `python tools/import_phyphox_sensordb.py`. Per-sensor research notes, including the rule that a correction may not silently change a reading, live in [docs/sensors](docs/sensors/README.md).

## Where it is going

Phone adaptation and AI assistance are the product. They are written down so the direction is public. They are **not in the current build**.

1. **AI drafts the experiment on the phone.** Describe the measurement in ordinary language. Phyerma drafts the sensors, the analysis, and the view. You preview it and save it on the device. A class can add an experiment without a desktop editor.
2. **AI explains the control you press.** A trace, a formula, a sensor channel, or a button. What the number is, what a sane result looks like, and what this phone model tends to do. The assistance sits on the device that is running the experiment.
3. **Calibration you can read.** Per-brand packs for Xiaomi (including Redmi and POCO), OPPO (including OnePlus and realme), and vivo (including iQOO), plus Honor and Huawei, aimed at sensor errors that have already shown up on phones from China. HarmonyOS and HarmonyOS NEXT are their own track: their own sensor APIs and their own test phones. Every coefficient is a switch: raw value, corrected value, the number used, and where it came from. Off until you turn it on.

Also on the roadmap: the noise and the crowd baseline on the experiment screen itself, a QR that hands your new experiment to the next phone with no account, and school packs (pendulum, free fall, speed of sound, coil, spring) with the brand note attached. Detail and status: [ROADMAP.md](ROADMAP.md).

## Build

```text
gradlew.bat :app:assembleDebug
```

The debug application id is `com.phyerma.app`.

## Credits and marks

Phyerma is a fork of [phyphox-android](https://github.com/phyphox/phyphox-android), copyright 2016 Dr. Sebastian Staacks, 2nd Institute of Physics, RWTH Aachen University, released under the GNU GPL since phyphox 1.1.0.

**The names “phyphox” and “RWTH Aachen University”, and the RWTH Aachen logo, are trademarks of the upstream project.** Phyerma is not published by RWTH Aachen, and this repository is not the phyphox app-store build.

Sensor-database snapshots are crowd-sourced by phyphox users. FFTW, jlhttp, ZXing, Eclipse Paho, and Chart.js keep the licenses recorded by upstream.

---

## 中文

**Phyerma 是 phyphox 的分支：实验里有 AI，手机也要真正适配。**

它保留 [phyphox](https://phyphox.org)。phyphox 来自亚琛工业大学，开源，在全球使用：手机可以是单摆、磁力计、声纳。Phyerma 在这之上加两件事。

**适配更多手机，尤其是来自中国的手机。** 这些机器上已经出现过传感器错误。实验如果信任了错误读数，教出去的就是错的结果。校正针对那一台手机——小米、OPPO、vivo、荣耀、华为，以及周围的机型——而且你看得见。默认关闭，直到你打开。鸿蒙是另一套系统，单独成线。

**实验里的 AI。** 这是每次打开实验室都用得上的部分。用白话描述要测什么，应用在手机上起草实验：传感器、分析、界面。你核对草稿，直接保存在手机上，不用电脑编辑器。按住一条曲线、一个公式或一个按钮，它说明这个数是什么、怎样算正常、这台手机通常会怎样。

### 现在就能用

- **实验。** phyphox 实验库：加速度、转动、磁场、光、气压、距离、声音、GPS、相机、深度、蓝牙仪器。可导入 `.phyphox` 或扫二维码。一键导出一个 zip：纯 CSV 数据，附设备和时间元数据，录过的视频和同步音频也一起带上。
- **这台手机。** 设备页写出品牌、型号和系统皮肤（HyperOS、MIUI、ColorOS、OriginOS、Funtouch、MagicOS、鸿蒙 / EMUI、Flyme、One UI 等）。
- **基线只作对照。** 用本地的 [phyphox 传感器库](https://phyphox.org/sensordb/) 快照匹配型号，显示静止重力、噪声和采样率。这些数**不会**被悄悄乘进实验读数。
- **传感器实验室。** Android 暴露出的每一项传感器，SI 单位，两秒窗口，实测噪声，实测速率，以及 Android 自称的量程——标明这是自称。
- **远程画面。** 需要你自己打开。同一网络里的浏览器可以看、可以控，电脑上不用安装软件。
- **中文和英文。**
- **默认安静。** 不用 Google Play 服务、Firebase、Crashlytics，也没有广告 SDK。除非你导出、打开远程访问，或运行一个自己会联网的实验，传感器读数留在手机上。

众包库快照用 `python tools/import_phyphox_sensordb.py` 刷新。每个传感器的研究笔记，以及“没接入之前不得静默改读数”的约定，在 [docs/sensors](docs/sensors/README.md)。

### 正在做（当前安装包里还没有）

手机适配和 AI 辅助是产品本身。

1. **AI 在手机上起草实验。** 用白话描述要测什么。Phyerma 起草传感器、分析和界面，你在手机上预览并保存。课堂上加实验，不用电脑编辑器。
2. **AI 解释你按住的地方。** 曲线、公式、传感器通道或按钮。这个数是什么、怎样算正常、这台手机通常会怎样。辅助就在正在跑实验的那台设备上。
3. **看得懂的校准。** 针对中国手机上已经出现过的传感器错误，小米（含 Redmi、POCO）、OPPO（含一加、真我）、vivo（含 iQOO）分品牌做，荣耀和华为同样单独成包。鸿蒙和鸿蒙 NEXT 是另一条线：另一套传感器接口、另一组测试机。每个系数都是开关：原始值、校正值、用了哪个数、数从哪来。默认关闭，直到你打开。

同一条路线上还有：实验画面上直接看到噪声和众包基线；一个二维码把刚做好的实验交给下一台手机，不需要账号；单摆、自由落体、声速、线圈、弹簧等课程包，并附上品牌备注。状态表见 [ROADMAP.md](ROADMAP.md)。

### 名称

Phyerma 派生自 [phyphox-android](https://github.com/phyphox/phyphox-android)（Sebastian Staacks 博士，亚琛工业大学第二物理研究所，自 phyphox 1.1.0 起以 GNU GPL 发布）。

**“phyphox”和“RWTH Aachen University”以及亚琛工大标识仍是上游项目的商标。** Phyerma 不是亚琛工大发布的，这个仓库也不是应用商店里的 phyphox。
