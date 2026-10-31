# Phyerma roadmap

Two directions: fit more phones, especially phones from China where sensor errors have already shown up, and put AI in the lab — draft an experiment on the phone, and explain the control you press.

Status words used below:

| Status | Meaning |
|---|---|
| **In this build** | You can do it in the app today. |
| **Designing** | The behavior is decided enough to build. It is not in the app yet. |
| **Planned** | We intend to build it. The design is still open. |

Nothing in **Designing** or **Planned** is a claim about the current APK.

## In this build

- phyphox experiment library, `.phyphox` / QR import, one-tap export as a zip of CSV files (data, metadata, recorded video and sync audio)
- Device page: brand, model, OS skin
- Local snapshot of the phyphox sensor database, shown as a baseline
- Sensor lab: SI units, two-second window, measured noise and rate, Android-claimed range labeled as a claim
- Opt-in remote access on the local network
- Chinese and English UI
- No Play Services, Firebase, Crashlytics, or ads

Research log: [docs/sensors](docs/sensors/README.md). A correction stays out of the experiment path until that sensor's note says it is connected, and until the user can see it.

## 1. Calibration you can read

**Status: Designing**

The phone's vendor stack changes the number. Phyerma will ship brand packs, not one global fudge factor.

| Pack | Phones | System |
|---|---|---|
| Xiaomi | Xiaomi, Redmi, POCO | MIUI, HyperOS |
| OPPO | OPPO, OnePlus, realme | ColorOS |
| vivo | vivo, iQOO | Funtouch, OriginOS |
| Honor | Honor | MagicOS |
| Huawei | Huawei | EMUI, HarmonyOS on Android-compatible devices |

**HarmonyOS is a separate track**, including HarmonyOS NEXT. It does not go through Android `SensorManager` the way the packs above do, so it gets its own sensor layer, its own test phones, and its own releases. A skin guess on an Android build is not a HarmonyOS port.

Each correction, when it exists, will show:

- the raw reading
- the corrected reading
- the coefficient
- the source: a measurement you just took on this phone, or a named crowd baseline
- a switch, **off until you turn it on**

Crowd rest-g, noise, and rate stay reference values. They do not become a silent scale factor.

## 2. Experiment studio on the phone

**Status: Designing**

You describe the experiment in ordinary language (“period of a pendulum from the accelerometer”, “sound speed down a hallway”). On the phone, Phyerma drafts:

- which sensors
- the analysis steps
- a view: value, graph, or both

You preview the draft, change the obvious fields, and save it as an experiment on the device. You can still export a `.phyphox` file and open it on another phone.

The desktop editor remains for people who want the full toolbox. Adding an experiment in class no longer requires it.

The draft step can use a model. The save format is still the experiment file. A draft is something you can read before you run it.

## 3. An explanation on every control

**Status: Designing**

Press a trace, a formula, an axis, a sensor channel, or a button. A short panel answers three questions:

- What is this quantity?
- What does a sane result look like?
- What does this phone model tend to do (noise, rate, missing sensor, vendor naming)?

Built-in experiments get written explanations in the app, so this works with no network and no model. An optional model answers a follow-up in the same panel. Turning the model off does not remove the written note, and does not stop the experiment.

Sensor data is not sent to a model unless you ask for that follow-up.

## 4. The number and its shadow

**Status: Planned**

On the experiment screen, next to the value: measured noise, measured rate, and — when this model is in the database — the crowd baseline. The Android-claimed range stays in the sensor lab, marked as a claim.

## 5. Pass the experiment with a QR

**Status: Planned**

An experiment saved in the studio becomes a QR. The next phone opens it. No account, no store listing, no desktop step.

## 6. Your model, your key

**Status: Planned**

Explanations and drafts can run against a model on the device, or a provider you configure with your own key. The default install does not upload sensor streams to Phyerma. There is no Phyerma account in this plan.

## 7. School packs

**Status: Planned**

Short experiments a class can run in one period, with the brand note attached:

- pendulum and local *g*
- free fall
- speed of sound
- magnetic field of a coil
- spring period

Each pack uses the explanation panels, so a student can ask what a number means without leaving the run.

## What will not change

- Readings are not rewritten in secret to look closer to a textbook value.
- HarmonyOS NEXT is not described as supported until a build actually runs there.
- Optional models do not become a requirement to open, run, or export an experiment.

---

## 中文

两条方向：适配更多手机，尤其是已经出现过传感器错误的中国手机；把 AI 放进实验——在手机上起草实验，并解释你按住的控件。

状态：**已在此版本** / **设计中**（行为已定，还没进应用）/ **计划中**（打算做，设计未定）。后两类都不是对当前安装包的承诺。

### 已在此版本

实验库、导入与导出、设备页、众包库对照、传感器实验室、可选的局域网远程、中英界面、无 Play 服务 / Firebase / Crashlytics / 广告。校正在研究笔记写明已接入、并且用户看得见之前，不进入实验路径。

### 1. 看得懂的校准（设计中）

按品牌做包，不做全局凑数：小米（含 Redmi、POCO）、OPPO（含一加、真我）、vivo（含 iQOO）、荣耀、华为。

**鸿蒙单独成线**，包括鸿蒙 NEXT。它不走上面那些包所用的 Android `SensorManager`，所以单独的传感器层、单独的测试机、单独的发布。Android 版本里猜到系统皮肤，不等于已经移植到鸿蒙。

每个校正都会给出原始值、校正值、系数、来源（你刚在这台手机上测的，或点名的众包基线），以及一个**默认关闭**的开关。众包的静止重力、噪声和采样率继续只当对照。

### 2. 手机上的实验工作室（设计中）

用白话描述要测什么。手机上起草传感器、分析步骤和界面，预览后保存。仍可导出 `.phyphox` 给另一台手机。电脑端编辑器还在，但课堂里加实验不再依赖它。起草可以用模型；保存下来的仍是可读的实验文件。

### 3. 每个控件都能解释（设计中）

按住曲线、公式、坐标轴、传感器通道或按钮，回答三件事：这个量是什么、怎样算正常、这台手机通常会怎样。内置实验自带写好的说明，没有网络、没有模型也能看。可选模型只回答追问。关掉模型，说明还在，实验也能跑。只有在你要求追问时，传感器数据才会被送到模型。

### 4. 数字和它的影子（计划中）

实验画面上同时看到数值、实测噪声、实测速率，以及库里有这台机型时的众包基线。Android 自称量程留在传感器实验室，并标明是自称。

### 5. 用二维码把实验交出去（计划中）

工作室里保存的实验生成二维码，下一台手机直接打开。不需要账号，也不需要电脑。

### 6. 模型用你自己的（计划中）

解释和起草可以走手机上的模型，或你自己配置密钥的服务。默认安装不会把传感器流上传给 Phyerma。这个计划里没有 Phyerma 账号。

### 7. 课程包（计划中）

一节课能做完的短实验，并附上品牌备注：单摆与当地重力加速度、自由落体、声速、线圈磁场、弹簧周期。学生不离开实验就能问一个数是什么意思。

### 不会改的事

- 不会为了靠近课本数值而悄悄改读数。
- 在真正有能跑的构建之前，不把鸿蒙 NEXT 写成已经支持。
- 可选模型不会变成打开、运行或导出实验的前提。
