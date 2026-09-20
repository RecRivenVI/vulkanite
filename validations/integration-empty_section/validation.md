# 空区段生命周期集成验证

## 目标

确认区段从非空变为空时不会留下过期的光线追踪几何，并确认再次构建会产生新区段修订。

## 运行

运行前须在本验证的隔离实例中准备 `auto-empty-section` 世界。本次使用旧独立自动化 fixture 的只读副本；日常实例与玩家存档不作为验证输入。从仓库根目录运行验证驱动：

```powershell
.\components\automation\src\main\scripts\Invoke-EmptySectionE2E.ps1
```

实例位于 `validations/integration-empty_section/instance/`，结果与证据写入 `result/empty_section/`，每次运行覆盖该目录。锁文件与临时文件写入本验证的 `instance/`。

## 通过标准

驱动先等待至少 60 次 PT 回调，且区段与 BLAS 更新计数连续两秒稳定，避免把启动期加载计入放置与移除检查。随后报告非空基线、已接受的空更新、移除后没有全局非空更新增长，以及重新放置方块后产生新的非空更新。握手失败、加载始终未稳定或客户端崩溃均判失败。全局计数不能定位同一坐标的过期 BLAS，也不能证明 holder 唯一性；结果将后一项标记为 `NOT_MEASURED`。
