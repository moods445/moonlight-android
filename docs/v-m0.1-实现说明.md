# v-m0.1 实现说明：串流中快速配置面板

- 版本：**v-m0.1**
- 需求来源：[v-m0.1.md](v-m0.1.md)
- 实现计划：[../.trae/documents/v-m0.1-实现计划.md](../.trae/documents/v-m0.1-实现计划.md)
- 基线：moonlight-android（fork，后续会合并 upstream 分支）
- 日期：2026-10-03

## 一、总目标与硬约束

在连接主机的串流过程中，通过左上角按钮弹出配置面板，实时修改会话配置，无需退出串流。

硬约束（贯穿全部实现）：

1. **尽量不与 upstream 冲突**——新功能代码全部放入新文件；对 upstream 文件的改动保持为“孤立小锚点”，不改 layout XML、不改既有 `strings.xml`。
2. 面板 UI 全部用代码构建，不引入新的 layout 资源。
3. 面板状态为会话级，不写入 SharedPreferences。

## 二、实现清单

### 2.1 新增文件（全部内容都在新文件中）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/limelight/quickconfig/QuickConfigPanel.java` | 快速配置面板本体：左上角按钮 + 弹窗面板 + 开关逻辑 + 会话状态；实现 `QuickConfigPanel.Host` 回调的消费方 |
| `app/src/main/java/com/limelight/quickconfig/PinchZoomHelper.java` | 双指捏合缩放 / 平移（1.0x–3.0x），并提供缩放后触摸坐标逆变换 |
| `app/src/main/java/com/limelight/quickconfig/RemoteKeyboardOverlay.java` | 内置半透明键盘，按键直接发送主机键盘事件 |
| `app/src/main/res/values/strings_quick_config.xml` | 面板英文文案，统一 `qc_` 前缀 |
| `app/src/main/res/values-zh-rCN/strings_quick_config.xml` | 面板中文文案 |

### 2.2 对 upstream 文件的改动（最小锚点）

| 文件 | 锚点内容 |
| --- | --- |
| `Game.java` | 新增 2 个 import；类实现 `QuickConfigPanel.Host`；2 个字段；`onCreate` 中创建 helper/panel（1 处）；`onKeyDown`/`onKeyUp`/`handleKeyDown` 三处 `handleKeyEvent` 拦截；PiP 进入/退出两处 `setOverlaysVisible`；`destroy()` 一处；`handleMotionEvent` 两处坐标逆变换 + 一处捏合处理 + 一处只读拦截；文件末尾追加 `Host` 回调实现（`setVirtualControllerVisible` 等） |
| `VirtualController.java` | 2 处：`refreshLayout()` 让出左上角空间（`topMargin = 15 + buttonSize + 8`）；新增 `inputEnabled` 开关并在输入分发处提前返回 |
| `PreferenceConfiguration.java` | `DEFAULT_TOUCHSCREEN_TRACKPAD` 由 `true` 改为 `false`（默认直接触摸） |
| `res/xml/preferences.xml` | 对应默认值 `true` → `false` |

> 说明：新功能本身没有新增任何 upstream 文件中的类；`Game.java` 的改动集中在少量行，便于后续合并 upstream。

## 三、功能实现说明

### 3.1 面板与按钮

- 左上角半透明齿轮按钮，点击展开/收起面板。
- 面板为代码构建的 `LinearLayout`，含 5 个开关 + “重置缩放”“宏”两个按钮。
- 面板状态仅存于会话内；PiP 进入时隐藏按钮与面板，退出时恢复。

### 3.2 可修改的配置

| 配置 | 实现方式 |
| --- | --- |
| 虚拟手柄显示/隐藏 | `Host.setVirtualControllerVisible(boolean)`；显示时按需创建 OSC 并向主机发送手柄到达事件，隐藏时清零 OSC 状态避免按键卡住 |
| 触摸屏作为触控板 / 直接触摸 | `Host.setTouchscreenTrackpad(boolean)`，运行期切换；默认改为“直接触摸” |
| 只读屏幕 / 正常屏幕 | `QuickConfigPanel.isReadOnly()`，在 `Game.handleMotionEvent` 与按键链路中拦截；默认正常 |
| 客户端放大屏幕 | `PinchZoomHelper`，双指捏合缩放 + 平移，`重置缩放` 按钮复位 |
| 两种输入模式 | 见 3.3 |
| 剪切板同步 | 见 3.4；默认关闭 |
| 宏录制 | 见“已知问题”；默认关闭 |

### 3.3 两种输入模式（v-m0.1 的关键改动）

需求原文：`1 手机输入模式，用于输入文字；2 主机键盘模式，用于输入主机的键盘事件`。

- **主机键盘模式（默认）**：`StreamView` 持有焦点，硬件 / 蓝牙键盘事件走原有翻译逻辑，发送真实主机键盘事件。
- **手机输入模式**：显示**内置半透明键盘** `RemoteKeyboardOverlay`，按键直接发送真实主机键盘事件。

关于“半透明内置键盘”的取舍（按用户要求调整）：

