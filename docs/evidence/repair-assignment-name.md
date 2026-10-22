# 维修人员姓名展示回归

2026-10-22 独立前端试用发现：工单详情只显示维修人员短UUID，不便于判断负责人。本次在派单时复用core已经授权的候选响应中的 `displayName`，保存为repair的 `assignedToName`。列表、详情和操作响应从同一工单字段读取，不增加逐行HTTP或用户资料查询；无关用户仍不能读取工单。

新增可空迁移 `V20261022160000__assigned_worker_name_snapshot.sql`，不改已应用迁移，也不跨schema回填个人数据。已有19张维修工单升级前后数量相同，13张已有派单的历史记录姓名字段为空。实际从公开详情接口读取其中一张旧工单返回200且 `assignedToName=null`；前端保留编号回退。姓名是派单时快照，账号后续更名不会静默改写历史，重新派单时更新。

真实core/repair HTTP回归 **1/1通过，9.863秒**，验证：

- 新工单姓名为空；派单后操作响应、本人列表和详情姓名一致。
- 账号更名后旧快照保持；重新派给该人时刷新姓名；改派另一维修员更新姓名。
- 首次派单时间和完工截止不变；无效人员派单失败不改姓名。
- 范围外学生详情404、本人列表没有该工单；取消后仍保留负责人姓名。

迁移与测试的机器结果：[repair-assignment-name.json](repair-assignment-name.json)。仅正常重启本机repair稳定副本 `.local/local/run/repair.jar`，core进程与前端进程未重启。同期迁移程序还应用了已提交的core可空 `operator_note` 扩展；原core运行版本继续工作。

`repairs.yaml`新增可空字段并已重新生成repair TypeScript类型。`repair_assignment_name_http.py`沿用公共HTTP套件环境变量，CI自动发现且要求非零/无跳过结果。包构建和契约生成成功；不将本机回归描述为正式发布镜像已验证。
