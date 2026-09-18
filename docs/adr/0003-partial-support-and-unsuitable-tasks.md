# 部分支持常驻提醒与不适用任务不注册检测器

cook 的专用检测器只覆盖 furnace 炉灶家族（MSK 聚合任务的已核验子集），其他设备配了也永不切入：保持 SUPPORTED 可调度，但原因码改为 PARTIAL_SUPPORT 并永久列入兼容问题视图与进服提醒。拒绝过的替代：降级为 UNSUPPORTED（会误伤 furnace 用户的正常调度）。

locate（跟随主人寻路）与 revive_player（事件驱动）不适用自动切换：直接不注册检测器，兼容报告显示 NO_DETECTOR。拒绝过的替代：保留检测器但默认黑名单（黑名单是用户策略，不应承载语义判决）。
