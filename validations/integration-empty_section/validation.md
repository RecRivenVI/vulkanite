# 空区段生命周期集成验证

## 目标

确认区段从非空变为空时不会留下过期的光线追踪几何，并确认再次构建会产生新区段修订。

## 运行

运行前须在本验证的隔离实例中准备 `auto-empty-section` 世界。本次使用旧独立自动化 fixture 的只读副本；日常实例与玩家存档不作为验证输入。从仓库根目录运行验证驱动：

```powershell
.\components\automation\src\main\scripts\Invoke-EmptySectionE2E.ps1
```

实例位于 `validations/integration-empty_section/instance/`，证据写入同目录下的 `result/`。

## 通过标准

驱动报告非空基线、已接受的空更新、移除后没有过期 BLAS 重新发布，以及重新放置方块后产生新的非空修订。握手失败或客户端崩溃均判失败。全局区段数与 TLAS 实例数会记录原值，但不能证明同一坐标的 holder 唯一性；结果将此项标记为 `NOT_MEASURED`。
