import api, { getApiPath } from './client';

// ---------------- 异常事件（页面主数据源） ----------------

export const getEventOverview = () =>
  api.get(getApiPath('/inspect/event/overview'));

export const getEventAppOptions = () =>
  api.get(getApiPath('/inspect/event/apps'));

export const getEvents = (params) =>
  api.get(getApiPath('/inspect/event'), { params });

export const getEventDetail = (eventId, params) =>
  api.get(getApiPath(`/inspect/event/${eventId}`), { params });

// 标注：这条我处理了。标注后停止升级重推，群里的卡片同步成已处理。
// 不传操作人——后端从登录态取，前端自报姓名等于谁都能冒充
export const handleEvent = (eventId, note) =>
  api.post(getApiPath(`/inspect/event/${eventId}/handle`), null, { params: { note } });

// 标记业务预期：这是业务逻辑正常走到的分支，正常抛出不报；突增仍会报
export const markEventExpected = (eventId, reason) =>
  api.post(getApiPath(`/inspect/event/${eventId}/expected`), null, { params: { reason } });

// ---------------- 巡检记录：每轮采集一条 ----------------
export const getRuns = (params) =>
  api.get(getApiPath('/inspect/run'), { params });

// 立即巡检：后台执行，立刻返回
export const triggerRun = () =>
  api.post(getApiPath('/inspect/run/trigger'));

export const getRunDetail = (id) =>
  api.get(getApiPath(`/inspect/run/${id}`));

export const getRunCard = (itemId) =>
  api.get(getApiPath(`/inspect/run/item/${itemId}/card`));

// ---------------- 批量操作：body 为 { ids, note | reason } ----------------
export const batchHandleEvents = (ids, note) =>
  api.post(getApiPath('/inspect/event/batch/handle'), { ids, note });

export const batchMarkEventsExpected = (ids, reason) =>
  api.post(getApiPath('/inspect/event/batch/expected'), { ids, reason });

export const batchMarkTemplatesExpected = (ids, reason) =>
  api.post(getApiPath('/inspect/event/library/batch/expected'), { ids, reason });

export const batchUnmarkTemplates = (ids) =>
  api.post(getApiPath('/inspect/event/library/batch/unexpected'), { ids });

export const batchRemoveTemplates = (ids) =>
  api.post(getApiPath('/inspect/event/library/batch/remove'), { ids });

// ---------------- 异常模版库 ----------------

// businessExpected: 1-只看业务预期 / 0-只看未确认 / 不传-全部
export const getLibrary = (params) =>
  api.get(getApiPath('/inspect/event/library'), { params });

// AI 复核单个模版：能确定是业务预期的自动标记，没把握的把结论写进说明
// 复核要等模型返回，比普通接口慢得多，单独放宽超时（默认 10s 会稳定失败）
export const reviewTemplate = (id) =>
  api.post(getApiPath(`/inspect/event/library/${id}/review`), null,
    { params: { force: true }, timeout: 120000 });

export const extractTemplates = (max) =>
  api.post(getApiPath('/inspect/event/library/extract'), null,
    { params: { force: true, max } });

// 详情返回 {template, sources[], appCount, appList, fpCount, totalCount}
// sources 是这个模版覆盖的各个来源：服务、代码位置、原始样例
export const getTemplateDetail = (id) =>
  api.get(getApiPath(`/inspect/event/library/${id}`));

export const markTemplateExpected = (id, reason) =>
  api.post(getApiPath(`/inspect/event/library/${id}/expected`), null, { params: { reason } });

export const unmarkTemplateExpected = (id) =>
  api.post(getApiPath(`/inspect/event/library/${id}/unexpected`));

// 模型生成的正则可能写宽或写窄，这个入口是必需的兜底
export const updateTemplatePattern = (id, template, pattern) =>
  api.post(getApiPath(`/inspect/event/library/${id}/pattern`), null,
    { params: { template, pattern } });

export const removeFromLibrary = (id) =>
  api.post(getApiPath(`/inspect/event/library/${id}/remove`));

export const updateLibraryReason = (id, reason) =>
  api.post(getApiPath(`/inspect/event/library/${id}/reason`), null, { params: { reason } });

// ---------------- 指纹详情（事件的下钻） ----------------

export const getOverview = () =>
  api.get(getApiPath('/inspect/fingerprint/overview'));

export const getAppOptions = () =>
  api.get(getApiPath('/inspect/fingerprint/apps'));

export const getFingerprints = (params) =>
  api.get(getApiPath('/inspect/fingerprint'), { params });

export const getFingerprintDetail = (fpId, params) =>
  api.get(getApiPath(`/inspect/fingerprint/${fpId}`), { params });

// ---------------- 推送 ----------------

export const pushCard = (fpId, force = false) =>
  api.post(getApiPath(`/inspect/push/${fpId}`), null, { params: { force } });

export const pingPush = () =>
  api.post(getApiPath('/inspect/push/ping'));

// ---------------- AI 分析 ----------------

export const analyze = (fpId, force = true) =>
  api.post(getApiPath(`/inspect/analyze/${fpId}`), null, { params: { force } });

export const previewPrompt = (fpId) =>
  api.get(getApiPath(`/inspect/analyze/${fpId}/prompt`));

export const getCost = (days = 30) =>
  api.get(getApiPath('/inspect/analyze/cost'), { params: { days } });

// ---------------- 配置 ----------------

export const getConfigs = () =>
  api.get(getApiPath('/inspect/config'));

// 配置页按分组渲染，并标出哪些被改过（当前值 ≠ 默认值）
export const getGroupedConfigs = () =>
  api.get(getApiPath('/inspect/config/grouped'));

// 后端收 @RequestBody，不是 query 参数
export const saveConfig = (cfgKey, cfgValue) =>
  api.post(getApiPath('/inspect/config'), { cfgKey, cfgValue });

export const resetConfig = (cfgKey) =>
  api.post(getApiPath(`/inspect/config/${cfgKey}/reset`));

// ---------------- 采集与代码仓库 ----------------

export const triggerCollect = () =>
  api.post(getApiPath('/inspect/collect/trigger'));

export const getCollectSwitch = () =>
  api.get(getApiPath('/inspect/collect/switch'));

export const getProjects = () =>
  api.get(getApiPath('/inspect/project'));

export const syncProject = (id) =>
  api.post(getApiPath(`/inspect/project/${id}/sync`));
