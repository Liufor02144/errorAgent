<template>
  <el-drawer :visible.sync="innerVisible" size="60%" :with-header="false"
             direction="rtl" @opened="onOpened">
    <div class="detail-wrap" v-loading="loading">
      <template v-if="detail">
        <!-- 标题 -->
        <div class="detail-header">
          <div class="head-left">
            <el-tag v-if="event.severity" size="small" effect="dark"
                    :type="severityTagType(event.severity)">{{ event.severity }}</el-tag>
            <span class="head-title">{{ headline }}</span>
            <el-tag :type="event.eventType === 'SURGE' ? 'warning' : 'danger'"
                    size="mini" effect="plain">
              {{ event.eventType === 'SURGE' ? '突增' : '新增' }}
            </el-tag>
          </div>
          <el-button size="mini" icon="el-icon-close" circle
                     @click="innerVisible = false" />
        </div>
        <div class="app-line">
          <span v-if="event.appCount > 1" class="multi-app">{{ event.appCount }} 个服务</span>
          <span v-else>{{ event.appId }}</span>
          <span v-if="subline"> · {{ subline }}</span>
        </div>

        <!-- 持续横幅：还在涨的问题要一眼看出来 -->
        <div class="dur-banner" :class="durBannerClass">
          已持续 <b>{{ durationText }}</b>，累计 <b>{{ formatNum(event.totalCount) }}</b> 次
          <span v-if="event.pushCount > 0" class="dur-push">
            · 已提醒 {{ event.pushCount }} 次
          </span>
          <span v-if="event.handled" class="dur-handled">· 已标注，停止提醒</span>
        </div>

        <!-- 事实区：系统算出来的，不经模型 -->
        <div class="section">
          <div class="section-title">事实</div>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="近 24h">
              {{ formatNum(detail.count24h) }} 次
            </el-descriptions-item>
            <el-descriptions-item label="近 7d">
              {{ formatNum(detail.count7d) }} 次
            </el-descriptions-item>
            <el-descriptions-item label="事件开始">
              {{ formatTime(event.firstSeenTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="最近出现">
              {{ formatTime(event.lastSeenTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="为什么报出来" :span="2">
              {{ event.triggerReason || '—' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="event.appCount > 1" label="涉及服务" :span="2">
              {{ event.appList || '—' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="event.frameList" label="涉及代码位置" :span="2">
              <span class="mono">{{ event.frameList }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="出错位置" :span="2">
              <span v-if="fp.hasStack" class="mono">{{ fp.bizFrame }}</span>
              <span v-else class="mono muted">
                {{ fp.loggerClass }} —— 这是日志打印位置，不是异常抛出点
              </span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- AI 判断 -->
        <div class="section">
          <div class="section-title">
            AI 判断
            <span v-if="analysis" class="title-extra">
              置信度 {{ analysis.confidence }}
              <template v-if="analysis.locateConfidence">
                · 代码定位 {{ analysis.locateConfidence }}
              </template>
            </span>
            <el-button v-if="!analysis" size="mini" type="primary" plain
                       :loading="analyzing" @click="runAnalyze">立即分析</el-button>
          </div>
          <div v-if="!analysis" class="empty-tip">
            尚未分析。可能是未达量级门槛、被闸门拦截，或调用失败。
          </div>
          <div v-else class="ai-body">
            <div class="ai-item">
              <span class="ai-label">根因</span>
              <span>{{ analysis.rootCause || '—' }}</span>
            </div>
            <div class="ai-item">
              <span class="ai-label">影响</span>
              <span>{{ analysis.businessImpact || '—' }}</span>
            </div>
            <div class="ai-meta">
              {{ analysis.model }} · {{ analysis.inputTokens }}+{{ analysis.outputTokens }} tokens
              · {{ analysis.elapsedMs }}ms · {{ formatTime(analysis.createTime) }}
            </div>
          </div>
        </div>

        <!-- 调用链：核对根因的依据 —— 链路上哪一步失败、哪一步慢 -->
        <div class="section" v-if="analysis && analysis.traceChain">
          <div class="section-title toggle" @click="chainOpen = !chainOpen">
            <i :class="chainOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
            调用链
            <span class="title-extra">「← 失败起点」「← 最耗时」由系统按链路数据标出</span>
          </div>
          <pre v-show="chainOpen" class="code-block">{{ analysis.traceChain }}</pre>
        </div>

        <!-- 代码 -->
        <div class="section" v-if="analysis && !analysis.codeSnippet">
          <div class="section-title">代码片段</div>
          <div class="empty-tip">
            未取到代码片段。可能是仓库未登记、尚未克隆完成，或堆栈中没有业务栈帧。
          </div>
        </div>
        <div class="section" v-if="analysis && analysis.codeSnippet">
          <div class="section-title toggle" @click="codeOpen = !codeOpen">
            <i :class="codeOpen ? 'el-icon-arrow-down' : 'el-icon-arrow-right'"></i>
            代码片段
            <span class="title-extra mono">{{ analysis.codeRef }}</span>
          </div>
          <pre v-show="codeOpen" class="code-block">{{ analysis.codeSnippet }}</pre>
          <div v-if="codeOpen && analysis.locateConfidence && analysis.locateConfidence !== 'HIGH'"
               class="locate-warn">
            代码定位置信度为 {{ analysis.locateConfidence }}，以上片段未必是真正的出错位置
          </div>
        </div>

        <!-- 趋势 -->
        <div class="section" v-if="detail.trend && detail.trend.length">
          <div class="section-title">最近 7 天趋势（按小时）</div>
          <div ref="trendChart" class="trend-chart"></div>
        </div>

        <!-- 原始日志 -->
        <div class="section" v-if="!detail.samples || !detail.samples.length">
          <div class="section-title">原始报错</div>
          <div class="empty-tip">
            暂无日志样本。样本在指纹达到量级门槛、进入待分析队列时才会抓取；
            首轮采集只建立基线，不抓样本。
          </div>
        </div>
        <div class="section" v-if="detail.samples && detail.samples.length">
          <div class="section-title">原始报错（{{ detail.samples.length }} 条样本）</div>
          <el-tabs v-model="activeSample" type="card">
            <el-tab-pane v-for="(s, i) in detail.samples" :key="s.id"
                         :label="formatTime(s.logTime)" :name="String(i)">
              <div class="sample-meta">
                <span v-if="s.traceId">traceId: {{ s.traceId }}</span>
                <span v-if="s.podName">pod: {{ s.podName }}</span>
                <span v-if="s.imageTag">版本: {{ s.imageTag }}</span>
              </div>
              <pre class="code-block raw">{{ s.rawMessage }}</pre>
            </el-tab-pane>
          </el-tabs>
        </div>

        <!-- 处理：只有两个动作 -->
        <div class="section action-section">
          <div class="section-title">处理</div>
          <div v-if="event.handled" class="handled-tip">
            已由 {{ event.handler || '未知' }} 于 {{ formatTime(event.handleTime) }} 标注：
            {{ event.handleNote }}
          </div>
          <div v-else class="action-row">
            <el-button size="small" type="primary" :loading="submitting"
                       @click="promptHandle">标注（我处理了）</el-button>
            <el-button size="small" :loading="submitting"
                       @click="promptMarkExpected">标记业务预期</el-button>
          </div>
          <div v-if="!event.handled" class="action-hint">
            标注后停止升级重推，群里那张卡片会同步变成「已标注」。<br />
            标记业务预期后这条正常抛出不再报，但<b>量级突增时仍会报</b> —— 标记不等于屏蔽。
          </div>
          <div v-if="inLibrary" class="lib-tip">
            这个模版已标记为业务预期，当前事件是因为<b>量级突增</b>才报出来的。
          </div>
          <div v-if="detail && detail.template" class="tpl-tip">
            归属模版：<code>{{ detail.template.template || detail.template.matchValue }}</code>
            <el-tag v-if="detail.template.businessExpected === 1" type="success"
                    size="mini" effect="plain">业务预期</el-tag>
            <el-tag v-else type="warning" size="mini" effect="plain">未确认</el-tag>
          </div>
        </div>

      </template>
    </div>
  </el-drawer>
</template>

<script>
import * as echarts from 'echarts';
import { getEventDetail, handleEvent, markEventExpected, analyze } from '../api/inspect';
import {
  formatTime, formatTimeShort, formatNum, sublineOf,
  SEVERITY_TAG, durationTextOf
} from '../utils/inspectFormat';

export default {
  name: 'InspectDetailDrawer',
  props: {
    visible: { type: Boolean, default: false },
    eventId: { type: Number, default: null }
  },
  data() {
    return {
      innerVisible: false,
      loading: false,
      submitting: false,
      analyzing: false,
      detail: null,
      activeSample: '0',
      // 调用链默认展开：哪一步失败、哪一步慢，比单段代码更能说明问题；代码片段需要时再点开
      chainOpen: true,
      codeOpen: false,
      chart: null
    };
  },
  computed: {
    event() {
      return (this.detail && this.detail.event) || {};
    },
    fp() {
      return (this.detail && this.detail.fingerprint) || {};
    },
    inLibrary() {
      return !!(this.detail && this.detail.inLibrary);
    },
    durationText() {
      return durationTextOf(this.event.firstSeenTime, this.event.lastSeenTime);
    },
    /** 拖得越久越刺眼 */
    durBannerClass() {
      if (this.event.handled) return 'dur-done';
      const ms = new Date(this.event.lastSeenTime) - new Date(this.event.firstSeenTime);
      if (ms >= 6 * 3600 * 1000) return 'dur-long';
      if (ms >= 3600 * 1000) return 'dur-mid';
      return '';
    },
    analysis() {
      return this.detail && this.detail.analysis;
    },
    headline() {
      return this.event.headline || '—';
    },
    subline() {
      return sublineOf(this.fp);
    },
  },
  watch: {
    visible(v) {
      this.innerVisible = v;
      if (v && this.eventId) this.load();
    },
    innerVisible(v) {
      this.$emit('update:visible', v);
      if (!v) this.disposeChart();
    },
    eventId(v) {
      if (v && this.innerVisible) this.load();
    }
  },
  beforeDestroy() {
    this.disposeChart();
  },
  methods: {
    async load() {
      this.loading = true;
      this.detail = null;
      try {
        const res = await getEventDetail(this.eventId, { trendDays: 7, sampleLimit: 5 });
        if (res.data && res.data.code === '0') {
          this.detail = res.data.data;
          this.activeSample = '0';
          this.chainOpen = true;
          this.codeOpen = false;
          this.$nextTick(this.renderChart);
        } else {
          this.$message.error((res.data && res.data.msg) || '加载失败');
        }
      } catch (e) {
        this.$message.error('加载失败：' + (e.message || e));
      } finally {
        this.loading = false;
      }
    },

    onOpened() {
      this.$nextTick(this.renderChart);
    },

    renderChart() {
      const el = this.$refs.trendChart;
      const trend = (this.detail && this.detail.trend) || [];
      if (!el || !trend.length) return;

      this.disposeChart();
      this.chart = echarts.init(el);
      this.chart.setOption({
        grid: { left: 50, right: 20, top: 20, bottom: 40 },
        tooltip: {
          trigger: 'axis',
          // 轴上是短格式，悬浮时给完整时间
          formatter: (params) => {
            if (!params || !params.length) return '';
            const i = params[0].dataIndex;
            return `${formatTime(trend[i].statHour)}<br/>${params[0].value} 次`;
          }
        },
        xAxis: {
          type: 'category',
          data: trend.map(t => formatTimeShort(t.statHour)),
          axisLabel: { fontSize: 10, rotate: 30 }
        },
        yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
        series: [{
          type: 'bar',
          data: trend.map(t => Number(t.cnt || 0)),
          itemStyle: { color: '#f56c6c' }
        }]
      });
    },

    disposeChart() {
      if (this.chart) {
        this.chart.dispose();
        this.chart = null;
      }
    },

    async runAnalyze() {
      this.analyzing = true;
      try {
        const res = await analyze(this.fp.id, true);
        if (res.data && res.data.code === '0') {
          this.$message.success('分析完成');
          this.load();
        } else {
          this.$message.error((res.data && res.data.msg) || '分析失败');
        }
      } catch (e) {
        this.$message.error('分析失败：' + (e.message || e));
      } finally {
        this.analyzing = false;
      }
    },

    promptHandle() {
      this.$prompt('简单说明处理情况（会记录到这条事件上，并停止升级重推）', '标注', {
        inputPlaceholder: '例如：已联系运维重启 Redis，10:40 恢复',
        inputValidator: (v) => (v && v.trim() ? true : '请填写处理说明')
      }).then(({ value }) => this.doHandle(value.trim())).catch(() => {});
    },

    async doHandle(note) {
      // 操作人由后端从登录态取，前端不传
      this.submitting = true;
      try {
        const res = await handleEvent(this.eventId, note);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          this.$message.success('已标注' + (d.cardSynced ? '，群里卡片已同步' : ''));
          this.load();
          this.$emit('handled');
        } else {
          this.$message.error((res.data && res.data.msg) || '标注失败');
        }
      } catch (e) {
        this.$message.error('标注失败：' + (e.message || e));
      } finally {
        this.submitting = false;
      }
    },

    promptMarkExpected() {
      this.$prompt(
        '标记后这条异常正常抛出不再报，但量级突增时仍会报出来。请说明为什么它属于业务预期：',
        '标记业务预期',
        {
          inputPlaceholder: '例如：服务停止时间内的正常业务拒绝，不是系统故障',
          inputValidator: (v) => (v && v.trim() ? true : '请填写原因，否则以后没人知道为什么放行')
        }
      ).then(({ value }) => this.doMarkExpected(value.trim())).catch(() => {});
    },

    async doMarkExpected(reason) {
      this.submitting = true;
      try {
        const res = await markEventExpected(this.eventId, reason);
        if (res.data && res.data.code === '0') {
          this.$message.success('已标记为业务预期');
          this.load();
          this.$emit('handled');
        } else {
          this.$message.error((res.data && res.data.msg) || '操作失败');
        }
      } catch (e) {
        this.$message.error('操作失败：' + (e.message || e));
      } finally {
        this.submitting = false;
      }
    },

    severityTagType(s) {
      return SEVERITY_TAG[s] || 'info';
    },
    formatNum(n) {
      return formatNum(n);
    },
    formatTime(t) {
      return formatTime(t);
    }
  }
};
</script>

<style scoped>
.dur-banner {
  margin: 10px 0 4px;
  padding: 8px 12px;
  border-radius: 4px;
  background: #f4f4f5;
  color: #606266;
  font-size: 13px;
}

.dur-banner.dur-mid {
  background: #fdf6ec;
  color: #e6a23c;
}

.dur-banner.dur-long {
  background: #fef0f0;
  color: #f56c6c;
}

.dur-banner.dur-done {
  background: #f0f9eb;
  color: #67c23a;
}

.dur-push,
.dur-handled {
  margin-left: 4px;
}

.multi-app {
  color: #e6a23c;
  font-weight: 500;
}

.lib-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #e6a23c;
}

.tpl-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.tpl-tip code {
  font-family: Menlo, Consolas, monospace;
  background: #f5f7fa;
  padding: 1px 5px;
  border-radius: 3px;
}

.detail-wrap {
  padding: 20px 24px 40px;
  height: 100%;
  overflow-y: auto;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head-title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}

.app-line {
  color: #909399;
  font-size: 13px;
  margin: 4px 0 16px;
  font-family: Menlo, Consolas, monospace;
}

.section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-title.toggle {
  cursor: pointer;
  user-select: none;
}

.title-extra {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.ai-body {
  background: #f8f9fb;
  border-radius: 4px;
  padding: 12px 14px;
}

.ai-item {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
  line-height: 1.6;
  font-size: 13px;
}

.ai-label {
  flex-shrink: 0;
  width: 34px;
  color: #909399;
}

.ai-meta {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 6px;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  background: #f8f9fb;
  padding: 12px 14px;
  border-radius: 4px;
}

.code-block {
  background: #282c34;
  color: #abb2bf;
  padding: 12px 14px;
  border-radius: 4px;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre;
  margin: 0;
}

.code-block.raw {
  max-height: 340px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.locate-warn {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 6px;
}

.trend-chart {
  height: 180px;
  width: 100%;
}

.sample-meta {
  font-size: 11px;
  color: #909399;
  margin-bottom: 6px;
  display: flex;
  gap: 16px;
  font-family: Menlo, Consolas, monospace;
}

.action-section {
  background: #fafafa;
  padding: 14px;
  border-radius: 4px;
}

.action-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.action-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.handled-tip {
  font-size: 12px;
  color: #67c23a;
  margin-bottom: 8px;
}

.mono {
  font-family: Menlo, Consolas, monospace;
}

.muted {
  color: #c0c4cc;
}
</style>
