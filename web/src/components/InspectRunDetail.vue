<template>
  <div class="run-detail">
    <!-- 1. 采集 -->
    <div class="sec-title">
      采集
      <span class="sec-sub">窗口 {{ run.windowText || '—' }} · 耗时 {{ seconds(run.elapsedMs) }}</span>
    </div>
    <div v-if="run.message" class="run-msg" :class="{ fail: run.status === 'FAILED' }">
      {{ run.message }}
    </div>
    <div class="flow">
      <div class="step">
        <div class="num">{{ formatNum(run.totalLogs) }}</div>
        <div class="lbl">ERROR 日志</div>
        <div class="hint">SLS 聚合 {{ run.slsRows || 0 }} 行</div>
      </div>
      <i class="el-icon-right arrow"></i>
      <div class="step">
        <div class="num">{{ run.fingerprints || 0 }}</div>
        <div class="lbl">异常指纹</div>
        <div class="hint">
          系统故障 {{ run.newCount || 0 }} · 突增 {{ run.surgeCount || 0 }} · 业务预期 {{ run.quietCount || 0 }}
        </div>
      </div>
      <i class="el-icon-right arrow"></i>
      <div class="step">
        <div class="num">{{ run.templateCount || 0 }}</div>
        <div class="lbl">命中模版</div>
        <div class="hint">
          未匹配指纹 {{ run.unmatchedCount || 0 }} · 新抽模版 {{ run.templateExtracted || 0 }}
        </div>
      </div>
      <i class="el-icon-right arrow"></i>
      <div class="step">
        <div class="num">{{ run.eventCount || 0 }}</div>
        <div class="lbl">事件</div>
        <div class="hint">新建 {{ newEvents }} · 累计 {{ detail.events.length - newEvents }}</div>
        <div class="hint">同一模版的指纹合并为一个事件</div>
      </div>
      <i class="el-icon-right arrow"></i>
      <div class="step">
        <div class="num">{{ run.llmCalls || 0 }}</div>
        <div class="lbl">AI 调用</div>
        <div class="hint">{{ formatNum(totalTokens) }} token · ¥{{ money(run.cost) }}</div>
      </div>
      <i class="el-icon-right arrow"></i>
      <div class="step">
        <div class="num">{{ sentCount }}</div>
        <div class="lbl">推送卡片</div>
        <div class="hint">未推送 {{ skippedCount }}</div>
      </div>
    </div>

    <!-- 明细分三个页签：一屏只看一件事，不用上下来回滚 -->
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane :label="'事件 ' + detail.events.length" name="events">
        <div class="scope-note">本轮采集窗口里出现、新建或累计到的事件</div>
        <el-table v-if="detail.events.length" :data="detail.events" size="mini" border>
          <el-table-column label="" width="64" align="center">
            <template slot-scope="s">
              <el-tag size="mini" :type="s.row.result === 'NEW' ? 'danger' : 'info'" effect="plain">
                {{ s.row.result === 'NEW' ? '新建' : '累计' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="定级" width="60" align="center">
            <template slot-scope="s">
              <el-tag v-if="s.row.severity" size="mini" effect="dark"
                      :type="severityType(s.row.severity)">{{ s.row.severity }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="事件" min-width="260" show-overflow-tooltip>
            <template slot-scope="s">
              <a class="link" @click="$emit('open-event', s.row.eventId)">{{ s.row.title }}</a>
            </template>
          </el-table-column>
          <el-table-column label="判定依据" min-width="220" show-overflow-tooltip prop="detail" />
        </el-table>
        <div v-else class="empty">本轮没有需要关注的异常</div>
      </el-tab-pane>

      <el-tab-pane :label="'AI 调用 ' + (run.llmCalls || 0)" name="ai">
        <div v-if="run.models" class="period">模型：{{ run.models }}</div>
        <div class="period">
          本轮时间段 {{ formatTime(run.startTime) }} ~
          {{ detail.periodEnd ? formatTime(detail.periodEnd) : '现在（还在累计）' }}
        </div>
        <el-table v-if="detail.llmUsage.length" :data="detail.llmUsage" size="mini" border class="gap">
          <el-table-column label="用途" width="120">
            <template slot-scope="s">{{ sceneText(s.row.scene) }}</template>
          </el-table-column>
          <el-table-column label="模型" min-width="150">
            <template slot-scope="s">{{ s.row.model || '—' }}</template>
          </el-table-column>
          <el-table-column label="次数" width="80" align="right">
            <template slot-scope="s">
              {{ s.row.calls }}
              <span v-if="Number(s.row.failed)" class="hot">（失败 {{ s.row.failed }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="输入 token" align="right">
            <template slot-scope="s">{{ formatNum(s.row.inputTokens) }}</template>
          </el-table-column>
          <el-table-column label="输出 token" align="right">
            <template slot-scope="s">{{ formatNum(s.row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column label="费用" width="100" align="right">
            <template slot-scope="s">¥{{ money(s.row.cost) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">本轮没有调用 AI</div>

        <div v-if="detail.analyses.length" class="sub-title">事件分析明细</div>
        <el-table v-if="detail.analyses.length" :data="detail.analyses" size="mini" border>
          <el-table-column label="定级" width="64" align="center">
            <template slot-scope="s">
              <el-tag v-if="s.row.result === 'FAIL'" size="mini" type="danger">失败</el-tag>
              <el-tag v-else-if="s.row.severity" size="mini" effect="dark"
                      :type="severityType(s.row.severity)">{{ s.row.severity }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="事件" min-width="200" show-overflow-tooltip>
            <template slot-scope="s">
              <a class="link" @click="$emit('open-event', s.row.eventId)">{{ s.row.title }}</a>
            </template>
          </el-table-column>
          <el-table-column label="结论" min-width="280" show-overflow-tooltip prop="detail" />
          <el-table-column label="Token" width="90" align="right">
            <template slot-scope="s">
              {{ formatNum(Number(s.row.inputTokens || 0) + Number(s.row.outputTokens || 0)) }}
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="'推送 ' + sentCount" name="push">
        <div class="scope-note">
          推送扫描的是<b>全部未处理事件</b>，不只是本轮采集到的 ——
          一直没人处理的旧事件到了重推时间也会在这里出现，标「非本轮」。
        </div>
        <div v-if="!detail.pushes.length" class="empty">本轮没有推送，也没有到推送时机的事件</div>
        <!-- 左边挑、右边看：已发送的看卡片，未推送的看原因，右边永远有内容 -->
        <div v-else class="push-split">
          <div class="push-list">
            <div class="group-title">已发送 {{ sent.length }}</div>
            <div v-if="!sent.length" class="empty group-empty">本轮没有发出卡片</div>
            <div v-for="p in sent" :key="p.id" class="push-item clickable"
                 :class="{ active: p.id === selectedId }" @click="select(p)">
              <div class="push-top">
                <el-tag size="mini" :type="pushType(p.result)" effect="plain">{{ pushText(p.result) }}</el-tag>
                <el-tag v-if="p.severity" size="mini" effect="dark"
                        :type="severityType(p.severity)">{{ p.severity }}</el-tag>
                <el-tag v-if="!inRound(p)" size="mini" type="info">非本轮</el-tag>
                <span class="push-time">{{ formatTime(p.createTime).substring(11) }}</span>
              </div>
              <div class="push-title">{{ p.title }}</div>
              <div class="push-detail">{{ p.detail }}</div>
            </div>

            <!-- 未推送按原因归组、默认收起：它们是「为什么没打扰你」，不是本轮的主角 -->
            <div v-if="skippedGroups.length" class="group-title skip-title" @click="skipOpen = !skipOpen">
              <i :class="skipOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
              未推送 {{ skippedCount }}
            </div>
            <template v-if="skipOpen">
              <div v-for="g in skippedGroups" :key="g.reason" class="skip-group">
                <div class="skip-reason">{{ g.reason }} <span class="muted">{{ g.items.length }}</span></div>
                <div v-for="p in g.items" :key="p.id" class="skip-item"
                     :class="{ active: p.id === selectedId }" @click="select(p)">
                  <el-tag v-if="p.severity" size="mini" effect="plain"
                          :type="severityType(p.severity)">{{ p.severity }}</el-tag>
                  <span class="skip-name">{{ p.title }}</span>
                  <el-tag v-if="!inRound(p)" size="mini" type="info">非本轮</el-tag>
                </div>
              </div>
            </template>
            <div v-else-if="skippedGroups.length" class="skip-summary" @click="skipOpen = true">
              <span v-for="g in skippedGroups" :key="g.reason">{{ shortReason(g.reason) }} {{ g.items.length }}</span>
            </div>
          </div>

          <div class="push-card">
            <!-- 已发送：卡片 -->
            <template v-if="selected && selected.cardJson">
              <div class="card-bar">
                <el-radio-group v-model="cardMode" size="mini">
                  <el-radio-button label="preview">预览</el-radio-button>
                  <el-radio-button label="json">原文 JSON</el-radio-button>
                </el-radio-group>
                <span class="muted card-hint">预览为近似还原，以群里实际显示为准</span>
              </div>
              <div v-loading="cardLoading">
                <div v-if="cardMode === 'preview'" v-html="cardHtml"></div>
                <pre v-else class="card-json">{{ cardPretty }}</pre>
              </div>
            </template>
            <!-- 未推送 / 发送失败：说清楚为什么，给个去处 -->
            <div v-else-if="selected" class="reason-card">
              <div class="reason-head">
                <el-tag size="mini" :type="pushType(selected.result)" effect="plain">
                  {{ pushText(selected.result) }}
                </el-tag>
                <el-tag v-if="selected.severity" size="mini" effect="dark"
                        :type="severityType(selected.severity)">{{ selected.severity }}</el-tag>
                <el-tag v-if="!inRound(selected)" size="mini" type="info">非本轮采集</el-tag>
              </div>
              <div class="reason-title">{{ selected.title }}</div>
              <div class="reason-label">没有推送的原因</div>
              <div class="reason-text">{{ selected.detail || '—' }}</div>
              <div class="reason-hint">{{ reasonHint(selected.detail) }}</div>
              <el-button size="small" @click="$emit('open-event', selected.eventId)">查看事件详情</el-button>
            </div>
            <!-- 什么都没选：给这一轮推送情况的总结，不留白 -->
            <div v-else class="reason-card">
              <div class="reason-title">本轮没有发出卡片</div>
              <div v-for="g in skippedGroups" :key="g.reason" class="summary-line">
                <b>{{ g.items.length }}</b> 条 · {{ g.reason }}
              </div>
              <div class="reason-hint">点左侧任意一条查看详情</div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { formatTime, formatNum, SEVERITY_TAG } from '../utils/inspectFormat';
import { getRunCard } from '../api/inspect';
import { renderCard } from '../utils/feishuCardPreview';

const SCENES = {
  ANALYZE: '事件分析',
  TEMPLATE_EXTRACT: '抽取模版',
  TEMPLATE_REVIEW: '模版复核',
  PING: '连通性检测'
};

export default {
  name: 'InspectRunDetail',
  props: {
    detail: { type: Object, required: true }
  },
  data() {
    return {
      tab: 'events',
      selectedId: null,
      skipOpen: false,
      cardJson: '',
      cardMode: 'preview',
      cardLoading: false
    };
  },
  mounted() {
    // 有卡片发出的轮次，默认落在推送页签并直接展示第一张 ——
    // 「这轮推了什么」是点进来最想看的
    const first = this.sent.find((p) => p.cardJson);
    if (first) {
      this.tab = 'push';
      this.select(first);
    }
  },
  computed: {
    roundEventIds() {
      return new Set(this.detail.events.map((e) => e.eventId));
    },
    // 已发送在前（含发送失败，那是要关注的），未推送单独归组
    sent() {
      return this.detail.pushes.filter((p) => p.result !== 'SKIP');
    },
    skippedGroups() {
      const map = {};
      this.detail.pushes.filter((p) => p.result === 'SKIP').forEach((p) => {
        const reason = this.normReason(p.detail);
        (map[reason] = map[reason] || []).push(p);
      });
      return Object.keys(map).map((reason) => ({ reason, items: map[reason] }))
        .sort((a, b) => b.items.length - a.items.length);
    },
    selected() {
      return this.detail.pushes.find((p) => p.id === this.selectedId) || null;
    },
    cardHtml() {
      return this.cardJson ? renderCard(this.cardJson) : '';
    },
    cardPretty() {
      try {
        return JSON.stringify(JSON.parse(this.cardJson), null, 2);
      } catch (e) {
        return this.cardJson;
      }
    },
    run() {
      return this.detail.run || {};
    },
    newEvents() {
      return this.detail.events.filter((e) => e.result === 'NEW').length;
    },
    sentCount() {
      return this.detail.pushes.filter((p) => p.result === 'SENT').length;
    },
    skippedCount() {
      return this.detail.pushes.filter((p) => p.result === 'SKIP').length;
    },
    totalTokens() {
      return Number(this.run.inputTokens || 0) + Number(this.run.outputTokens || 0);
    }
  },
  methods: {
    select(p) {
      this.selectedId = p.id;
      if (p.cardJson) this.showCard(p.id);
    },

    inRound(p) {
      return this.roundEventIds.has(p.eventId);
    },

    /** 「定级 P2 未达推送门槛」「定级 P3 未达推送门槛」归成一组，按级别分组没有意义 */
    normReason(d) {
      if (!d) return '其他';
      if (/未达推送门槛/.test(d)) return '定级未达推送门槛';
      if (/尚未定级/.test(d)) return '尚未定级，等 AI 分析完再推';
      if (/业务预期/.test(d)) return 'AI 判定为业务预期';
      if (/上限/.test(d)) return '当日推送已达上限';
      return d;
    },

    shortReason(r) {
      return { '定级未达推送门槛': '未达门槛', '尚未定级，等 AI 分析完再推': '未定级',
        'AI 判定为业务预期': '业务预期', '当日推送已达上限': '达上限' }[r] || r;
    },

    reasonHint(d) {
      if (!d) return '';
      if (/未达推送门槛/.test(d)) return '推送门槛在配置页「推送」分组里调整。这类事件照常记录，页面上能看到，只是不进群打扰人';
      if (/尚未定级/.test(d)) return 'AI 分析完成、拿到定级后，下一轮会重新判断要不要推';
      if (/业务预期/.test(d)) return '如果你认为 AI 判错了，打开事件详情看它的依据，必要时手动推送';
      if (/上限/.test(d)) return '当天新事件推送数已达上限。持续触顶说明线上确实在出事，或判定阈值偏松';
      return '';
    },

    async showCard(itemId) {
      this.cardJson = '';
      this.cardLoading = true;
      try {
        const res = await getRunCard(itemId);
        if (res.data && res.data.code === '0') {
          this.cardJson = res.data.data || '';
        } else {
          this.$message.error((res.data && res.data.msg) || '加载卡片失败');
        }
      } catch (e) {
        this.$message.error('加载卡片失败：' + (e.message || e));
      } finally {
        this.cardLoading = false;
      }
    },

    sceneText(s) {
      return SCENES[s] || s;
    },
    pushType(r) {
      return { SENT: 'success', FAIL: 'danger', SKIP: 'info' }[r] || 'info';
    },
    pushText(r) {
      return { SENT: '已发送', FAIL: '失败', SKIP: '未推送' }[r] || r;
    },
    severityType(s) {
      return SEVERITY_TAG[s] || 'info';
    },
    money(v) {
      const n = Number(v || 0);
      return n < 0.01 && n > 0 ? n.toFixed(4) : n.toFixed(2);
    },
    seconds(ms) {
      if (!ms) return '—';
      return (Number(ms) / 1000).toFixed(1) + 's';
    },
    formatTime(t) {
      return formatTime(t);
    },
    formatNum(n) {
      return formatNum(n);
    }
  }
};
</script>

<style scoped>
.run-detail {
  padding: 4px 12px 12px;
}

.sec-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin: 14px 0 8px;
}

.sec-title:first-child {
  margin-top: 0;
}

.sec-sub {
  font-weight: normal;
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.run-msg {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.run-msg.fail {
  color: #e6564a;
}

/* 采集到推送一条线看下来：每一步收窄了多少一目了然 */
.flow {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: 6px;
}

.step {
  flex: 1 1 120px;
  min-width: 110px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 10px;
  background: #fff;
}

.num {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.lbl {
  font-size: 12px;
  color: #606266;
}

.hint {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.arrow {
  align-self: center;
  color: #c0c4cc;
}

.tabs {
  margin-top: 16px;
}

.period {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.gap {
  margin-bottom: 14px;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}

.scope-note {
  font-size: 12px;
  color: #909399;
  margin-bottom: 10px;
}

.group-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin: 4px 0 2px;
}

.group-empty {
  padding: 4px 0 8px;
}

.skip-title {
  margin-top: 12px;
  cursor: pointer;
  user-select: none;
}

.skip-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: #909399;
  cursor: pointer;
}

.skip-group {
  margin-top: 6px;
}

.skip-reason {
  font-size: 12px;
  color: #909399;
  margin-bottom: 2px;
}

.skip-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
  color: #606266;
}

.skip-item:hover {
  background: #f5f7fa;
}

.skip-item.active {
  background: #ecf5ff;
}

.skip-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reason-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px 18px;
  background: #fafafa;
}

.reason-head {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}

.reason-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
  word-break: break-all;
}

.reason-label {
  font-size: 12px;
  color: #909399;
}

.reason-text {
  font-size: 13px;
  color: #303133;
  margin: 2px 0 8px;
}

.reason-hint {
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
  line-height: 1.6;
}

.summary-line {
  font-size: 13px;
  color: #606266;
  margin-bottom: 4px;
}

.push-split {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.push-list {
  flex: 0 0 300px;
  max-height: 70vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.push-item {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 10px;
}

.push-item.clickable {
  cursor: pointer;
}

.push-item.clickable:hover {
  border-color: #c6e2ff;
}

.push-item.active {
  border-color: #409eff;
  background: #f5f9ff;
}

.push-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.push-time {
  margin-left: auto;
  font-size: 11px;
  color: #c0c4cc;
}

.push-title {
  font-size: 13px;
  color: #303133;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.push-detail {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.push-card {
  flex: 1;
  min-width: 0;
}

.card-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.card-hint {
  font-size: 11px;
}

.card-empty {
  padding: 40px 0;
  text-align: center;
}

.card-json {
  max-height: 64vh;
  overflow: auto;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}

.hot {
  color: #e6564a;
}

.empty {
  font-size: 12px;
  color: #c0c4cc;
}

.muted {
  color: #c0c4cc;
}

.link {
  color: #409eff;
  cursor: pointer;
}
</style>
