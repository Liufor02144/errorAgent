<template>
  <div class="run-panel">
    <el-card shadow="never" class="filter-card">
      <div class="meta-line">
        {{ intervalText }}一轮采集记一条，点一行看这一轮的完整流程。AI 分析与推送是独立任务，
        它们的动作归入当时最近的一轮 —— 即「从这轮采集开始，到下一轮采集开始」之间发生的事。
      </div>
      <div class="filter-bar">
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="状态">
            <el-radio-group v-model="status" size="small" @change="onFilter">
              <el-radio-button label="">全部</el-radio-button>
              <el-radio-button label="SUCCESS">成功</el-radio-button>
              <el-radio-button label="FAILED">失败</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <div class="filter-actions">
          <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
          <el-tooltip content="马上跑一轮：采集 → 聚合 → AI 分析 → 推送。约 1 分钟，列表里会先出现一条「进行中」"
                      placement="bottom">
            <el-button size="small" type="primary" icon="el-icon-video-play" :loading="triggering"
                       @click="trigger">立即巡检</el-button>
          </el-tooltip>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" v-loading="loading" size="small" border row-key="id"
                highlight-current-row :row-class-name="rowClass" @row-click="open">
        <el-table-column label="开始时间" width="150">
          <template slot-scope="s">
            {{ formatTime(s.row.startTime) }}
            <el-tag v-if="s.row.triggerType === 'MANUAL'" size="mini" type="info">手动</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="76" align="center">
          <template slot-scope="s">
            <el-tooltip :disabled="!s.row.message" :content="s.row.message || ''">
              <el-tag size="mini" :type="statusType(s.row.status)" effect="plain">
                {{ statusText(s.row.status) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="ERROR 日志" width="96" align="right">
          <template slot-scope="s">{{ formatNum(s.row.totalLogs) }}</template>
        </el-table-column>
        <el-table-column label="异常指纹" min-width="210">
          <template slot-scope="s">
            <b>{{ s.row.fingerprints || 0 }}</b>
            <span class="sub">
              系统故障 <span :class="{ hot: s.row.newCount > 0 }">{{ s.row.newCount || 0 }}</span>
              · 突增 <span :class="{ hot: s.row.surgeCount > 0 }">{{ s.row.surgeCount || 0 }}</span>
              · 业务预期 {{ s.row.quietCount || 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模版" width="120">
          <template slot-scope="s">
            {{ s.row.templateCount || 0 }}
            <span v-if="s.row.unmatchedCount" class="sub">未匹配 {{ s.row.unmatchedCount }}</span>
          </template>
        </el-table-column>
        <!-- 同一模版的多个指纹合并成一个事件；已有未处理事件的只累计次数，不新开 -->
        <el-table-column label="事件" min-width="130">
          <template slot-scope="s">
            <b>{{ s.row.eventCount || 0 }}</b>
            <span v-if="s.row.eventCount" class="sub">
              新建 <span :class="{ hot: s.row.eventNew > 0 }">{{ s.row.eventNew || 0 }}</span>
              · 累计 {{ s.row.eventAccumulated || 0 }}
            </span>
          </template>
        </el-table-column>
        <!-- 换过模型后，要能一眼分清哪几轮是哪个模型跑的，费用和结论才有得比 -->
        <el-table-column label="AI 模型" min-width="150">
          <template slot-scope="s">
            <template v-if="s.row.models">
              <el-tag v-for="m in s.row.models.split(', ')" :key="m" size="mini"
                      effect="plain" class="model-tag">{{ m }}</el-tag>
              <span class="sub">{{ s.row.llmCalls }} 次</span>
              <span v-if="s.row.analyzeFailed" class="sub hot">失败 {{ s.row.analyzeFailed }}</span>
            </template>
            <span v-else class="muted">未调用</span>
          </template>
        </el-table-column>
        <el-table-column label="Token" width="130">
          <template slot-scope="s">
            <span v-if="s.row.llmCalls">
              {{ formatNum(tokens(s.row)) }}
              <span class="sub">¥{{ money(s.row.cost) }}</span>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="推送" width="110">
          <template slot-scope="s">
            <span v-if="s.row.pushSent" class="sent">{{ s.row.pushSent }} 张</span>
            <span v-else class="muted">0</span>
            <span v-if="s.row.pushSkipped" class="sub">未推 {{ s.row.pushSkipped }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="72" align="right">
          <template slot-scope="s">{{ seconds(s.row.elapsedMs) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" background layout="total, sizes, prev, pager, next"
                     :current-page="page" :page-size="size" :total="total"
                     :page-sizes="[20, 50, 100]"
                     @current-change="(p) => { page = p; load(); }"
                     @size-change="(z) => { size = z; page = 1; load(); }" />
    </el-card>

    <!--
      详情用抽屉而不是行内展开：展开会把下面的行顶走，看完一轮要滚回去找下一轮；
      抽屉里直接上一轮 / 下一轮切换，列表位置不动。和事件详情、模版详情的交互也保持一致
    -->
    <el-drawer :visible.sync="drawerVisible" direction="rtl" size="68%" :with-header="false">
      <div class="drawer">
        <div class="drawer-head">
          <div>
            <div class="drawer-title">
              巡检 #{{ activeId }}
              <el-tag v-if="activeRow" size="mini" :type="statusType(activeRow.status)" effect="plain">
                {{ statusText(activeRow.status) }}
              </el-tag>
            </div>
            <div v-if="activeRow" class="drawer-sub">
              {{ formatTime(activeRow.startTime) }}
              <span v-if="activeRow.triggerType === 'MANUAL'">· 手动触发</span>
            </div>
          </div>
          <div class="drawer-nav">
            <el-button size="mini" icon="el-icon-arrow-up" :disabled="!newerId"
                       @click="open({ id: newerId })">下一轮</el-button>
            <el-button size="mini" icon="el-icon-arrow-down" :disabled="!olderId"
                       @click="open({ id: olderId })">上一轮</el-button>
            <el-button size="mini" icon="el-icon-close" circle @click="drawerVisible = false" />
          </div>
        </div>
        <div v-if="!details[activeId]" class="detail-loading">
          <i class="el-icon-loading"></i> 加载中
        </div>
        <run-detail v-else :key="activeId" :detail="details[activeId]"
                    @open-event="(id) => $emit('open-event', id)" />
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { getRuns, getRunDetail, triggerRun } from '../api/inspect';
import { formatTime, formatNum } from '../utils/inspectFormat';
import RunDetail from './InspectRunDetail.vue';

export default {
  name: 'InspectRunPanel',
  components: { RunDetail },
  props: {
    // 采集间隔是动态配置，由看板从接口取到后传进来
    intervalText: { type: String, default: '每' },
  },
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      page: 1,
      size: 20,
      status: '',
      details: {},
      drawerVisible: false,
      activeId: null,
      triggering: false,
      pollTimer: null
    };
  },
  computed: {
    activeIndex() {
      return this.rows.findIndex((r) => r.id === this.activeId);
    },
    activeRow() {
      return this.activeIndex >= 0 ? this.rows[this.activeIndex] : null;
    },
    // 列表按时间倒序：前一行是更新的一轮，后一行是更早的一轮。只在当前页内切换
    newerId() {
      const i = this.activeIndex;
      return i > 0 ? this.rows[i - 1].id : null;
    },
    olderId() {
      const i = this.activeIndex;
      return i >= 0 && i < this.rows.length - 1 ? this.rows[i + 1].id : null;
    }
  },
  mounted() {
    this.load();
  },
  beforeDestroy() {
    this.stopPoll();
  },
  methods: {
    async load() {
      this.loading = true;
      try {
        const params = { page: this.page, size: this.size };
        if (this.status) params.status = this.status;
        const res = await getRuns(params);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          this.rows = d.rows || [];
          this.total = Number(d.total || 0);
          // 列表刷新后，缓存的详情可能过时（最新一轮还在累积分析与推送）
          this.details = {};
          if (this.drawerVisible && this.activeId) this.loadDetail(this.activeId);
        } else {
          this.$message.error((res.data && res.data.msg) || '查询失败');
        }
      } catch (e) {
        this.$message.error('查询失败：' + (e.message || e));
      } finally {
        this.loading = false;
      }
    },

    async trigger() {
      this.triggering = true;
      try {
        const res = await triggerRun();
        if (res.data && res.data.code === '0') {
          this.$message.success('已开始巡检，约 1 分钟完成');
          this.page = 1;
          this.status = '';
          // 后台开始写记录需要一两秒，稍等再刷，之后跟到这一轮结束
          setTimeout(() => this.load(), 1500);
          this.startPoll();
        } else {
          this.$message.warning((res.data && res.data.msg) || '无法开始巡检');
        }
      } catch (e) {
        this.$message.error('触发失败：' + (e.message || e));
      } finally {
        this.triggering = false;
      }
    },

    /** 最新一轮还在进行中就每 5 秒刷一次，最多 3 分钟 */
    startPoll() {
      this.stopPoll();
      const started = Date.now();
      this.pollTimer = setInterval(() => {
        const running = this.rows.length && this.rows[0].status === 'RUNNING';
        if (Date.now() - started > 180000 || (!running && Date.now() - started > 5000)) {
          this.stopPoll();
          this.load();
          return;
        }
        this.load();
      }, 5000);
    },

    stopPoll() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer);
        this.pollTimer = null;
      }
    },

    onFilter() {
      this.page = 1;
      this.load();
    },

    open(row) {
      this.activeId = row.id;
      this.drawerVisible = true;
      // 最新一轮的时间段还没结束，分析与推送还在往里记，每次打开都重新取
      const latest = this.rows.length && this.rows[0].id === row.id;
      if (!this.details[row.id] || latest) this.loadDetail(row.id);
    },

    rowClass({ row }) {
      return this.drawerVisible && row.id === this.activeId ? 'row-active' : '';
    },

    async loadDetail(id) {
      try {
        const res = await getRunDetail(id);
        if (res.data && res.data.code === '0') {
          this.$set(this.details, id, res.data.data);
        } else {
          this.$message.error((res.data && res.data.msg) || '加载详情失败');
        }
      } catch (e) {
        this.$message.error('加载详情失败：' + (e.message || e));
      }
    },

    tokens(row) {
      return Number(row.inputTokens || 0) + Number(row.outputTokens || 0);
    },
    money(v) {
      const n = Number(v || 0);
      return n < 0.01 && n > 0 ? n.toFixed(4) : n.toFixed(2);
    },
    seconds(ms) {
      if (!ms) return '—';
      return (Number(ms) / 1000).toFixed(ms < 10000 ? 1 : 0) + 's';
    },
    statusType(s) {
      return { SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning' }[s] || 'info';
    },
    statusText(s) {
      return { SUCCESS: '成功', FAILED: '失败', RUNNING: '进行中', SKIPPED: '跳过' }[s] || s;
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
.filter-card {
  margin-bottom: 12px;
}

.filter-card >>> .el-form-item {
  margin-bottom: 0;
}

.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-actions {
  flex-shrink: 0;
}

.meta-line {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.table-card >>> .el-table__row {
  cursor: pointer;
}

.sub {
  font-size: 12px;
  color: #909399;
  margin-left: 6px;
}

.model-tag {
  margin-right: 4px;
}

.hot {
  color: #e6564a;
  font-weight: 600;
}

.sent {
  color: #409eff;
  font-weight: 600;
}

.muted {
  color: #c0c4cc;
}

.detail-loading {
  padding: 16px;
  color: #909399;
}

.pager {
  margin-top: 12px;
  text-align: right;
}

.table-card >>> .row-active td {
  background: #ecf5ff !important;
}

.drawer {
  height: 100%;
  overflow-y: auto;
  padding: 18px 22px 40px;
}

.drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 14px;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.drawer-nav {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
