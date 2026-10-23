# 架构图片的来源与重新导出

[返回架构总览](../README.md)

三篇架构指南和前端开发指南共使用 9 张静态 SVG 主图，读者无需等待 GitLab 的 Mermaid iframe。每张图下面的折叠区保存可编辑的 Mermaid 源码，使用 `text` 代码块，避免页面再次自动渲染。修改时先改这份源码，再重新导出对应 SVG；不要直接改生成的 SVG。

| 图片 | 可编辑源码所在页面 |
| --- | --- |
| [应用与部署拓扑](deployment-topology.svg) | [架构总览：系统组成](../README.md#系统由哪些部分组成) |
| [业务数据与资源占用关系](business-data-relations.svg) | [数据一致性：数据关系](../data-consistency.md#哪些数据必须一起理解) |
| [创建预约的事务链路](reservation-transaction.svg) | [数据一致性：预约事务](../data-consistency.md#预约一次提交同时保护学生和座位) |
| [最终审批的原子提交](final-approval-transaction.svg) | [数据一致性：最终审批](../data-consistency.md#最终审批批准本身必须能取得资源) |
| [维修资源同步链路](repair-resource-synchronization.svg) | [数据一致性：维修同步](../data-consistency.md#维修本地状态与远端资源分阶段确认) |
| [维修工单状态机](repair-state-machine.svg) | [数据一致性：维修同步](../data-consistency.md#维修本地状态与远端资源分阶段确认) |
| [应用与数据库版本回退边界](application-schema-rollback.svg) | [数据一致性：迁移与回退](../data-consistency.md#迁移与应用回退如何共存) |
| [会话与跨服务授权](session-authorization.svg) | [身份与安全](../security.md) |
| [前端请求链路](frontend-request-flow.svg) | [前端开发：请求与状态](../../development/frontend.md#请求与状态) |

## 本次图片用什么生成？

2026-10-23 的本地导出使用 Node.js 26.0.0、`@mermaid-js/mermaid-cli` 12.0.0、Mermaid 12.2.0、Puppeteer 25.16.0，以及本机已有的 Playwright Chromium Headless Shell 156.0.8078.4。9 张图的 SVG 与 PNG 均渲染成功，随后逐图检查了中文、连线和标签。工具临时安装在 `output/`，没有加入应用依赖或 CI。

导出关闭 HTML 标签、字体嵌入和自动单词折行；状态节点保留完整枚举。SVG 使用原生文字和本机中文字体回退，不包含 `foreignObject`、脚本或外部字体/图片引用。不同系统的字体字形可能略有差别；需要固定像素效果时可查看导出的 PNG。横向图较宽，可打开图片查看原尺寸。

## 怎样重新导出？

在仓库根目录操作，需要 Node.js 22.13 或更新版本、Python 3，以及已有的 Chrome/Chromium 可执行文件。下面的命令只把临时工具、源文件和预览写到 `output/architecture-diagrams/`；不会自动覆盖文档图片。设置 `STUDYROOM_DIAGRAM_CHROMIUM` 为本机实际浏览器路径。

```sh
export STUDYROOM_DIAGRAM_CHROMIUM='/absolute/path/to/chrome-or-chromium'
PUPPETEER_SKIP_DOWNLOAD=true npm install \
  --prefix output/architecture-diagrams/tooling \
  --save-exact --ignore-scripts --no-audit --no-fund \
  --registry=https://registry.npmjs.org \
  @mermaid-js/mermaid-cli@12.0.0 mermaid@12.2.0 puppeteer@25.16.0
```

从四篇指南的折叠区提取源码，并生成本地导出配置：

```sh
python3 - <<'PY'
from pathlib import Path
import json, os, re

base = Path('output/architecture-diagrams')
base.mkdir(parents=True, exist_ok=True)
browser = Path(os.environ['STUDYROOM_DIAGRAM_CHROMIUM'])
assert browser.is_file(), '请指定已有的 Chrome/Chromium 可执行文件'
pattern = re.compile(
    r'!\[[^\]]+\]\((?:images/|\.\./architecture/images/)([^/]+)\.svg\)\s*'
    r'<details>\s*<summary>[^<]+</summary>\s*'
    r'```text\n(.*?)^```\s*</details>', re.M | re.S)
count = 0
for name in ('architecture/README.md', 'architecture/data-consistency.md',
             'architecture/security.md', 'development/frontend.md'):
    document = Path('docs') / name
    for image_name, source in pattern.findall(document.read_text()):
        (base / f'{image_name}.mmd').write_text(source)
        count += 1
assert count == 9, f'预期提取 9 张图，实际 {count}；请检查源码与图片映射'
config = {
    'theme': 'neutral', 'securityLevel': 'strict', 'htmlLabels': False,
    'fontFamily': 'PingFang SC, Microsoft YaHei, Noto Sans CJK SC, sans-serif',
    'deterministicIds': True, 'markdownAutoWrap': False,
    'flowchart': {'htmlLabels': False, 'wrappingWidth': 400},
    'state': {'wrappingWidth': 400, 'minNodeWidth': 220},
}
(base / 'mermaid-config.json').write_text(json.dumps(config, indent=2))
(base / 'puppeteer-config.json').write_text(json.dumps({
    'executablePath': str(browser.resolve()), 'headless': True,
}, indent=2))
print(f'已提取 {count} 张图')
PY
```

逐图导出 SVG 和 PNG。若有错误，命令立即停止；根据报错修正文档里的源码或本机浏览器路径后重试。

```sh
for diagram in output/architecture-diagrams/*.mmd; do
  for format in svg png; do
    output/architecture-diagrams/tooling/node_modules/.bin/mmdc \
      -i "$diagram" -o "${diagram%.mmd}.$format" \
      -c output/architecture-diagrams/mermaid-config.json \
      -p output/architecture-diagrams/puppeteer-config.json \
      -b white --no-font-embed --size 1800 || exit 1
  done
done
```

确认 9 张图均成功后，逐张打开 PNG 检查文字是否完整、连线是否可辨、状态枚举是否被拆词。检查 SVG 不含脚本、`foreignObject`、事件处理属性、非本地 `href` 或 CSS 外部引用；本地 `url(#...)` 箭头标记是正常的。然后只将已核对的 `.svg` 复制到本目录，并把源码和图片放进同一次提交。PNG 与临时依赖留在 `output/`，不提交。
