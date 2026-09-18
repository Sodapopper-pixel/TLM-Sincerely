# 命名牌仅做实体路由，人设身份不受改名影响

提示词注入文案经用户修订：`You are in the maid entity @<nameplate>, and tool-call directives will act on that entity. Your self identity and persona come from the character setting, not from @<nameplate>.` 注入位置复用 `MemoryGuidanceMixin` 的 `buildMessage` Redirect（与记忆引导同一 systemChat 注入点，零新 Mixin），运行时替换当前命名牌。拒绝过的替代：要求模型以命名牌为自我名（会覆盖人设身份）；新增自动注入 Context 分类（多一次分类注册，收益相同）。
