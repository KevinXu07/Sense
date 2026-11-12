# Sense（感知）— RMX5010 HyperOS 移植修改版

面向真我 GT7 Pro（RMX5010）HyperOS 移植包的 LSPosed / KernelSU 系统增强模块。
本仓库是在原版「感知」基础上的修改版，作为
[RMX5010 HyperOS Fix](https://github.com/KevinXu07/RMX5010_HyperOSFix) 模块的配套组件分发。

## 原作者与致谢

- **原作者：Zephiel**（[酷安主页](https://www.coolapk.com/u/880964)）。
  原仓库 `github.com/Zephiel/Sense` 已删除，本仓库保留其 GPL-3.0 授权继续维护。
- 原版贡献者：TUSB_5834、八月刷机、扬帆起航航航航航、已跑路_勿扰。
- RMX5010 适配与修改：[KevinXu07](https://github.com/KevinXu07)。

包名 `com.zephiel.sense`、原版界面与功能代码（`app/src/main/java/com/zephiel/sense/`）
均来自原作者；修改版沿用原包名，以便已安装用户直接覆盖升级。

## 修改内容

RMX5010 适配代码位于 `app/src/main/java/local/mio/gt7profodfix/`，由
`HookEntry` 按进程分发，主要包括：

- system_server / SystemUI：屏下指纹、圆角、灵动岛、状态栏、触感等适配
- 屏幕色彩：小米「色彩风格」桥接到 ColorOS 校准模式（`ScreenColorBridgeHooks`）
- 查找设备：NoTZ 软件凭证委托与自动签发、cloudsp_tzid 修正（`FdNoTzHooks`）
- 息屏与注视：全屏息屏切换面板 Longrui AOD 模式（`OplusAodPanelBridge`）；
  小米注视感知桥接到 Oplus AON 摄像头 HAL（`XiaomiAonGazeHooks`）
- OplusCamera 移植相关修正（慢动作、专业模式、延时摄影、多机位、相册跳转等）
- 蓝牙 LHDC V5、小米钱包/IFAA、电池与充电信息、设置页入口等

各项修复的原因与验证记录见 RMX5010_HyperOSFix 仓库的 `docs/`。

## 构建

```bash
./gradlew assembleRelease
```

签名密钥不在仓库中。自行构建请使用自己的 keystore 签名；注意 Android 只允许同签名
覆盖安装，换签名后需卸载原版并在 LSPosed 中重新启用、勾选作用域。

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。

仓库中的第三方二进制文件（`app/src/main/assets/KernelSU.apk`、
`app/src/main/jniLibs/` 下的 LHDC 相关库、`app/libs/` 下的厂商接口 jar）
版权归各自权利人所有，不受本仓库 GPL-3.0 授权覆盖。
