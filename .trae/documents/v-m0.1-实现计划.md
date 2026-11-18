# v-m0.1 实现计划（低冲突版）：串流中快速配置面板

依据 [v-m0.1.md](file:///home/aio/gitWorkspace/moonlight-android/docs/v-m0.1.md) 实现 8 项功能。

**硬约束（用户新增）**：必须尽量避免与 upstream 合并冲突——用户会把自己改的代码合并回原项目分支。因此本版本把**绝大部分代码放进新文件**，对 upstream 既有文件只保留**少量、孤立的一行/两行锚点**，并**取消所有既有布局 XML 的改动**（面板用 Java 代码构建）。

---

## 一、Summary

- 新增左上角按钮 + 非模态浮层面板，可实时切换：虚拟手柄、触摸板/直接触摸、只读屏幕、缩放重置、输入模式、剪切板同步、宏（占位）。
- 缩放交互：**双指捏合**，可与只读模式共存。
- 所有开关**仅本次会话生效**，不写回 `SharedPreferences`。
- 面板 UI 全部由 Java 代码构建（不新增/不修改 layout XML），字符串放到**新的资源文件**，从而与 upstream 的布局/翻译改动零冲突。

## 二、已确认决策

| # | 决策 | 选择 |
|---|------|------|
| 1 | 范围 | 全部 8 项（剪切板单向；宏仅占位按钮） |
| 2 | 触摸模式 | 复用现有 `touchscreenTrackpad`（Absolute/Relative 上下文），仅增加运行期切换 |
| 3 | 只读语义 | 屏蔽全部本地输入：触摸/鼠标/键盘/手柄 |
| 4 | 持久化 | 会话级，不写回全局设置 |
| 5 | 缩放交互 | 双指捏合 |
| 6 | 剪切板 | 单向：客户端 → 主机（协议无反向通道） |
| 7 | 宏 | 只加按钮，暂不实现 |
| 8 | **冲突控制** | 新文件优先；upstream 文件只留最小锚点；不改 layout XML；不改现有 strings.xml |

## 三、冲突面清单（本计划的"最小锚点"承诺）

**新增文件（与 upstream 零冲突）**
| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/limelight/quickconfig/QuickConfigPanel.java` | 面板 UI + 会话状态 + 剪切板监听 + 键事件拦截 + 宏占位 |
| `app/src/main/java/com/limelight/quickconfig/PinchZoomHelper.java` | 捏合检测 + 缩放/平移状态 + 坐标换算 |
| `app/src/main/res/values/strings_quick_config.xml` | 面板文案（默认语言） |
| `app/src/main/res/values-zh-rCN/strings_quick_config.xml` | 面板文案（中文） |

**既有文件（每处均为 1~3 行、彼此孤立）**
| 文件 | 锚点数 | 内容 |
|------|--------|------|
| [Game.java](file:///home/aio/gitWorkspace/moonlight-android/app/src/main/java/com/limelight/Game.java) | 2 字段 + 1 实例化块 + 3 内联 hook + 2 坐标换算点 + `onDestroy` 1 行 + PiP 1 行 + 末尾追加回调方法 | 见第五节 |
| [VirtualController.java](file:///home/aio/gitWorkspace/moonlight-android/app/src/main/java/com/limelight/binding/input/virtual_controller/VirtualController.java) | 2 处 | 新增 `setInputEnabled()`（类末尾追加）+ `refreshLayout()` 里 OSC 齿轮按钮 topMargin 避让 1 行 |
| [PreferenceConfiguration.java](file:///home/aio/gitWorkspace/moonlight-android/app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java) | 1 常量 | `DEFAULT_TOUCHSCREEN_TRACKPAD = false`（唯一一处"改既有值"，为满足文档"默认直接触摸"） |
| [preferences.xml](file:///home/aio/gitWorkspace/moonlight-android/app/src/main/res/xml/preferences.xml) | 1 属性 | `checkbox_touchscreen_trackpad` 的 `android:defaultValue="false"` |

**明确不改**：`activity_game.xml` 及任何 layout、任何既有 `strings.xml`、`ControllerHandler.java`（OSC 到达事件改由 `Game.conn` 直接发送）、原生/JNI 层。

## 四、设计要点

### 4.1 会话状态载体
`prefConfig` 是 `onCreate` 里 `readPreferences()` 生成的 per-Activity 实例、从不落盘，所以**直接改它的字段 = 会话级**：
- 虚拟手柄 → `prefConfig.onscreenController`（同时让 `ControllerHandler.getActiveControllerMask()` 自动生效）
- 触摸模式 → `prefConfig.touchscreenTrackpad`（`handleMotionEvent` 的 `xOffset` 判定读的也是它）

其余状态放在 `QuickConfigPanel` 内部：`readOnly`、`phoneTextInputMode`、`clipboardSync`、`macro`。缩放状态放在 `PinchZoomHelper` 内部。

### 4.2 只读与缩放共存
文档同时要"只读"和"放大方便操作"，所以**捏合缩放先于只读拦截**：只读只拦"转发给主机"的输入，不拦本地显示变换。

### 4.3 Game 与面板的通信
`QuickConfigPanel` 通过构造函数接收一个 `Host` 回调接口（由 `Game` 实现），需要 Game 内部资源时回调，避免长参数列表：

```java
public interface Host {
    void setVirtualControllerVisible(boolean visible); // 惰性创建/显示/隐藏 OSC + 补发到达/移除
    void setTouchscreenTrackpad(boolean enabled);      // 重建 touchContextMap
    void sendUtf8Text(String text);                    // 剪切板/手机输入，内部判 null
}
```
`Host` 由 `Game implements Game.Host`（类声明追加 `implements` 属于一次性小改动）；三个实现方法**追加在类末尾**，纯新增、低冲突。

---

## 五、Proposed Changes

### 变更 0：新增文件

**0.1 `PinchZoomHelper.java`**
```java
public class PinchZoomHelper {
    private final View target;                 // StreamView
    private float scale = 1f, panX, panY;
    private boolean baselineSet, active;
    private float startSpan, startScale, startMidX, startMidY, startPanX, startPanY;
    private static final float MAX_ZOOM = 3f, TRIGGER_RATIO = 0.15f;

    public PinchZoomHelper(View target) { this.target = target; }

    /** 只处理双指手势；返回 true 表示本事件已被缩放消费。全部用 raw 坐标做距离/差，规避坐标系问题。 */
    public boolean handleEvent(MotionEvent e) { /* 见下 */ }
    public boolean isZoomed() { return scale != 1f; }
    public void reset() { scale = 1f; panX = panY = 0f; apply(); }

    /** 父坐标系 -> StreamView 本地未变换坐标（仅缩放时用逆矩阵） */
    public void mapToLocal(float parentX, float parentY, float[] out) { /* 见下 */ }
    private void apply() { /* pivot 居中 + setScaleX/Y + clamp 后的 setTranslationX/Y */ }
}
```
`handleEvent` 逻辑（双指 raw 距离/中点；跨度变化 >15% 才进入缩放，进入时返回 true 并消费整个手势到手指全部抬起）——与前一版计划一致，唯一区别是"取消已激活的 touch 上下文"改为回调 `Host`/由 Game 在 hook 处处理：**在 `Game.handleMotionEvent` 里，`pinchZoomHelper.handleEvent(event)` 返回 true 时由 Game 负责 `touchContextMap[i].cancelTouch()`**（见变更 1 的 hook 说明）。

**0.2 `QuickConfigPanel.java`**（`extends FrameLayout implements View.OnClickListener, CompoundButton.OnCheckedChangeListener`）
- 构造：`QuickConfigPanel(Activity activity, FrameLayout root, StreamView streamView, PreferenceConfiguration prefConfig, Host host)`
  - `setClickable(false)`、`setFocusable(false)`，layout 参数 `MATCH_PARENT`，加入 `root`；**非 clickable 容器不消费空白区域触摸，事件自然穿透到下层**（`backgroundTouchView` / `streamView` 行为不变）。
  - 内部构建：左上角 `ImageButton`（`R.drawable.ic_settings`）+ `LinearLayout` 面板（半透明背景、padding），子控件全部 `focusable=false`：
    `switchVirtualController`、`switchTouchTrackpad`、`switchReadOnly`、`buttonZoomReset`、`switchPhoneInputMode`、`switchClipboardSync`、`buttonMacro`。
  - 初始值：虚拟手柄=`prefConfig.onscreenController`、触控板=`prefConfig.touchscreenTrackpad`、其余为关。
- 公开 API（供 Game 的 hook 调用）：
  - `boolean isReadOnly()`
  - `boolean handleKeyEvent(KeyEvent event, boolean down)` → 只读时返回 true；手机输入模式下若 `getUnicodeChar()` 有效则 `host.sendUtf8Text(...)` 并返回 true
  - `void setOverlaysVisible(boolean visible)` → 供 PiP 联动
  - `void destroy()` → 注销剪切板监听
  - `void bringToFrontAfterOsc()` → 内部调用 `bringToFront()`，供 OSC 惰性创建后调用
- 事件回调（调用 `Host`）：虚拟手柄/触控板开关 → `host.*`；`buttonZoomReset` → `host.resetZoom()`（或直接持有 `PinchZoomHelper`，见下）；宏 → Toast。
- 剪切板：`ClipboardManager.OnPrimaryClipChangedListener`，文本变化且非只读时 `host.sendUtf8Text(text)`，用 `lastClipboardText` 去重。
- **为减少 Game 锚点**，构造时额外传入 `PinchZoomHelper`，`buttonZoomReset` 直接调 `zoomHelper.reset()`，不需要 Game 参与。

**0.3 字符串资源**：两个新 XML 文件，键前缀统一 `qc_`：
`qc_button_desc`、`qc_title`、`qc_virtual_controller`(+`_summary`)、`qc_touch_trackpad`(+`_summary`)、`qc_read_only`(+`_summary`)、`qc_zoom_reset`、`qc_phone_input`(+`_summary`)、`qc_clipboard_sync`(+`_summary`)、`qc_macro`、`qc_macro_unimplemented`。

### 变更 1：`Game.java` 的锚点（全部最小化）

**(a) 字段**（加在 :145 `isHidingOverlays` 附近）
```java
private QuickConfigPanel quickConfigPanel;
private PinchZoomHelper pinchZoomHelper;
```

**(b) `onCreate`**（加在 :538 `setPreferredOrientationForCurrentDisplay` 之前、`streamView`/`prefConfig` 均已就绪处）
```java
pinchZoomHelper = new PinchZoomHelper(streamView);
quickConfigPanel = new QuickConfigPanel(this, (FrameLayout) streamView.getParent(),
        streamView, prefConfig, pinchZoomHelper, this);
```
（放在 OSC 创建块之后，保证面板在 z 序最上层。）

**(c) `handleKeyDown` (:1327) / `handleKeyUp` (:1409) / `handleKeyMultiple` (:1473)**
在 `handleSpecialKeys(...)` 之后、`controllerHandler`/键盘转发之前各插 1 行：
```java
if (quickConfigPanel != null && quickConfigPanel.handleKeyEvent(event, true /*或 false*/)) return true;
```
这样：只读时拦截键/手柄下发；手机输入模式下把字符走 UTF-8 通路。本地特殊键（Ctrl+Alt+Shift+Q 退出）仍优先可用。

**(d) `handleMotionEvent` (:1785)** 在 `if (!grabbedInput) return false;` 之后插 2 行：
```java
if (pinchZoomHelper != null && pinchZoomHelper.handleEvent(event)) {
    for (TouchContext t : touchContextMap) { if (t != null) { t.cancelTouch(); t.setPointerCount(0); } }
    return true;
}
if (quickConfigPanel != null && quickConfigPanel.isReadOnly()) return true;
```
- 捏合在前 → 只读下仍可缩放；只读在后 → 触摸/鼠标/摇杆/手柄全部不下发（`handleMotionEvent` 是 `streamView`/`backgroundTouchView`/`onCapturedPointer` 的唯一汇聚点）。
- 3 指呼键盘在只读下被一并吞掉，属预期。

**(e) 坐标换算**：`getStreamViewRelativeNormalizedXY` (:1548) 与 `updateMousePosition` (:2141) 的 `view != streamView` 分支改用 `pinchZoomHelper.mapToLocal(...)`（每处 2~3 行局部替换；`view == streamView` 时 Android 已投递本地坐标，保持不变）。

**(f) `onDestroy` (:1035)** 加 1 行：`if (quickConfigPanel != null) quickConfigPanel.destroy();`

**(g) PiP 可见性**：在 `onCreate` 里 `isHidingOverlays` 被赋值的 PiP 进入/退出分支各加 1 行：
`if (quickConfigPanel != null) quickConfigPanel.setOverlaysVisible(!isHidingOverlays);`

**(h) 类声明 + 末尾追加回调实现**（纯新增）
- 类声明改为 `class Game extends Activity implements GameGestures, NvConnectionListener, EvdevListener, InputCaptureProvider, Game.Host`（只加 `Game.Host`）。
- 末尾追加：
```java
@Override public void setVirtualControllerVisible(boolean visible) {
    if (visible) {
        if (virtualController == null) {
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout) streamView.getParent(), this);
            virtualController.refreshLayout();
        }
        virtualController.show();
        // 通知主机 gamepad 1 到达
        if (conn != null) conn.sendControllerArrivalEvent((byte) 1, (short) 1 | controllerHandler.getActiveControllerMask(),
                MoonBridge.LI_CTYPE_UNKNOWN, <OSC 按钮位>, (short) 0);
        prefConfig.onscreenController = true;
        if (quickConfigPanel != null) quickConfigPanel.bringToFrontAfterOsc();
    } else {
        if (virtualController != null) {
            virtualController.setInputEnabled(false); // 清零一次避免残留
            if (conn != null) conn.sendControllerInput(1, (short) 0, (short) 0, (byte)0,(byte)0,(short)0,(short)0,(short)0,(short)0);
            virtualController.hide();
        }
        prefConfig.onscreenController = false;
    }
    if (virtualController != null) virtualController.setInputEnabled(!(quickConfigPanel != null && quickConfigPanel.isReadOnly()));
}
@Override public void setTouchscreenTrackpad(boolean enabled) { /* 重建 touchContextMap，见变更 2 */ }
@Override public void sendUtf8Text(String text) { if (conn != null && text != null) conn.sendUtf8Text(text); }
```
（`getActiveControllerMask()` 若在 `ControllerHandler` 中为 public 则直接用；否则退化为 `(short) 1`，并在计划外不改 `ControllerHandler`。）

### 变更 2：触摸模式运行期切换（Game 新增方法，追加在末尾）
```java
for (TouchContext t : touchContextMap) { if (t != null) t.cancelTouch(); }
prefConfig.touchscreenTrackpad = enabled;
for (int i = 0; i < touchContextMap.length; i++) {
    touchContextMap[i] = enabled
        ? new RelativeTouchContext(conn, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, streamView, prefConfig)
        : new AbsoluteTouchContext(conn, i, streamView);
}
```
配套：`PreferenceConfiguration.DEFAULT_TOUCHSCREEN_TRACKPAD = false` + `preferences.xml` 的 `android:defaultValue="false"`（唯一"改既有值"处，为满足文档"默认直接使用触摸屏"）。

### 变更 3：`VirtualController.java`（2 处）
1. 类末尾追加：
```java
private boolean inputEnabled = true;
public void setInputEnabled(boolean enabled) { this.inputEnabled = enabled; }
```
2. `sendControllerInputContextInternal()` (:181) 首行加 `if (!inputEnabled) return;`。
3. `refreshLayout()` (:155) 中 OSC 齿轮按钮 `topMargin` 由 `15` 改为 `15 + buttonSize + 8`，避让左上角新按钮。

### 变更 4：只读的其他清理
- 进入只读：`touchContextMap[i].cancelTouch()`（会抬起鼠标键）、`virtualController.setInputEnabled(false)`、清空 `modifierFlags`（由面板切换回调经 `Host` 触发，写在 `Game.setTouchscreenTrackpad` 同级的追加方法里，如 `Game.setReadOnly(boolean)`；面板只读开关回调它）。
  - 因此 `Host` 再增加一个方法 `void setReadOnly(boolean readOnly)`。共 4 个回调方法，全部追加在类末尾。
- **已知限制**：已按下的键盘按键无法逐个补发抬起（现有实现未追踪按键集合），只清修饰键状态。

### 变更 5：剪切板单向同步（在 `QuickConfigPanel` 内实现）
- 开关 ON：`addPrimaryClipChangedListener`；回调里 `getPrimaryClip()` 文本非空、与 `lastClipboardText` 不同、且非只读 → `host.sendUtf8Text(text)`。
- 开关 OFF / `destroy()`：`removePrimaryClipChangedListener`。
- **限制**（写入 UI 摘要文案）：`sendUtf8Text` 是"在主机上逐字打字"，非真正粘贴同步；主机→客户端不可实现。

### 变更 6：输入模式（在 `QuickConfigPanel.handleKeyEvent` 内实现）
- `phoneTextInputMode=false`（默认）= 主机键盘模式，逻辑不变。
- `=true`：`getUnicodeChar()` 有效且非组合重音 → `host.sendUtf8Text("" + (char) unicode)` 并返回 true（down/up 对称消费）。
- 切换到手机输入模式时调 `activity` 的 `toggleKeyboard` 等价逻辑（面板内用 `InputMethodManager.showSoftInput(streamView, 0)`）；切回时 `hideSoftInputFromWindow`。
- 已知影响：全局模式，物理键盘也走文本通路。

### 变更 7：宏占位
- `buttonMacro` 点击仅翻转标志 + `Toast(R.string.qc_macro_unimplemented)`。

---

## 六、Assumptions & Decisions

1. **面板形态**：非模态同层浮层（非 Dialog），边看边调；非 clickable 容器不拦截空白区触摸。
2. **会话级**：只改 `prefConfig` 内存字段，绝不 `SharedPreferences.edit()`。
3. **`DEFAULT_TOUCHSCREEN_TRACKPAD=false`** 是本计划唯一改动 upstream 既有值的地方（2 处，1 常量 + 1 XML 属性），为满足文档"默认直接使用触摸屏操作主机"；冲突风险低但需知悉。
4. **`ControllerHandler.java` 零改动**：OSC 到达/移除事件由 `Game` 用已有的 `conn`（`NvConnection`）直接发。若 `getActiveControllerMask()` 非 public，则退化为固定 mask `(short)1`，仍不改 `ControllerHandler`。
5. **布局 XML / 既有 strings.xml 零改动**：面板用 Java 构建，文案放新资源文件。
6. **捏合缩放不阻塞只读**；只读只拦"转发给主机"。
7. **剪切板单向打字**；**宏仅占位**；不启用原生多点触控 `sendTouchEvent`。
8. 不改原生/JNI。

## 七、实施顺序

1. 新资源文件（`strings_quick_config.xml` ×2）。
2. `PinchZoomHelper.java`。
3. `QuickConfigPanel.java`（含 `Host` 接口定义）。
4. `VirtualController.java` 2 处。
5. `Game.java` 锚点 a→h + 追加回调方法。
6. `PreferenceConfiguration.java` + `preferences.xml` 默认值。

## 八、Verification

**编译**：需 Android SDK（`compileSdk 37`）+ NDK `29.0.14206865`；当前仓库无 `local.properties`、无 `ANDROID_HOME`，若环境未提供则无法本机编译。命令 `./gradlew :app:assembleDebug`。

**静态检查**
- `Game.handleMotionEvent` 中捏合 hook 在只读 hook **之前**。
- `handleKeyDown/Up/Multiple` 的 hook 在 `handleSpecialKeys` **之后**。
- `QuickConfigPanel` 容器 `setClickable(false)`，空白区触摸仍穿透到 `backgroundTouchView`/`streamView`。
- `touchContextMap` 重建后 `xOffset` 判定读取同一 `prefConfig.touchscreenTrackpad`。
- OSC 惰性创建后调用 `bringToFrontAfterOsc()`，面板仍在最上层。
- `onDestroy` 注销剪切板监听。

**手动清单（真机 + 可用主机）**
1. 左上角 ⚙ 按钮存在、可弹/收面板，不与性能叠加层、OSC 齿轮重叠。
2. 虚拟手柄开关：开启后手柄出现、主机识别 gamepad 1；关闭无残留按键。
3. 触摸模式：直接模式鼠标绝对跟随；触控板模式相对移动；切换无卡死触摸。
4. 只读：触摸/鼠标/键盘/手柄均不下发，画面继续；面板与缩放仍可用。
5. 双指捏合：分合手指缩放 1x~3x；普通双指滑动仍为滚轮；重置回 1x；放大后画面外触摸坐标仍准确。
6. 输入模式：手机输入模式软键盘弹出、字符正确到达；主机键盘模式组合键生效。
7. 剪切板：开启后复制文本自动"打字"到主机；关闭/只读时不发送。
8. 宏按钮仅提示未实现。
9. 会话级：重进串流后开关回到设置页配置。
10. PiP：进入时按钮/面板隐藏，退出恢复。