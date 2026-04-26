#!/bin/bash

# MainActivity 拆分说明
# 本项目已将原始的 3322 行 MainActivity.kt 拆分为 3 个文件

## 文件结构：

### 1. MainActivity.kt (原文件 - 保留并精简)
- 包含：MainActivity 主类、OscopeApp 主要 Composable UI 组件
- 行数：约2200行
- 职责：应用入口、主 UI 逻辑

### 2. OscopeUIComponents.kt (新文件 - UI 子组件)
- 包含：ImmersiveScreen、LiveWaveformView、CaptureDiagnosticsLine、
  ClickToEditNumberText、InfoIconButton、LowHighPassRow、FilterOrderSelector、
  EqPanel、ResetIconButton、EqResponseGraph
- 行数：约1200行
- 职责：可复用的 Compose UI 组件

### 3. OscopeUIUtils.kt (新文件 - 工具函数)
- 包含：滑块映射函数、显示低通滤波器、EQ 响应计算等工具函数
- 行数：约220行
- 职责：数学计算和通用工具函数

## 优势：
✓ 代码模块化，更容易维护和理解
✓ 各个文件职责分离清晰
✓ 支持并行开发和版本控制
✓ 便于单元测试和代码复用
✓ 减少单个文件的认知负荷

## 使用方式：
所有文件都在同一个 package (com.example.oscope) 中，
自动导入和使用，无需额外配置。

