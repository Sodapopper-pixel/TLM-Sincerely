# 复活后记忆迁移与命名牌回填

主模组神龛/胶片复活走 `ItemFilm.filmToMaid`：`new EntityMaid` + `readAdditionalSaveData`，新 UUID 且不保留命名牌（已实机验证）。附属在公开事件 `MaidAndItemTransformEvent.ToMaid` 中补救：按胶片 `MaidInfo.UUID` 把记忆文件拷到新 UUID（旧文件保留作备份，目标已存在则跳过），按 `MaidInfo.CustomName` 回填命名牌。拒绝过的替代：改主模组 UUID 语义（上游行为，女仆铃/备份已按 UUID 区分，附属无权改）；祭坛配方路径（`AltarRecipe.copyIngredientTag` 不抛此事件，暂不覆盖，文档已注明）。
