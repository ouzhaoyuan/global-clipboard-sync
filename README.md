# Global Clipboard Sync

> 多设备全局共享剪贴板 — 无需服务器，无需注册，扫码即用

## 功能

- **全局剪贴板同步** — 手机A复制文字，手机B自动收到
- **极简悬浮窗** — 屏幕上仅一个小图标，支持拖动
- **单击写入** — 收到新内容时图标闪烁，单击即写入剪贴板+震动反馈
- **双击关闭** — 双击悬浮窗图标，停止服务并关闭APP
- **无服务器** — 使用公共MQTT Broker通信，无需自建后端
- **端到端去重** — 自动忽略自己发出的消息，防死循环

## 首次使用授权步骤

1. 安装APK后打开
2. 系统会依次要求授予以下权限（**必须全部允许**）：
   - **悬浮窗权限** — "显示在其他应用上层"
   - **通知权限** — 用于前台服务保活
   - **无障碍服务** — 设置 → 无障碍 → 剪贴板同步 → 开启
3. 授权完成后，APP自动启动后台服务并显示悬浮窗图标

## 使用方法

- 复制任何文字 → 自动同步到其他设备
- 悬浮窗闪烁 = 有新内容 → **单击**写入剪贴板
- **双击**悬浮窗 = 停止服务并关闭APP

## 已知限制

- **Android 10+** 后台无法直接读取/写入剪贴板，本APP通过无障碍服务+悬浮窗方案解决
- 首次安装需手动授予无障碍服务权限（系统限制，无法自动开启）
- 使用公共MQTT Broker (broker.hivemq.com)，延迟取决于网络状况

## 本地编译

```bash
git clone https://github.com/ouzhaoyuan/global-clipboard-sync.git
cd global-clipboard-sync
./gradlew assembleDebug
```

## 技术栈

- Kotlin
- Eclipse Paho MQTT Android Client
- AccessibilityService (全局复制监听)
- ForegroundService (后台保活)
- WindowManager (悬浮窗)
