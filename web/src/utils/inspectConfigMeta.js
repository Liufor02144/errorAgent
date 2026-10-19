/**
 * 配置项的展示元数据。
 *
 * 为什么放前端：后端存的是 cfg_key 和一段给工程师看的原始说明，
 * 而页面上要给的是「这项是干什么的」。把人话标题硬塞进数据库，
 * 等于让改文案也要跑一次 SQL。
 *
 * label —— 人话标题，页面主文案，不出现技术键名
 * unit  —— 单位后缀，让数字有语境（300 → 300 秒）
 * hint  —— 一句话说清影响；更细的原理留在后端 description 里，走问号浮层
 */

/** 分组顺序即导航顺序，按「多久看一次」由高到低排 */
export const GROUPS = [
  { key: 'JUDGE', label: '什么算异常', icon: 'el-icon-aim' },
  { key: 'EVENT', label: '提醒节奏', icon: 'el-icon-bell' },
  { key: 'LIBRARY', label: '异常库', icon: 'el-icon-folder-checked' },
  { key: 'COLLECT', label: '采集', icon: 'el-icon-refresh' },
  { key: 'LLM', label: 'AI 分析', icon: 'el-icon-cpu' }
];

/**
 * 重新分组：后端的 group_name 是按代码模块分的，
 * 而人找配置是按「我想调什么」找的 —— 资金兜底属于「什么算异常」，
 * 推送相关的全归「提醒节奏」，跟它在代码里属于哪个包无关。
 */
export const META = {
  // ---------- 什么算异常 ----------
  'judge.surge.enabled': {
    group: 'JUDGE', label: '突增判定', unit: '',
    hint: '关闭后，已确认业务预期的异常量再大也不报，只报新增。已开着的突增事件也不再分析和推送'
  },
  'judge.surge.multiple': {
    group: 'JUDGE', label: '突增倍数', unit: '倍',
    hint: '已标业务预期的异常，量级超过同时段基线这么多倍才报（默认 3 倍）'
  },
  'judge.surge.min.count': {
    group: 'JUDGE', label: '突增最低次数', unit: '次',
    hint: '最近 10 分钟至少这么多次才判突增。基线很低时，零星几条也会算出很大的倍数'
  },
  'judge.baseline.days': {
    group: 'JUDGE', label: '基线统计天数', unit: '天',
    hint: '拿近几天同一时段的均值当基线'
  },

  // ---------- 提醒节奏 ----------
  'event.ai.interval.hours': {
    group: 'EVENT', label: 'AI 分析最短间隔', unit: '小时',
    hint: '同一个事件两次分析之间至少隔多久。间隔到了也不一定再分析，还要满足下面的「量级涨幅」或扩散到新服务'
  },
  'event.ai.reanalyze.growth': {
    group: 'EVENT', label: '重新分析的量级涨幅', unit: '倍',
    hint: '事件累计次数涨到上次分析时的几倍才重新分析。量级没变的事件再分析只会得到同样的结论，白花钱'
  },
  'template.extract.retry.hours': {
    group: 'LLM', label: '抽模版失败冷却', unit: '小时',
    hint: '同一个异常抽模版失败后，多久内不再重试。抽不出来的异常每轮重试结果都一样'
  },
  'run.retain.days': {
    group: 'COLLECT', label: '巡检记录保留', unit: '天',
    hint: '巡检记录（含推送的卡片原文）保留多少天，过期的每轮采集顺带清理'
  },
  'event.repush.enabled': {
    group: 'EVENT', label: '升级重推', unit: '',
    hint: '开启后，一直没人处理的事件会反复提醒，且越拖越显眼'
  },
  'event.repush.hours': {
    group: 'EVENT', label: '重推间隔', unit: '小时',
    hint: '按顺序递增，用完最后一档就一直保持那个频率。标注后停止'
  },
  'event.close.quiet.hours': {
    group: 'EVENT', label: '事件自动关闭', unit: '小时',
    hint: '连续这么久没再出现就自动收敛，之后再出现算新事件'
  },
  'push.daily.limit': {
    group: 'EVENT', label: '每日推送上限', unit: '条',
    hint: '只限制新事件；升级重推不占额度'
  },
  'push.max.per.round': {
    group: 'EVENT', label: '单轮推送上限', unit: '条',
    hint: '一批事件同时满足推送条件时，本轮最多发多少条，剩余的顺延到下一轮'
  },
  'push.severity.include': {
    group: 'EVENT', label: '推送严重度', unit: '',
    hint: '达到这些级别才进群（默认 P0,P1,P2；只有 P3 无实质影响的不推）'
  },
  // ---------- 采集 ----------
  'collect.scan.interval.seconds': {
    group: 'COLLECT', label: '采集间隔', unit: '秒',
    hint: '多久扫一次生产日志。上一轮没跑完会跳过本轮'
  },

  // ---------- 异常库 ----------
  'template.extract.batch.size': {
    group: 'LIBRARY', label: '单次抽取数量', unit: '条',
    hint: '一次大模型请求里放多少个待抽取模版的异常'
  },
  'template.extract.max.per.round': {
    group: 'LIBRARY', label: '单轮抽取上限', unit: '条',
    hint: '每轮巡检最多让 AI 抽取多少个新模版'
  },
  'template.review.max.per.round': {
    group: 'LIBRARY', label: '单轮复核上限', unit: '条',
    hint: '每轮巡检后最多自动复核多少个新模版，0 表示关闭自动复核'
  },
  'template.match.min.length': {
    group: 'LIBRARY', label: '正则最小长度', unit: '字符',
    hint: '过短的正则匹配面太宽，会把不同异常吞进同一个模版'
  },

  // ---------- AI 分析 ----------
  'llm.daily.limit': {
    group: 'LLM', label: '每日调用上限', unit: '次',
    hint: '超出后熔断，当天不再调用'
  },
  'llm.rpm.limit': {
    group: 'LLM', label: '每分钟请求上限', unit: '次',
    hint: '不能高于服务商给的配额'
  },
  'llm.timeout.seconds': {
    group: 'LLM', label: '单次超时', unit: '秒'
  },
  'llm.max.input.tokens': {
    group: 'LLM', label: '输入长度上限', unit: 'tokens'
  },
  'llm.dedup.hours': {
    group: 'LLM', label: '重复分析间隔', unit: '小时',
    hint: '同一个异常这段时间内只分析一次'
  },
  'llm.stack.max.frames': {
    group: 'LLM', label: '堆栈保留帧数', unit: '帧',
    hint: '超长堆栈截断，避免把 token 耗在框架栈上'
  },
  'llm.temperature.milli': {
    group: 'LLM', label: '采样温度', unit: '', scale: 1000,
    hint: '值越低结论越稳定。注意部分模型只接受 1'
  },
  'llm.price.input.per1k.milli': {
    group: 'LLM', label: '输入单价', unit: '厘/千tokens',
    hint: '仅用于成本估算，不影响实际计费'
  },
  'llm.price.output.per1k.milli': {
    group: 'LLM', label: '输出单价', unit: '厘/千tokens',
    hint: '仅用于成本估算，不影响实际计费'
  },

};

