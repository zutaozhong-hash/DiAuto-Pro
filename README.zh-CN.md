# DiAuto Pro

**在比亚迪车机上运行 Android Auto。支持无线与 USB。**

**还能把 Android Auto 画面镜像到仪表盘** —— 投屏时机可自行设置：常开、仅在有地图时、
或仅在导航进行中。适配 DiLink 4.0 / 5.0 / 5.1。
[跳到详细说明](#1-仪表盘投屏)。

> **本仓库是 [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) 的中文本地化维护分支**，
> 而后者本身是 [Open Headunit](https://github.com/andreknieriem/open-headunit) 的独立分支。
> 详见下方[致谢与许可](#致谢与许可)。
>
> **比亚迪支持范围：** 本项目专注比亚迪车型。在其他品牌上可能可用，但不提供支持，
> 也没有适配其他品牌或修复其专属兼容性问题的计划。

[下载与安装](https://zutaozhong-hash.github.io/DiAuto-Pro/) ·
[最新版本](https://github.com/zutaozhong-hash/DiAuto-Pro/releases/latest) ·
[Telegram 更新](https://t.me/DiAutoPro)

[English](README.md) · [العربية](https://zutaozhong-hash.github.io/DiAuto-Pro/ar/) · [Русский](https://zutaozhong-hash.github.io/DiAuto-Pro/ru/) · [Español](https://zutaozhong-hash.github.io/DiAuto-Pro/es/) · [简体中文](https://zutaozhong-hash.github.io/DiAuto-Pro/zh-Hans/)

把 DiAuto Pro 装到**车机上**，然后用手机自带的 Android Auto 功能连接。
**不需要额外的手机端配套应用、不需要转接盒等外接硬件、不需要 root、不需要改固件。**
仍然需要一台支持 Android Auto 的安卓手机；这不是 Apple CarPlay。

![Android Auto 界面：当前音乐卡片与可自由拖动的地图，未设置目的地](site/assets/projection-music.png)

## 0.3.12 更新内容

- **debug 版更名为 DiAuto Pro**，同时保留可共存的 debug 包名，便于与正式版同时安装。
- **修复桌面长按菜单**：debug 版下所有菜单项此前都静默失效，原因是快捷方式的
  目标包名被写死为正式版包名。
- **新增构建期守卫**：若 `shortcuts.xml` 与变体包名再次不一致，debug 构建会直接失败，
  而不再是在运行时静默出问题。
- 新增专用发布签名密钥；release APK 现为 v1 + v2 签名。
- 同步葡萄牙语（巴西）、越南语等社区翻译。

## 特色功能

以下三项能力使本分支区别于普通的 Android Auto 接收端。每项都附带**实际限制**说明，
而不只是功能承诺。

### 1. 仪表盘投屏

把 Android Auto 画面放到**车辆仪表显示屏**上，与车速、挡位等信息并列显示 ——
利用的是比亚迪已经向第三方应用开放的仪表区域。

仪表的投屏区域由 `com.byd.containerservice` 作为公开的 presentation display 发布，
**因此不需要任何比亚迪专属权限**。应用通过显示名称发现它，并按以下优先级选择当前
可用的图层：

| 固件 | 发布的图层名 | 投屏载体 |
| --- | --- | --- |
| DiLink 4.0 / 5.0 | `fission_bg_XDJAScreenProjection` | 自建 Activity + `setLaunchDisplayId` |
| DiLink 5.1 | `shared_fission_bg_XDJAScreenProjection_0`（全图图层） | `Presentation` |
| DiLink 5.1 | `shared_fission_bg_XDJAScreenProjection_1`（侧图图层） | `Presentation` |

**两种载体都保留**，因为 `Presentation` 并非在所有固件上都能正常合成显示。
控制器会依次尝试并自动回退。

**投屏区域会跟随固件自适应**，确保永不遮挡仪表信息：

- **DiLink 4.0 / 5.0** —— 发布的图层本身已排除仪表区域，因此投屏使用整块屏幕。
- **DiLink 5.1** —— 原厂地图自己的图层对第三方隐藏，而 `shared_` 系列图层仍包含
  顶部状态条与底部挡位/续航条。此时投屏只占据**地图带**（顶部 144 px 与底部条保持可见），
  在侧图图层上则占据右侧 600×720 卡片。遮挡车速或挡位属于**安全问题**，而非外观问题。

所有已实测固件的仪表分辨率均为 **1920×720**。在未实测的分辨率下，投屏回退到整块屏幕 ——
因为地图带的偏移量只在 1920×720 下已知；**画面位置不理想但可见，好过静默地什么都看不见**。

应用在此不做任何自绘：它采样 Android Auto 画面并放入视口，原厂仪表面板仍在其下方正常显示。

> **可用性说明：** 这是**实验性**功能，且**默认关闭**。它仅适用于仪表区域已向应用
> 开放共享的车型。若车机未暴露任何仪表显示，设置项会直接报告「功能不可用」而非报错 ——
> 不存在需要安装或绕过的步骤。
>
> 启用位置：**设置 → 高级 → 「仪表盘地图镜像」**。其下方的投屏时机控件**始终显示**，
> 即使镜像开关处于关闭状态 —— 一个会隐藏自己的控件，与一个从未实现的功能无法区分。

### 2. 投屏时机可配置

一直投屏并不总是你想要的：在长时间只听歌的驾驶中，你可能更希望仪表保持显示车辆自身的信息。
**设置 → 高级 → 「投屏时机」**提供三档。**默认为「常开」**，因此在你主动修改之前，
已有配置的行为与之前完全一致。

| 档位 | 投屏条件 | 适用场景 |
| --- | --- | --- |
| **常开** | 只要会话建立，不看屏幕内容 | 把仪表当成第二块屏幕使用（默认） |
| **巡航和导航** | 屏幕上有地图时（无论是否在导航） | 看地图时就希望仪表同步显示 |
| **仅导航** | 正在实际导航引导路线时 | 除逐向导航外不打扰仪表 |

三档**严格包含**：`仅导航` ⊂ `巡航和导航` ⊂ `常开`。因此这是一个单一的有序偏好，
不存在模棱两可的组合。

判定依据是 Android Auto 给出的**明确信号**，而非猜测：
`INSTRUMENT_CLUSTER_START` / `STOP` 是「地图应用是否占用仪表视图」的可靠锁存信号；
`INSTRUMENT_CLUSTER_NAVIGATION_STATUS` 则区分「正在引导路线」（`ACTIVE` / `REROUTING`）
与「只是打开了地图」（`INACTIVE`）。30 秒的宽松窗口用于吸收长直路上的静默时段，
避免地图只是暂时无内容上报时投屏闪断。

这套规则是**不含 Android 类型的纯逻辑**，因此能在 JVM 上做**单元测试**，
而不是仅以注释形式声称其正确。

### 3. DiLink 4.0 与 5.x 固件支持

仪表投屏面向 **DiLink 4.0、5.0、5.1**，而非单一固件 —— 这也是上表有三行的原因。

支持 4.0 并非简单地匹配一个字符串就能做到。DiLink 4.0 的固件
**使用 `setLaunchDisplayId` 启动自己的 Activity**，而不使用 `Presentation`。
因此，一个只实现 Presentation 的版本在这些车机上会**静默地产出黑屏/无内容**。
正因如此，两种载体都被实现并在运行时选择；同时几何布局也跟随固件：
图层已排除仪表的用整屏，未排除的用地图带。

同类的其他固件差异化处理：

- **Wi-Fi Direct 频段请求**仅在 API 29 及以上存在；代码在该版本上走受支持路径，
  同时为更低版本保留可用的回退方案。
- **Android 10 的比亚迪固件会把本地热点固定在 2.4 GHz。** 应用会识别这一情况，
  并给出针对性提示，而不是笼统的失败信息。
- **Android 10 上关闭 GPS 时 BSSID 读取常被屏蔽**，连接流程已对此做处理。

这些代码路径基于共享的固件行为实现，并**有单元测试覆盖**，
但**目前只有 DiLink 5.1 在真车上实际验证过** —— 准确的能力边界见下方
[兼容性与安装](#兼容性与安装)。

## 功能列表

- 原生无线 Android Auto，支持 USB 数据线连接。
- **仪表盘投屏，投屏时机可配置** —— 见[上文](#1-仪表盘投屏)。
- **DiLink 4.0 / 5.0 / 5.1 图层支持**，两种载体 + 按固件自适应的投屏几何。
- 在已验证固件上提供比亚迪风挡导航箭头、距离与路名，无需 ADB。
- 改进 Wi-Fi Direct 接口出现较晚时的恢复能力；仍支持显式 Static BSSID。
- 在受支持的 DiLink 固件上自动恢复 Wi-Fi Direct 地址，无需 ADB 配置。
- 适合车机的首页、精简的设置界面与重做的选项对话框。
- 音乐走车机蓝牙的模式，避免媒体音频焦点冲突。
- 在支持时自动优先使用车机已有的非 DFS 5 GHz Wi-Fi 信道，否则正常选频。
- 有界的 SurfaceView 帧节奏控制、全屏 1080p 与已修正的触控对齐。
- 官网提供英/简中/阿/俄/西五个语言版本与阿拉伯语 RTL 布局；应用内含 20 余种社区翻译。

## 兼容性与安装

已在 **比亚迪 DiLink 5.1 + Android 13** 上实测，覆盖无线与 USB 物理连接。

**「已实测」在这里的准确含义：** DiLink 5.1 的无线与 USB 连接路径、以及 5.1 的仪表投屏
图层处理，均已在一台真车上跑过。而 **DiLink 4.0 / 5.0 的仪表投屏代码路径与 Android 10
的连接路径虽已实现并有单元测试，但尚未在硬件上验证。** 如果你使用的是这些固件，
请把自己当作早期测试者，并欢迎反馈实际表现。

需要一台支持 Android Auto 且功能正常的安卓手机。应用与官网支持英语、简体中文、
阿拉伯语、俄语、西班牙语；其余现有社区翻译仍然可用。

参见 [比亚迪 HUD 兼容性与清理限制](docs/BYD_NAVIGATION.md)。

安装步骤见[安装指南](docs/INSTALL.md)，包含权限、Static BSSID 以及从私有预览版升级的说明。
车机必须允许安装 APK。请在停车状态下完成设置。

实测车机面板约为 58 Hz。在完成 Wi-Fi 信道对齐后，近期无线地图拖动采样达到
47–58 显示帧率，仍存在波动与偶发卡顿。**不承诺稳定 60 FPS。** 参见[验证说明](docs/REVIEW.md)。

## 构建

需要 JDK 17、Android SDK 36、NDK `29.0.14206865`、CMake `3.22.1`。
设置 `ANDROID_HOME`，或提供未纳入版本控制的 `local.properties`，内含 `sdk.dir=...`。

```sh
./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

APK 产物位于 `app/build/outputs/apk/github/`。Release 构建启用 R8/资源压缩。
除非提供未纳入版本控制的 `key.properties`，否则产物未签名：

```properties
storeFile=/absolute/path/to/your-release-keystore.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

`storeFile` 的相对路径会先在仓库根目录解析，再退到 `app/` 模块目录，两处都会尝试。

切勿提交签名密钥或密码。自行构建的 APK 若未使用相同密钥签名，将无法覆盖安装公开发布版。
公开发布签名密钥由维护者私下保管。

包名对照：

| 构建类型 | 包名 | 应用名 |
| --- | --- | --- |
| Release | `com.andrerinas.headunitrevived` | DiAuto Pro |
| Debug（可共存） | `com.andrerinas.headunitrevived.bydhudtest` | DiAuto Pro |

debug 包名允许测试版与稳定版同时安装在同一台车机上。
本分支的 CI 仅覆盖 `github` flavor。

## 官网

`site/` 下是五个静态语言版本，由 `scripts/build_site.py` 生成。
修改 `site/content.json` 以及该脚本顶部的 `BASE` / `REPO` / `VERSION` 常量，然后运行：

```sh
python3 scripts/build_site.py
```

**请勿手改 `site/*/index.html`** —— 下次构建会覆盖。
GitHub Actions 仅将 `site/` 部署到 GitHub Pages。

## 致谢与许可

DiAuto Pro 是 [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) 的中文本地化分支，
后者是 [Open Headunit](https://github.com/andreknieriem/open-headunit) 的独立分支，
基于其贡献者与 [Michael Reid](https://github.com/mikereidis/headunit) 的工作。
本地 DiLink 分支起始于上游提交 `562c8dc`；首个公开快照包含自此分支派生出的 DiAuto 改动。

参见 [LICENSE](LICENSE)（AGPL-3.0）、原始版权声明、应用内致谢与
[第三方声明](docs/THIRD_PARTY.md)。本仓库包含已发布应用的源码。
Android Auto 是 Google 的商标。本项目与 Google、比亚迪无隶属关系，亦未获其背书。
