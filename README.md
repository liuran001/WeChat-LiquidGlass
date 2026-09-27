# 微信 / QQ 底栏液态玻璃 · WeChat-LiquidGlass

为微信和 QQ 打造的 iOS 26 风格液态玻璃底部导航栏 · LSPosed 模块（libxposed API 102）

## ✨ 特性

- 🫧 **单 pass AGSL 透镜管线**：圆角矩形 SDF 折射、边缘色散、重力传感器高光
- 💧 **玻璃滴选中动效**：切换标签时液滴滑动、回弹；**支持横向拖拽切换**，跟手拉伸、松手吸附
- 🎯 **对齐 KernelSU 管理器**：悬浮 pill 宽度贴合内容、4dp 轻模糊 + 容器色垫底、
  按压时液滴放大 `78/56` 并淡入折射、焦点标签放大 `1.1×`
- 🔍 **背景实时跟随**：玻璃折射的是当前页面的真实内容，切页即变
- 🧩 **完整保留原生功能**：红点、未读数、图标渐变、长按菜单全部走应用自己的实现
- 🪟 **双宿主**：微信与 QQ 共用一套安装器，差异集中在 `HostApp` 一张表里
- 🌗 **深浅色跟随**：读取应用 uiMode，辅以标签色众数探测交叉校验
- ♿ **降级兜底**：Android 12 保留 GPU 模糊与液滴，只少了边缘折射（AGSL 需 13）；
  Android 12 以下退化为轻磨砂；native 模糊库不可用时自动切纯 Java 实现

## 📦 安装

1. 准备支持 **libxposed API 102** 的 LSPosed 环境
2. 安装 APK，在 LSPosed 中启用（作用域已限定 `com.tencent.mm` 和 `com.tencent.mobileqq`，
   只勾选想改造的那个也可以）
3. **强制停止**微信 / QQ 后重新打开

> 已测试：微信 `8.0.77 (3160)`  QQ `9.2.85 (13860)` `9.1.52 (9054)`

## 🔍 工作原理

两个应用的主界面结构几乎是双胞胎——内容区铺满全屏，底栏以 `layout_gravity=bottom`
浮在它上面，同处一个 FrameLayout——所以安装器只有一套：

```
Hook Instrumentation.callActivityOnResume
  └─ 主界面 Activity：按类名定位底栏（两家资源 ID 都被 AndResGuard 混淆，不可用）
       └─ 布局手术：把底栏搬进 LiquidGlassHostLayout
            ├─ 玻璃层 LiquidGlassView（backdrop = 底栏原本的全屏兄弟）
            ├─ 液滴层 LiquidGlassView（跟随选中项，可拖拽）
            └─ 应用原生底栏（背景已清空）
```

应用相关的部分全在 [`HostApp`](src/io/github/liuran001/mmliquidglass/HostApp.java) 里：

| | 微信 | QQ |
|---|---|---|
| 包名 | `com.tencent.mm` | `com.tencent.mobileqq` |
| 主界面 Activity | `ui.LauncherUI` | `activity.SplashActivity` |
| 底栏 | `ui.LauncherUIBottomTabView` | `widget.QQTabWidget`（默认）<br>`widget.QQTabLayout`（灰度） |
| 切页信号 | `setTo(int)` | `setCurrentTab(int)` |
| backdrop | `CustomViewPager` | `tabcontent` / `ViewPager2` |
| 额外处理 | — | 隐藏自带的 `QQBlurViewWrapper` |

QQ 同一个安装包里带了新旧两套底栏，服务端开关 `tab_layout_9065_116522266`
默认关闭，即绝大多数用户跑的是 `QQTabWidget`（`android.widget.TabWidget` 子类）。
两套都在表里，运行时按实际存在的那个走。

日志 tag：`LiquidGlass`

## 🛠️ 构建

无需 Gradle / Android Studio：

```bash
./setup-tools.sh   # 下载 android.jar / build-tools 34 / libxposed
./build.sh
```

产物：`LiquidGlass-vX.Y.Z.apk`（自动生成 debug 签名）。

### 可调参数

| 参数 | 位置 | 默认 |
|---|---|---|
| 折射高度 / 边缘带 | `GlassTuner.onSize()` | 24dp（同 KernelSU `lens()`） |
| 模糊强度 | `tuneGlass()` | 4dp（同 KernelSU `blur()`） |
| 每列额外留白 | `hugContentWidth()` | 32dp |
| 底部抬升 | `GlassConfig.barOffsetDp` | 12dp |
| 按压放大 / 焦点放大 | `DropletDragController` / `DropletPanel` | 78/56 · 1.1 |
| 拖拽形变上限 | `DropletDragController.STRETCH_LIMIT` | 0.2 |

### 液滴

静止时是一层 10% 的纯色块；按住/拖拽时按 `pressProgress` 淡入透镜：

```
lens(refractionHeight = 10dp × p, refractionAmount = 14dp × p,
     depthEffect = true, chromaticAberration = 0.5)
```

液滴折射的是 **页面 + 一份单独绘制的 1.1× 标签副本**（KernelSU 的
`CombinedBackdrop`）。拖拽时看到的放大图标正是这份副本经折射后的样子，
因此液滴层必须压在真实标签**之上**，而真实标签保持原尺寸——两边都缩放会叠加两次。

### 动画

滑动/按压完全复刻 KernelSU 的 `DampedDragAnimation`：五条独立弹簧，参数照搬。

| 弹簧 | 阻尼比 / 刚度 | 驱动 |
|---|---|---|
| value | 1.0 / 1000 | 位置（**以标签为单位**，非像素） |
| velocity | 0.5 / 300 | 归一化速度 → 拉伸形变 |
| press | 1.0 / 1000 | 按压进度 |
| scaleX | 0.6 / 250 | 横向缩放 |
| scaleY | 0.7 / 250 | 纵向缩放 |

两处易被忽略但决定手感的细节：位置以标签为单位追踪，速度按标签数归一化，
因此不同屏幕密度手感一致；`release()` 会**等液滴接近目标**才回落缩放，
这样甩动读起来是一个连贯动作而非"滑动 + 单独缩回"。

点击切换也走同一条路径（press → 移动 → release），与拖拽完全一致。

## 🙏 致谢

- [sjtt2/HeyBox-LiquidGlass](https://github.com/sjtt2/HeyBox-LiquidGlass) — MIT，本项目的灵感来源
- [tiann/KernelSU](https://github.com/tiann/KernelSU) — 拖拽形变的速度曲线参考其 `FloatingBottomBar`
- [libxposed/api](https://github.com/libxposed/api) — Apache-2.0

## 📄 License

[MIT](LICENSE)