/**
 * 已废弃、不再参与判定的配置项，页面不展示。
 *
 * severity.money.* 是靠关键字匹配来认定「这是资金异常」的兜底规则 ——
 * 「退款」二字出现在任何一条无关异常里都会被抬成 P1，判得粗糙且误报多，已整体移除。
 */
export const DEPRECATED = [
  'judge.resurgent.days',
  'severity.money.floor',
  'severity.money.keywords',
  // 量级门槛整体废弃：异常库已经是第一道过滤器，再按次数筛会漏掉
  // 「每小时 1 次、连续 5 小时」这类低频但严重的异常
  'judge.min.count.threshold',
  'event.push.min.count',
  // 访问地址属于部署配置，不是运行时调优参数，页面不暴露
  'push.detail.base.url'
];

/** 秒/分钟这类数字换算成人能直接读的说法，避免心算 */
export function humanize(key, value) {
  const n = Number(value);
  if (isNaN(n) || n <= 0) return '';
  const meta = META[key] || {};

  if (meta.unit === '秒' && n >= 60) {
    const m = n / 60;
    return m >= 60 ? `≈ ${(m / 60).toFixed(1)} 小时` : `= ${Number(m.toFixed(1))} 分钟`;
  }
  if (meta.unit === '分钟' && n >= 60) {
    return `= ${Number((n / 60).toFixed(1))} 小时`;
  }
  if (meta.unit === '小时' && n >= 24) {
    return `= ${Number((n / 24).toFixed(1))} 天`;
  }
  if (meta.unit === '天' && n >= 30) {
    return `≈ ${Number((n / 30).toFixed(1))} 个月`;
  }
  if (meta.unit === '行' && n >= 10000) {
    return `= ${(n / 10000).toFixed(n % 10000 === 0 ? 0 : 1)} 万行`;
  }
  return '';
}