- 早期方案使用系统 IME + 隐藏 `EditText` 镜像转发（`TextWatcher` + `sendUtf8Text`）。实测链路可用，但系统软键盘会把画面顶起、无法控制透明度，导致远端桌面看不见。
- 现方案改为 **App 内自绘键盘**：作为覆盖层绘制在画面之上，**不改变 `StreamView` 尺寸、不顶起画面**，远端桌面始终可见；容器 40% 不透明、按键 25% 不透明。
- **系统输入法相关逻辑不再改动**（保留 upstream 原样）。
- 按键事件走 `Game.sendKeyEvent(keyCode, down)` → 复用 `handleKeyDown/handleKeyUp`，因此享有与物理按键一致的键位翻译、修饰键处理与只读过滤。
- 键盘布局：数字行 / `qwertyuiop` / `asdfghjkl-` / `Shift`+`zxcvbnm./⌫` / `Esc Tab Space Enter`，共 45 键。
- Shift 为“单次生效”锁定：按下 Shift 高亮，下一次可大写按键后自动复位；`-`→`_`、`.`→`>`、`/`→`?`。

### 3.4 剪切板同步（单向）

- 监听 `OnPrimaryClipChangedListener`，把新复制的文本通过 `Host.sendUtf8Text` 发送到主机。
- 仅实现 **客户端 → 主机** 方向；主机 → 客户端因协议无对应通道，不可实现。

### 3.5 缩放与只读的共存

- 捏合手势在只读检查**之前**处理，因此只读模式下仍可缩放/平移查看。
- 缩放后 `handleMotionEvent` 对触摸坐标做逆变换，保证触摸位置与画面一致。
- 捏合判定阈值：双指跨度变化超过 15% 才视为捏合，否则保留正常双指滚动。

## 四、已验证内容

验证设备：`192.168.0.56:39609`（Android 11 / 1080x2340），主机 `home-host`。

- 构建：`./gradlew :app:assembleNonRootDebug` 通过，安装成功。
- 面板：左上角按钮展开/收起正常；PiP 隐藏/恢复正常。
- 虚拟手柄：显示/隐藏切换正常。
- 触摸模式：运行期切换正常。
- 只读：开启后本地输入被拦截；缩放仍可用。
- 缩放：双指捏合缩放、平移、重置正常。
- 内置键盘：
  - 开启开关后面板自动收起、键盘在底部出现（整宽、5 行、半透明）。
  - Shift 锁定时所有字母键标签变大写、`-`→`_`、`/`→`?`。
  - 连续点击按键过程中应用不崩溃、`Game` 保持前台。
  - `KeyboardTranslator.translate(keyCode, -1)` 在合成事件（`deviceId = -1`）下走默认 US 映射，字母/数字均可翻译。
- 验证方式：`adb input` 驱动 + `uiautomator dump` 读取控件坐标与状态（不使用截图）。

## 五、已知问题与限制

1. **宏（Macro）未实现**：仅占位按钮，点击提示“宏录制功能尚未实现”。
2. **剪切板仅单向、且为“逐字打字”**：客户端 → 主机，通过 `sendUtf8Text` 实现，不是真正的剪贴板同步；主机 → 客户端无协议通道，不可实现。
3. **内置键盘不支持中文/输入法文本**：改为“直接发送主机键盘事件”后，只能输入 US 布局的 ASCII 字母数字与少量符号，**无法输入中文**（也未接入系统 IME）。这是“手机输入模式用于输入文字”需求当前的取舍：优先保证 PIN 解锁等只能接受真实按键的场景。
4. **内置键盘缺失部分按键**：无 Ctrl / Alt / Win、方向键、功能键；Shift+数字的符号（`!@#$%^&*()`）未提供；无长按自动重复（退格需连续点击）。
5. **只读会拦下本地特殊组合键**：只读模式下 `Ctrl+Alt+Z/Q/C` 等本地特殊组合键同样被拦截，只能通过面板开关关闭只读。
6. **面板与内置键盘互斥显示**：两者都占用底部/左上区域，键盘会遮住自己的开关，因此面板可见时键盘自动隐藏；开启“手机输入键盘”时面板自动收起以便立即看到键盘。
7. **横屏下面板偏窄**：面板宽度在构造时按 `widthPixels` 计算，而构造时机早于横屏生效（读到竖屏值 1080），导致横屏下面板较窄、说明文字换行。属既有小问题，不影响功能。
8. **内置键盘的透明度**：以背景色 alpha 实现（容器 40%、按键 25%），非系统级模糊/半透明效果。
9. **主机侧最终确认**：内置键盘发的是真实主机键盘事件，客户端侧链路已验证；PIN 解锁、记事本实际输入的最终观感需在主机侧确认。

## 六、构建与安装

```bash
./gradlew :app:assembleNonRootDebug
adb -s 192.168.0.56:39609 install -r -t \
  app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

> 本机编译需具备对应 Android SDK / NDK；缺少时会无法完成 `assembleDebug`。

## 七、版本记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v-m0.1 | 2026-10-03 | 初版：快速配置面板、捏合缩放、只读、剪切板单向同步、两种输入模式（内置半透明键盘）、宏占位 |