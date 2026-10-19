<template>
  <div class="inspect-dashboard">
    <!--
      只有两个概念：异常事件（要看的）、异常库（不用看的）。
      三张卡片就是这两个概念的三个切面，点哪张就筛哪批。
    -->
    <div class="overview-row">
      <!--
        「今日新增」曾放在这里，已移除：它是时间切片（含已处理），
        和「待处理」的状态切片口径不同，并排摆着会被读成包含关系。
        今天新增了多少条并不驱动任何操作 —— 要看的是还剩多少没处理。
      -->
      <div class="stat-card clickable"
           :class="{ 'stat-active': activeTab === 'events' }"
           @click="switchTab('events')">
        <div class="stat-value">{{ overview.openEvents || 0 }}</div>
        <div class="stat-label">待处理异常</div>
        <div class="stat-hint">未标注业务预期，也未自动收敛</div>
      </div>
      <div class="stat-card clickable"
           :class="{ 'stat-active': activeTab === 'library' }"
           @click="switchTab('library')">
        <div class="stat-value">{{ overview.libraryTotal || 0 }}</div>
        <div class="stat-label">异常模版</div>
        <div class="stat-hint">
          业务预期 {{ overview.libraryExpected || 0 }} 个
          <span v-if="overview.libraryUnreviewed > 0" class="badge-warn">
            系统故障 {{ overview.libraryUnreviewed }} 个
          </span>
        </div>
      </div>
      <!-- 巡检本身的运转：每轮采集到了什么、调没调 AI、推了哪些卡片 -->
      <div class="stat-card clickable"
           :class="{ 'stat-active': activeTab === 'runs' }"
           @click="switchTab('runs')">
        <div class="stat-value stat-text">巡检记录</div>
        <div class="stat-label">{{ intervalText }}一轮</div>
        <div class="stat-hint">采集 → 聚合 → AI 分析 → 推送，每一步的明细</div>
      </div>
    </div>

    <!-- ============ 异常事件 ============ -->
    <template v-if="activeTab === 'events'">
      <el-card shadow="never" class="filter-card">
        <div class="meta-line">
          <span>{{ appOptions.length }} 个服务在采集</span>
          <span class="meta-sep">·</span>
          <span>系统故障每次出现都会爆；业务预期只在突增时爆</span>
          <el-button type="text" size="mini" class="cfg-entry" icon="el-icon-setting"
                     @click="configVisible = true">配置</el-button>
        </div>
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="服务">
            <el-select v-model="filter.appId" clearable filterable placeholder="全部服务"
                       style="width: 260px">
              <el-option v-for="a in appOptions" :key="a" :label="a" :value="a" />
            </el-select>
          </el-form-item>
          <el-form-item label="成因">
            <el-select v-model="filter.eventType" clearable placeholder="全部" style="width: 150px">
              <el-option label="新增（不在异常库）" value="NEW" />
              <el-option label="突增（库内超基线）" value="SURGE" />
            </el-select>
          </el-form-item>
          <el-form-item label="严重度">
            <el-select v-model="filter.severity" clearable placeholder="全部" style="width: 110px">
              <el-option label="P0" value="P0" />
              <el-option label="P1" value="P1" />
              <el-option label="P2" value="P2" />
              <el-option label="P3" value="P3" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="filter.keyword" placeholder="异常内容 / 异常类 / 服务"
                      clearable style="width: 220px" @keyup.enter.native="reload" />
          </el-form-item>
          <!--
            处理状态必须能单独筛「已处理」：原来是「含已处理」复选框，
            勾上得到的是全集，想看已处理的还得自己在里面挑。
          -->
          <el-form-item label="处理状态">
            <el-radio-group v-model="handledFilter" size="small"
                            @change="onHandledFilterChange">
              <el-radio-button :label="0">未处理</el-radio-button>
              <el-radio-button :label="1">已处理</el-radio-button>
              <el-radio-button label="">全部</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
            <el-button icon="el-icon-refresh" @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="table-card">
        <!-- 勾选后才出现，平时不占位置 -->
        <div v-if="selectedEvents.length" class="batch-bar">
          <span class="batch-count">已选 <b>{{ selectedEvents.length }}</b> 条</span>
          <el-button size="mini" type="primary" :loading="batchBusy"
                     @click="promptBatchHandle">批量已处理</el-button>
          <el-button size="mini" :loading="batchBusy"
                     @click="promptBatchEventExpected">批量整类标业务预期</el-button>
          <el-button size="mini" type="text" @click="clearEventSelection">取消选择</el-button>
        </div>
        <el-table ref="eventTable" :data="rows" v-loading="loading" size="small" border
                  row-key="id"
                  :row-class-name="rowClass" @row-click="openDetail"
                  @selection-change="(v) => (selectedEvents = v)">
          <!-- 已处理的不能再勾：批量操作只对待办有意义 -->
          <el-table-column type="selection" width="42" align="center"
                           :selectable="(row) => !row.handled" />
          <el-table-column label="严重度" width="86" align="center">
            <template slot-scope="s">
              <el-tag v-if="s.row.severity" :type="severityTagType(s.row.severity)"
                      size="mini" effect="dark">{{ s.row.severity }}</el-tag>
              <span v-else class="muted">分析中</span>
            </template>
          </el-table-column>
          <el-table-column label="事件类型" width="90" align="center">
            <template slot-scope="s">
              <el-tag :type="s.row.eventType === 'SURGE' ? 'warning' : 'danger'"
                      size="mini" effect="plain">
                {{ s.row.eventType === 'SURGE' ? '突增' : '新增' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="服务" width="210" show-overflow-tooltip>
            <template slot-scope="s">
              <el-tooltip v-if="s.row.appCount > 1" :content="s.row.appList || ''">
                <span class="multi-app">{{ s.row.appCount }} 个服务</span>
              </el-tooltip>
              <span v-else>{{ s.row.appId || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="异常" min-width="300" show-overflow-tooltip>
            <template slot-scope="s">
              <div class="exc-name">{{ s.row.headline }}</div>
              <div class="exc-frame">{{ s.row.excSimple || '—' }}</div>
            </template>
          </el-table-column>
          <!--
            所属模版单开一列：模版是判定的依据（库内且标了业务预期才不报），
            混在「异常」里只是个小徽章，看不出这条事件归到了哪个模版、依据是什么。
          -->
          <el-table-column label="所属模版" min-width="240" show-overflow-tooltip>
            <template slot-scope="s">
              <el-tooltip v-if="s.row.templateId" content="点击查看模版详情：匹配正则、覆盖的服务与代码位置、原始样例"
                          placement="top" :open-delay="400">
                <a class="tpl-link" @click.stop="openTemplateDetail(s.row.templateId)">
                  <span class="tpl-link-text">{{ s.row.templateText || '查看模版' }}</span>
                  <i class="el-icon-top-right tpl-link-icon"></i>
                </a>
              </el-tooltip>
              <el-tooltip v-else content="尚未匹配到模版，下一轮采集会尝试抽取">
                <span class="muted">未归类</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="累计" width="96" align="right">
            <template slot-scope="s">{{ formatNum(s.row.totalCount) }}</template>
          </el-table-column>
          <el-table-column label="最近出现" width="140">
            <template slot-scope="s">{{ formatTime(s.row.lastSeenTime) }}</template>
          </el-table-column>
          <el-table-column label="提醒" width="70" align="center">
            <template slot-scope="s">
              <el-tooltip v-if="s.row.pushCount > 0"
                          :content="'已推送 ' + s.row.pushCount + ' 次，最近 ' + formatTime(s.row.lastPushTime)">
                <span class="push-count">{{ s.row.pushCount }} 次</span>
              </el-tooltip>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>

          <!-- 只剩两个动作：标注（我处理了）、加入异常库（允许发生） -->
          <el-table-column label="操作" width="190" align="center" fixed="right">
            <template slot-scope="s">
              <div @click.stop>
                <template v-if="s.row.handled">
                  <el-tooltip :content="(s.row.handler || '') + '：' + (s.row.handleNote || '')">
                    <span class="handled-tip">已处理</span>
                  </el-tooltip>
                </template>
                <template v-else>
                  <el-button size="mini" type="text" :disabled="rowBusy === s.row.id"
                             @click="promptHandle(s.row)">已处理</el-button>
                  <el-button size="mini" type="text" :disabled="rowBusy === s.row.id"
                             @click="promptMarkExpected(s.row)">整类标业务预期</el-button>
                </template>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination class="pager" background
                       layout="total, sizes, prev, pager, next"
                       :current-page="page" :page-size="size" :total="total"
                       :page-sizes="[20, 50, 100]"
                       @current-change="onPageChange" @size-change="onSizeChange" />
      </el-card>
    </template>

    <!-- ============ 巡检记录 ============ -->
    <inspect-run-panel v-else-if="activeTab === 'runs'" :interval-text="intervalText"
                       @open-event="openEventById" />

    <!-- ============ 异常模版库 ============ -->
    <template v-else>
      <el-card shadow="never" class="filter-card">
        <div class="meta-line">
          <span>模版库收录<b>全部</b>异常模版。标记为「业务预期」的正常抛出不报，
            <b>但突增仍会报</b> —— 标记不等于屏蔽。</span>
          <el-button type="text" size="mini" class="cfg-entry" icon="el-icon-setting"
                     @click="configVisible = true">配置</el-button>
        </div>
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item>
            <!-- 只有两种状态，下拉要点两次才能切；按钮组一次点到位 -->
            <el-radio-group v-model="libFilter.businessExpected" size="small"
                            @change="searchLibrary">
              <el-radio-button label="">全部</el-radio-button>
              <el-radio-button :label="0">系统故障</el-radio-button>
              <el-radio-button :label="1">业务预期</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="libFilter.keyword" placeholder="模版内容 / 原因"
                      clearable style="width: 240px" @keyup.enter.native="searchLibrary" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="searchLibrary">查询</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="table-card">
        <el-alert v-if="overview.libraryUnreviewed > 0" type="warning" :closable="false"
                  show-icon class="lib-alert">
          有 {{ overview.libraryUnreviewed }} 个模版判为<b>系统故障</b>，这些异常<b>每次出现都会报</b>。
          确认某一条其实是业务正常行为，点开它标记为业务预期；拿不准的可以让 AI 先复核一次。
        </el-alert>

        <div v-if="selectedTpls.length" class="batch-bar">
          <span class="batch-count">已选 <b>{{ selectedTpls.length }}</b> 个模版</span>
          <el-button size="mini" type="primary" :loading="batchBusy"
                     @click="promptBatchTplExpected">批量标记业务预期</el-button>
          <el-button size="mini" :loading="batchBusy"
                     @click="confirmBatchUnmark">批量取消标记</el-button>
          <el-button size="mini" class="danger-text" :loading="batchBusy"
                     @click="confirmBatchRemove">批量删除</el-button>
          <el-button size="mini" type="text" @click="clearTplSelection">取消选择</el-button>
        </div>
        <!-- 点行展开详情：模版的正则、样例都在里面 -->
        <el-table ref="tplTable" :data="libRows" v-loading="libLoading" size="small" border
                  row-key="id"
                  @row-click="openTemplate"
                  @selection-change="(v) => (selectedTpls = v)">
          <el-table-column type="selection" width="42" align="center" />
          <el-table-column label="异常模版" min-width="420" show-overflow-tooltip>
            <template slot-scope="s">
              <span class="exc-name">{{ s.row.template || s.row.headline || s.row.matchValue }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120" align="center">
            <template slot-scope="s">
              <el-tag v-if="s.row.businessExpected === 1" type="success" size="mini" effect="plain">
                业务预期
              </el-tag>
              <el-tag v-else type="danger" size="mini" effect="plain">系统故障</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220" show-overflow-tooltip>
            <template slot-scope="s">
              <span v-if="s.row.reason">{{ s.row.reason }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="命中次数" width="100" align="right">
            <template slot-scope="s">{{ formatNum(s.row.hitCount) }}</template>
          </el-table-column>
          <el-table-column label="最近命中" width="140">
            <template slot-scope="s">
              <span v-if="s.row.lastHitTime">{{ formatTime(s.row.lastHitTime) }}</span>
              <span v-else class="muted">从未</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="center" fixed="right">
            <template slot-scope="s">
              <div @click.stop>
                <el-button v-if="s.row.businessExpected !== 1" size="mini" type="text"
                           @click="promptMarkTemplate(s.row)">标记业务预期</el-button>
                <el-button v-else size="mini" type="text"
                           @click="confirmUnmark(s.row)">取消标记</el-button>
                <el-button size="mini" type="text" class="danger-text"
                           @click="confirmRemove(s.row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination class="pager" background
                       layout="total, sizes, prev, pager, next"
                       :current-page="libPage" :page-size="libSize" :total="libTotal"
                       :page-sizes="[20, 50, 100]"
                       @current-change="(p) => { libPage = p; loadLibrary(); }"
                       @size-change="(z) => { libSize = z; libPage = 1; loadLibrary(); }" />
      </el-card>
    </template>

    <inspect-detail-drawer :visible.sync="detailVisible" :event-id="activeEventId"
                           @handled="onHandled" />

    <!--
      模版详情用抽屉而不是表格内展开：
      el-table 的 expand 列一旦设了固定宽度，展开行的布局会算错 ——
      实测内容被压进 40px 的列里、竖排成一列字符。抽屉不受表格布局约束，
      也和事件列表的交互保持一致
    -->
    <el-drawer :visible.sync="tplVisible" direction="rtl" size="52%" :with-header="false">
      <div v-if="activeTpl" class="tpl-drawer">
        <div class="tpl-head">
          <div>
            <div class="tpl-title">{{ activeTpl.template || activeTpl.matchValue }}</div>
            <div class="tpl-sub">
              <el-tag v-if="activeTpl.businessExpected === 1" type="success"
                      size="mini" effect="plain">业务预期</el-tag>
              <el-tag v-else type="danger" size="mini" effect="plain">系统故障</el-tag>
              <span class="tpl-sub-text">
                命中 {{ formatNum(activeTpl.hitCount) }} 次 ·
                {{ activeTpl.businessExpected === 1
                    ? '正常抛出不报，突增仍会报' : '每次出现都会报，需要人工分析' }}
              </span>
            </div>
          </div>
          <div class="tpl-head-actions">
            <!--
              复核入口放在这里而不是列表上方：人是看着某一条拿不准时才想听 AI 的判断，
              一次把几十条丢给模型既慢又刷屏，初始化才需要那种批量口径。
            -->
            <el-tooltip content="让 AI 判断这条是不是业务预期。判成业务预期且有把握时会自动标记，判成故障只写进说明、不动标记"
                        placement="bottom">
              <el-button size="mini" icon="el-icon-magic-stick"
                         :loading="reviewingId === activeTpl.id"
                         @click="reviewOne(activeTpl)">AI 复核这一条</el-button>
            </el-tooltip>
            <el-button size="mini" icon="el-icon-close" circle @click="tplVisible = false" />
          </div>
        </div>

        <el-descriptions :column="1" size="small" border class="tpl-desc">
          <el-descriptions-item label="匹配正则">
            <div class="tpl-code-row">
              <code class="tpl-code">{{ activeTpl.pattern || '（无，按 logger / 异常类匹配）' }}</code>
              <el-button type="text" size="mini" @click="promptEditPattern(activeTpl)">修正</el-button>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="说明">
            {{ activeTpl.reason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="首次出现">
            {{ formatTime(activeTpl.firstSeenTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="最近命中">
            {{ activeTpl.lastHitTime ? formatTime(activeTpl.lastHitTime) : '从未' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="activeTpl.matchType !== 'TEMPLATE'" label="匹配方式">
            {{ matchDesc(activeTpl) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="tpl-section-title">
          覆盖范围
          <span v-if="tplSources.length" class="tpl-scope">
            {{ tplMeta.appCount || 0 }} 个服务 · {{ tplMeta.fpCount || 0 }} 个来源 ·
            累计 {{ formatNum(tplMeta.totalCount) }} 次
          </span>
        </div>

        <!-- 模版是跨服务聚合的，一条样例说明不了它实际管着多大范围 -->
        <div v-if="tplSources.length" class="src-list">
          <div v-for="(src, i) in tplSources" :key="i" class="src-item">
            <div class="src-head">
              <span class="src-app">{{ src.appId || '未知服务' }}</span>
              <span v-if="src.bizFrame" class="src-frame">{{ src.bizFrame }}</span>
              <span v-else-if="src.loggerClass" class="src-frame muted">
                {{ src.loggerClass }}（打印位置，非抛出点）
              </span>
              <span class="src-count">{{ formatNum(src.totalCount) }} 次</span>
            </div>
            <div v-if="src.traceId || src.podName" class="src-meta">
              <span v-if="src.traceId">traceId: {{ src.traceId }}</span>
              <span v-if="src.podName">pod: {{ src.podName }}</span>
            </div>
            <pre v-if="src.rawMessage" class="tpl-sample">{{ src.rawMessage }}</pre>
          </div>
        </div>
        <div v-else>
          <pre class="tpl-sample">{{ activeTpl.sampleMessage || '（无）' }}</pre>
          <div class="src-empty">
            这个模版还没有匹配到任何指纹 —— 可能是刚建好、还没采集过
          </div>
        </div>

        <div class="tpl-actions">
          <el-button v-if="activeTpl.businessExpected !== 1" type="primary" size="small"
                     @click="promptMarkTemplate(activeTpl)">标记业务预期</el-button>
          <el-button v-else size="small" @click="confirmUnmark(activeTpl)">取消标记</el-button>
          <el-button size="small" class="danger-text" @click="confirmRemove(activeTpl)">删除模版</el-button>
        </div>
      </div>
    </el-drawer>
    <inspect-config-panel :visible.sync="configVisible" />
  </div>
</template>

<script>
import {
  getEventOverview, getEvents, getEventAppOptions,
  handleEvent, markEventExpected,
  getLibrary, removeFromLibrary, markTemplateExpected,
  unmarkTemplateExpected, updateTemplatePattern,
  reviewTemplate, getTemplateDetail,
  batchHandleEvents, batchMarkEventsExpected,
  batchMarkTemplatesExpected, batchUnmarkTemplates, batchRemoveTemplates
} from '../api/inspect';
import { formatTime, formatNum, SEVERITY_TAG } from '../utils/inspectFormat';
import InspectDetailDrawer from './InspectDetailDrawer.vue';
import InspectConfigPanel from './InspectConfigPanel.vue';
import InspectRunPanel from './InspectRunPanel.vue';

export default {
  name: 'InspectDashboard',
  components: { InspectDetailDrawer, InspectConfigPanel, InspectRunPanel },
  computed: {
    // 采集间隔在「配置」里可改，这里跟着接口返回的实际值走
    intervalText() {
      const sec = Number(this.overview.scanIntervalSeconds);
      if (!sec) {
        return '每';
      }
      return sec % 60 === 0 ? `每 ${sec / 60} 分钟` : `每 ${sec} 秒`;
    },
  },
  data() {
    return {
      activeTab: 'events',
      loading: false,
      overview: {},
      rows: [],
      total: 0,
      page: 1,
      size: 20,
      filter: this.emptyFilter(),
      handledFilter: 0,
      appOptions: [],
      rowBusy: null,
      detailVisible: false,
      activeEventId: null,
      configVisible: false,
      libLoading: false,
      libRows: [],
      libPage: 1,
      libSize: 20,
      libTotal: 0,
      tplVisible: false,
      activeTpl: null,
      tplSources: [],
      tplMeta: {},
      reviewingId: null,
      libFilter: { businessExpected: '', keyword: '' },
      selectedEvents: [],
      selectedTpls: [],
      batchBusy: false,
      refreshTimer: null
    };
  },
  mounted() {
    // 飞书卡片跳转过来时带 eventId，直接打开那条详情
    const eventId = this.readEventIdFromUrl();
    if (eventId) {
      this.activeEventId = eventId;
      this.detailVisible = true;
    } else {
      // 没有 eventId 时，卡片会退回「筛选到这批异常」的形式：
      // fpId（还没产生事件）或 keyword（系统性故障没有详情页）。
      // 展不开详情，但至少要把列表筛到相关那几条，而不是把人丢在首页。
      const fpId = this.readParamFromUrl('fpId');
      const keyword = this.readTextParamFromUrl('keyword');
      if (fpId || keyword) {
        this.filter.keyword = keyword || String(fpId);
        this.handledFilter = '';
      }
    }
    this.reload();
    this.loadOverview();
    this.loadApps();
    // 这个页面是用来「第一时间发现异常」的，停在那儿不刷新就失去意义
    this.refreshTimer = setInterval(this.loadOverview, 60000);
  },
  beforeDestroy() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  },
  methods: {
    emptyFilter() {
      return { appId: '', eventType: '', severity: '', keyword: '', sinceDays: null };
    },

    /**
     * 从 URL 取 eventId。
     * 卡片链接可能把参数放在 hash 或 search 里，两边都看。
     */
    readEventIdFromUrl() {
      return this.readParamFromUrl('eventId');
    },

    /**
     * 从 URL 取数字参数。
     *
     * hash 和 search 都要看：卡片拼的是 /#/?module=xxx&eventId=1，
     * 参数落在 hash 里；而手工粘贴的链接可能带在 search 上。
     */
    /** 从 URL 取文本参数（需要解码，可能是中文异常原因） */
    /** HTML 转义。凡是把日志内容塞进 HTML 字符串的地方都要过一遍 */
    escapeHtml(v) {
      return String(v == null ? '' : v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    },

    readTextParamFromUrl(name) {
      const re = new RegExp('[?&]' + name + '=([^&]+)');
      const pick = (qs) => {
        if (!qs) return null;
        const m = qs.match(re);
        if (!m) return null;
        try {
          return decodeURIComponent(m[1]);
        } catch (e) {
          // 编码坏了不该让整个页面打不开
          return m[1];
        }
      };
      return pick(window.location.hash) || pick(window.location.search);
    },

    readParamFromUrl(name) {
      const re = new RegExp('[?&]' + name + '=(\\d+)');
      const pick = (qs) => {
        if (!qs) return null;
        const m = qs.match(re);
        return m ? Number(m[1]) : null;
      };
      return pick(window.location.hash) || pick(window.location.search);
    },

    switchTab(tab) {
      this.activeTab = tab;
      // 两张表是 v-if 切换的，表销毁了勾选状态还留着，按钮条会凭空挂着
      this.selectedEvents = [];
      this.selectedTpls = [];
      if (tab === 'library') {
        this.loadLibrary();
        return;
      }
      if (tab === 'runs') {
        // 巡检记录由自己的组件加载
        return;
      }
      this.filter = this.emptyFilter();
      this.handledFilter = 0;
      this.page = 1;
      this.reload();
    },

    async loadApps() {
      try {
        const res = await getEventAppOptions();
        if (res.data && res.data.code === '0') {
          this.appOptions = res.data.data || [];
        }
      } catch (e) {
        console.warn('加载服务列表失败', e);
      }
    },

    async loadOverview() {
      try {
        const res = await getEventOverview();
        if (res.data && res.data.code === '0') {
          this.overview = res.data.data || {};
        }
      } catch (e) {
        // 概览失败不该打断列表，静默即可
        console.warn('加载概览失败', e);
      }
    },

    async reload() {
      this.loading = true;
      try {
        const params = { page: this.page, size: this.size };
        if (this.filter.appId) params.appId = this.filter.appId;
        if (this.filter.eventType) params.eventType = this.filter.eventType;
        if (this.filter.severity) params.severity = this.filter.severity;
        if (this.filter.keyword) params.keyword = this.filter.keyword;
        if (this.filter.sinceDays) params.sinceDays = this.filter.sinceDays;
        if (this.handledFilter !== '') params.handled = this.handledFilter;

        const res = await getEvents(params);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          this.rows = d.rows || [];
          this.total = Number(d.total || 0);
        } else {
          this.$message.error((res.data && res.data.msg) || '查询失败');
        }
      } catch (e) {
        this.$message.error('查询失败：' + (e.message || e));
      } finally {
        this.loading = false;
      }
    },

    async loadLibrary() {
      this.libLoading = true;
      try {
        const params = { page: this.libPage, size: this.libSize };
        if (this.libFilter.businessExpected !== '' && this.libFilter.businessExpected !== null) {
          params.businessExpected = this.libFilter.businessExpected;
        }
        if (this.libFilter.keyword) params.keyword = this.libFilter.keyword;
        const res = await getLibrary(params);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          this.libRows = d.rows || [];
          this.libTotal = Number(d.total || 0);
          // 批量操作删掉了最后一页的全部内容时，退回上一页而不是停在空页
          if (!this.libRows.length && this.libPage > 1 && this.libTotal > 0) {
            this.libPage = Math.ceil(this.libTotal / this.libSize);
            this.loadLibrary();
            return;
          }
        } else {
          this.$message.error((res.data && res.data.msg) || '查询失败');
        }
      } catch (e) {
        this.$message.error('查询失败：' + (e.message || e));
      } finally {
        this.libLoading = false;
      }
    },

    /** 改筛选条件要回到第一页，否则可能停在一个超出新结果范围的页码上 */
    searchLibrary() {
      this.libPage = 1;
      this.loadLibrary();
    },

    onHandledFilterChange() {
      this.page = 1;
      this.reload();
    },

    resetFilter() {
      this.filter = this.emptyFilter();
      this.handledFilter = 0;
      this.page = 1;
      this.reload();
    },

    onPageChange(p) {
      this.page = p;
      this.reload();
    },

    onSizeChange(s) {
      this.size = s;
      this.page = 1;
      this.reload();
    },

    openDetail(row, column) {
      // 点勾选框那一格不该顺带打开详情
      if (column && column.type === 'selection') return;
      this.activeEventId = Number(row.id);
      this.detailVisible = true;
    },

    /** 巡检记录里点事件：直接打开事件详情，不切走当前页签 */
    openEventById(eventId) {
      if (!eventId) return;
      this.activeEventId = Number(eventId);
      this.detailVisible = true;
    },

    onHandled() {
      this.reload();
      this.loadOverview();
    },

    /**
     * 已处理 = 这条我看过了，不用再提醒我。
     *
     * 操作人由后端从登录态取，前端不传 —— 这是要追责到人的记录，
     * 让调用方自报姓名等于谁都能冒充。
     */
    promptHandle(row) {
      this.$prompt('简单说明处理情况（会记录到这条事件上，并停止升级重推）', '标记为已处理', {
        inputPlaceholder: '例如：已联系运维重启 Redis，10:40 恢复',
        inputValidator: (v) => (v && v.trim() ? true : '请填写处理说明')
      }).then(({ value }) => this.doHandle(row, value.trim())).catch(() => {});
    },

    async doHandle(row, note) {
      this.rowBusy = row.id;
      try {
        const res = await handleEvent(row.id, note);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          this.$message.success('已标记为已处理' + (d.cardSynced ? '，群里卡片已同步' : ''));
          this.reload();
          this.loadOverview();
        } else {
          this.$message.error((res.data && res.data.msg) || '操作失败');
        }
      } catch (e) {
        this.$message.error('操作失败：' + (e.message || e));
      } finally {
        this.rowBusy = null;
      }
    },

    /**
     * 整类标业务预期。
     *
     * 这个按钮和旁边的「已处理」看着像一对，实际作用范围差一个量级：
     *   已处理        → 只这一条事件
     *   整类标业务预期 → 这条事件所属的【整个模版】，永久生效
     * 所以弹框里必须先把影响面摊开（模版原文 + 覆盖多少服务/来源/次数），
     * 否则用户是在不知道自己签了什么的情况下点确认。
     */
    async promptMarkExpected(row) {
      if (!row.templateId) {
        this.$message.warning('这条事件还没有关联模版，无法整类标记');
        return;
      }
      // 先取覆盖范围 —— 让人看清这一下会影响多少东西
      let scope = '';
      try {
        const res = await getTemplateDetail(row.templateId);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          scope = `涉及 ${d.appCount || 0} 个服务 · ${d.fpCount || 0} 个来源 · 累计 ${d.totalCount || 0} 次`;
        }
      } catch (e) {
        // 取不到覆盖范围不该挡住操作，但要说明看不到影响面
        scope = '（覆盖范围暂时取不到）';
      }

      // 模版文本来自生产日志，可能含 < > &，必须转义后再当 HTML 渲染
      const tpl = this.escapeHtml(row.templateText || row.headline || '');
      this.$prompt(
        '<div style="margin-bottom:8px">作用于<b>整个模版</b>，不只是这一条事件：</div>'
          + `<div style="padding:6px 8px;background:#f5f7fa;border-radius:3px;`
          + `font-family:monospace;font-size:12px;word-break:break-all">${tpl}</div>`
          + `<div style="margin:6px 0 10px;color:#909399;font-size:12px">${scope}</div>`
          + '<div>标记后这类异常正常抛出不再报，突增时仍会报。请说明为什么它属于业务预期：</div>',
        '整类标记为业务预期',
        {
          dangerouslyUseHTMLString: true,
          inputPlaceholder: '例如：服务停止时间内的正常业务拒绝，不是系统故障',
          inputValidator: (v) => (v && v.trim() ? true : '请填写原因，否则以后没人知道为什么放行')
        }
      ).then(({ value }) => this.doMarkExpected(row, value.trim())).catch(() => {});
    },

    async doMarkExpected(row, reason) {
      this.rowBusy = row.id;
      try {
        const res = await markEventExpected(row.id, reason);
        if (res.data && res.data.code === '0') {
          this.$message.success('该模版已整类标为业务预期，相关未处理事件一并关闭');
          this.reload();
          this.loadOverview();
        } else {
          this.$message.error((res.data && res.data.msg) || '操作失败');
        }
      } catch (e) {
        this.$message.error('操作失败：' + (e.message || e));
      } finally {
        this.rowBusy = null;
      }
    },

    /** 点整行打开模版详情，并拉取它覆盖的服务与代码位置 */
    async openTemplate(row, column) {
      if (column && column.type === 'selection') return;
      this.activeTpl = row;
      this.tplSources = [];
      this.tplMeta = {};
      this.tplVisible = true;
      await this.loadTemplateSources(row.id);
    },

    async loadTemplateSources(id) {
      try {
        const res = await getTemplateDetail(id);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          if (d.template) this.activeTpl = d.template;
          this.tplSources = d.sources || [];
          this.tplMeta = {
            appCount: d.appCount, fpCount: d.fpCount, totalCount: d.totalCount
          };
        }
      } catch (e) {
        // 覆盖范围取不到不该挡住详情本身
        console.warn('加载模版来源失败', e);
      }
    },

    /**
     * 下钻到模版详情。
     *
     * 事件是按模版聚合的，「为什么这几个服务的异常被合成一条」「为什么它还在报」
     * 都只有看到模版才解释得清 —— 所以模版在列表里要能点。
     */
    async openTemplateDetail(templateId) {
      if (!templateId) return;
      this.tplSources = [];
      this.tplMeta = {};
      this.activeTpl = {};
      this.tplVisible = true;
      await this.loadTemplateSources(templateId);
    },

    /**
     * 让 AI 复核这一条模版。
     *
     * 单向标记：判成「业务预期 + 有把握」才自动落标记，判成故障只把结论写进说明。
     * 这个不对称是有意的 —— 错标成业务预期会让真故障永久静默，漏标只是多报一条。
     */
    async reviewOne(tpl) {
      if (!tpl || !tpl.id) return;
      this.reviewingId = tpl.id;
      try {
        const res = await reviewTemplate(tpl.id);
        if (res.data && res.data.code === '0') {
          const d = res.data.data || {};
          if (d.message) {
            this.$message.warning(d.message);
          } else if (d.autoMarked > 0) {
            this.$message.success('AI 判定为业务预期，已自动标记');
          } else if (d.keptAsFault > 0) {
            this.$message.info('AI 判定疑似故障，保持照报（结论已写进说明）');
          } else {
            this.$message.info('AI 没把握，结论已写进说明，需要你确认');
          }
          // 标记和说明都可能变了，详情与列表都要刷新
          await this.loadTemplateSources(tpl.id);
          this.loadLibrary();
          this.loadOverview();
        } else {
          this.$message.error((res.data && res.data.msg) || '复核失败');
        }
      } catch (e) {
        this.$message.error('复核失败：' + (e.message || e));
      } finally {
        this.reviewingId = null;
      }
    },

    promptMarkTemplate(row) {
      this.$prompt(
        '标记后这个模版的异常正常抛出不再报，突增时仍会报。请说明原因：',
        '标记业务预期',
        {
          inputValue: row.reason && row.reason.indexOf('AI 建议') === 0 ? '' : (row.reason || ''),
          inputPlaceholder: '例如：服务停止时间内的正常业务拒绝，不是系统故障',
          inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
        }
      ).then(async ({ value }) => {
        try {
          await markTemplateExpected(row.id, value.trim());
          this.$message.success('该模版已整类标为业务预期，相关未处理事件一并关闭');
          this.tplVisible = false;
          this.loadLibrary();
          this.loadOverview();
        } catch (e) {
          this.$message.error('操作失败：' + (e.message || e));
        }
      }).catch(() => {});
    },

    confirmUnmark(row) {
      this.$confirm(
        '取消后这个模版的异常每次出现都会重新产生事件并推送。确定吗？',
        '取消业务预期', { type: 'warning' }
      ).then(async () => {
        try {
          await unmarkTemplateExpected(row.id);
          this.$message.success('已取消');
          this.tplVisible = false;
          this.loadLibrary();
          this.loadOverview();
        } catch (e) {
          this.$message.error('操作失败：' + (e.message || e));
        }
      }).catch(() => {});
    },

    /**
     * 修正模版正则。
     * 模型生成的正则可能写宽了（把不同异常吞进来）或写窄了（永远命中不了），
     * 后端会校验「能编译」且「能匹配自身样例」。
     */
    promptEditPattern(row) {
      this.$prompt('修改匹配正则（后端会校验它能否匹配该模版的原始样例）', '修正模版', {
        inputValue: row.pattern || '',
        inputValidator: (v) => (v && v.trim() ? true : '正则不能为空')
      }).then(async ({ value }) => {
        try {
          const res = await updateTemplatePattern(
            row.id, row.template || row.matchValue, value.trim());
          if (res.data && res.data.code === '0') {
            this.$message.success('已更新');
            this.tplVisible = false;
            this.loadLibrary();
          } else {
            this.$message.error((res.data && res.data.msg) || '更新失败');
          }
        } catch (e) {
          this.$message.error('更新失败：' + (e.message || e));
        }
      }).catch(() => {});
    },

    confirmRemove(row) {
      this.$confirm(
        '删除后这个异常会被当成没见过，下轮采集时重新抽取模版。确定吗？',
        '删除模版', { type: 'warning' }
      ).then(async () => {
        try {
          await removeFromLibrary(row.id);
          this.$message.success('已删除');
          this.tplVisible = false;
          this.loadLibrary();
          this.loadOverview();
        } catch (e) {
          this.$message.error('删除失败：' + (e.message || e));
        }
      }).catch(() => {});
    },

    // ---------------- 批量操作 ----------------

    clearEventSelection() {
      if (this.$refs.eventTable) this.$refs.eventTable.clearSelection();
    },

    clearTplSelection() {
      if (this.$refs.tplTable) this.$refs.tplTable.clearSelection();
    },

    /** 统一处理批量请求：成功回调给出提示文案，失败统一报错 */
    async runBatch(request, onOk) {
      this.batchBusy = true;
      try {
        const res = await request();
        if (res.data && res.data.code === '0') {
          onOk(res.data.data);
        } else {
          this.$message.error((res.data && res.data.msg) || '操作失败');
        }
      } catch (e) {
        this.$message.error('操作失败：' + (e.message || e));
      } finally {
        this.batchBusy = false;
      }
    },

    /**
     * 被保护的技术故障要让人看见 —— 静默跳过的话，
     * 用户会以为全标上了，而空指针 / 超时其实还在照报。
     */
    showProtected(d) {
      if (!d || !d.protectedCount) return;
      const list = (d.protected || []).slice(0, 10)
        .map((t) => '<li style="margin:2px 0;word-break:break-all">' + this.escapeHtml(t) + '</li>')
        .join('');
      const more = d.protectedCount > 10 ? `<div>…等 ${d.protectedCount} 个</div>` : '';
      this.$alert(
        `<div style="margin-bottom:6px">以下 <b>${d.protectedCount}</b> 个是技术故障（空指针 / SQL 异常 / 超时等），`
          + '不随批量放行，仍会照报。确认要标的话请点开单条标记：</div>'
          + `<ul style="padding-left:18px;font-size:12px;max-height:260px;overflow:auto">${list}</ul>${more}`,
        '部分未标记', { dangerouslyUseHTMLString: true, type: 'warning' }
      ).catch(() => {});
    },

    promptBatchHandle() {
      const ids = this.selectedEvents.map((r) => r.id);
      this.$prompt(`将 ${ids.length} 条事件标为已处理，说明会记录到每一条上`, '批量标记已处理', {
        inputPlaceholder: '例如：v1772 已修复上线',
        inputValidator: (v) => (v && v.trim() ? true : '请填写处理说明')
      }).then(({ value }) => this.runBatch(
        () => batchHandleEvents(ids, value.trim()),
        (d = {}) => {
          let msg = `已处理 ${d.handled || 0} 条`;
          if (d.skipped) msg += `，跳过 ${d.skipped} 条（已处理或不存在）`;
          if (d.cardSynced) msg += `，群卡片同步 ${d.cardSynced} 张`;
          this.$message.success(msg);
          this.clearEventSelection();
          this.reload();
          this.loadOverview();
        }
      )).catch(() => {});
    },

    /**
     * 批量整类标业务预期。作用于勾选事件所属的【模版】，多条同模版只算一个，
     * 所以弹框里要说清楚实际会动多少个模版。
     */
    promptBatchEventExpected() {
      const ids = this.selectedEvents.map((r) => r.id);
      const tplCount = new Set(this.selectedEvents.filter((r) => r.templateId)
        .map((r) => r.templateId)).size;
      const noTpl = this.selectedEvents.filter((r) => !r.templateId).length;
      this.$prompt(
        `<div>作用于这 ${ids.length} 条事件所属的 <b>${tplCount} 个模版</b>，不只是这些事件，永久生效。</div>`
          + (noTpl ? `<div style="color:#e6a23c;margin-top:4px">${noTpl} 条还没有模版，会被跳过</div>` : '')
          + '<div style="margin-top:6px;color:#909399;font-size:12px">'
          + '空指针 / SQL 异常 / 超时等技术故障不随批量放行。</div>'
          + '<div style="margin-top:8px">请说明为什么它们属于业务预期：</div>',
        '批量整类标业务预期',
        {
          dangerouslyUseHTMLString: true,
          inputPlaceholder: '例如：服务停止时间内的正常业务拒绝，不是系统故障',
          inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
        }
      ).then(({ value }) => this.runBatch(
        () => batchMarkEventsExpected(ids, value.trim()),
        (d = {}) => {
          this.$message.success(`已标记 ${d.marked || 0} 个模版，相关未处理事件一并关闭`);
          this.showProtected(d);
          this.clearEventSelection();
          this.reload();
          this.loadOverview();
        }
      )).catch(() => {});
    },

    promptBatchTplExpected() {
      const ids = this.selectedTpls.map((r) => r.id);
      this.$prompt(
        `将 ${ids.length} 个模版标为业务预期：正常抛出不再报，突增仍会报。`
          + '空指针 / SQL 异常 / 超时等技术故障不随批量放行。请说明原因：',
        '批量标记业务预期',
        {
          inputPlaceholder: '例如：服务停止时间内的正常业务拒绝，不是系统故障',
          inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
        }
      ).then(({ value }) => this.runBatch(
        () => batchMarkTemplatesExpected(ids, value.trim()),
        (d = {}) => {
          let msg = `已标记 ${d.marked || 0} 个`;
          if (d.already) msg += `，${d.already} 个本来就是业务预期`;
          this.$message.success(msg);
          this.showProtected(d);
          this.afterTplBatch();
        }
      )).catch(() => {});
    },

    confirmBatchUnmark() {
      const ids = this.selectedTpls.map((r) => r.id);
      this.$confirm(
        `取消后这 ${ids.length} 个模版的异常每次出现都会重新产生事件并推送。确定吗？`,
        '批量取消业务预期', { type: 'warning' }
      ).then(() => this.runBatch(
        () => batchUnmarkTemplates(ids),
        (n) => {
          this.$message.success(`已取消 ${n || 0} 个`);
          this.afterTplBatch();
        }
      )).catch(() => {});
    },

    confirmBatchRemove() {
      const ids = this.selectedTpls.map((r) => r.id);
      this.$confirm(
        `删除后这 ${ids.length} 个模版对应的异常会被当成没见过，下轮采集时重新抽取。确定吗？`,
        '批量删除模版', { type: 'warning', confirmButtonText: '删除' }
      ).then(() => this.runBatch(
        () => batchRemoveTemplates(ids),
        (n) => {
          this.$message.success(`已删除 ${n || 0} 个`);
          this.afterTplBatch();
        }
      )).catch(() => {});
    },

    afterTplBatch() {
      this.clearTplSelection();
      this.loadLibrary();
      this.loadOverview();
    },

    matchDesc(row) {
      if (row.matchType === 'TEMPLATE') {
        return row.pattern ? '正则：' + row.pattern : '模版';
      }
      const typeText = {
        LOGGER: '按 logger',
        EXCEPTION: '按异常类',
        APP: '按服务'
      }[row.matchType] || row.matchType;
      return typeText + '：' + (row.matchValue || '');
    },

    rowClass({ row }) {
      if (row.handled) return 'row-handled';
      if (row.severity === 'P0') return 'row-p0';
      if (row.severity === 'P1') return 'row-p1';
      return '';
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
.inspect-dashboard {
  padding: 4px;
}

.overview-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

/* 三张卡片等宽：flex-basis 归零，不让内容长度决定宽度 */
.stat-card {
  flex: 1 1 0;
  min-width: 0;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 14px 16px;
}

.stat-card.clickable {
  cursor: pointer;
  transition: border-color .15s, box-shadow .15s;
}

.stat-card.clickable:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, .15);
}

/*
  选中态是卡片上唯一的着色来源。
  之前「有待处理事件」也给整张卡片上红底，和选中态撞在一起，
  看上去像两张卡片同时被选中；而且待处理本来就是常态，
  用最重的视觉去表达它，真出 P0 时反而没有更强的手段可用了
*/
.stat-card.stat-active {
  border-color: #409eff;
  background: #f5f9ff;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  color: #303133;
}

.stat-value.stat-text {
  font-size: 20px;
  line-height: 1.56;
}

.stat-value.stat-danger {
  color: #e6564a;
}

/* 分母用小字弱化：主角是「待处理」那个数 */
.stat-sub {
  font-size: 15px;
  font-weight: normal;
  color: #c0c4cc;
}

.stat-label {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}

.stat-hint {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 2px;
}

.badge-warn {
  color: #e6a23c;
  margin-left: 4px;
}

.filter-card {
  margin-bottom: 12px;
}

.filter-card >>> .el-form-item {
  margin-bottom: 0;
}

.cfg-entry {
  float: right;
  padding: 0;
}

.table-card >>> .el-table__row {
  cursor: pointer;
}

.table-card >>> .row-p0 td {
  background: #fef0f0 !important;
}

.table-card >>> .row-p1 td {
  background: #fdf6ec !important;
}

.table-card >>> .row-handled {
  opacity: 0.55;
}

.lib-alert {
  margin-bottom: 12px;
}

.tpl-drawer {
  padding: 20px 24px 40px;
  height: 100%;
  overflow-y: auto;
}

.tpl-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.tpl-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  line-height: 1.6;
  word-break: break-all;
}

.tpl-sub {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tpl-sub-text {
  font-size: 12px;
  color: #909399;
}

.tpl-desc {
  margin-bottom: 16px;
}

.tpl-code-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.tpl-code {
  flex: 1;
  min-width: 0;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 3px;
  color: #e6564a;
  word-break: break-all;
  line-height: 1.7;
}

.tpl-section-title {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
}

.tpl-scope {
  font-weight: normal;
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.src-list {
  margin-bottom: 20px;
}

.src-item {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.src-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.src-app {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.src-frame {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  color: #409eff;
}

.src-count {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.src-meta {
  font-size: 11px;
  color: #c0c4cc;
  display: flex;
  gap: 12px;
  margin-bottom: 6px;
  word-break: break-all;
}

.src-empty {
  font-size: 12px;
  color: #c0c4cc;
  margin-bottom: 20px;
}

.tpl-sample {
  margin: 0 0 20px;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  line-height: 1.7;
  color: #606266;
}

.tpl-actions {
  display: flex;
  gap: 10px;
}

.muted {
  color: #c0c4cc;
}

.meta-line {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  overflow: hidden;
}

.meta-sep {
  margin: 0 6px;
}

.danger-text {
  color: #f56c6c;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 10px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 4px;
}

.batch-count {
  font-size: 13px;
  color: #606266;
  margin-right: 4px;
}

.pager {
  margin-top: 12px;
  text-align: right;
}

/*
  可下钻的列必须自己说出来 —— 纯黑文字没人知道能点。
  这条样式之前漏掉了（插入时锚定在一条不存在的规则上，静默失败），
  表现就是「所属模版」看着像普通文本。
*/
.tpl-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  max-width: 100%;
  color: #409eff;
  cursor: pointer;
  font-size: 13px;
  text-decoration: underline;
  text-decoration-color: rgba(64, 158, 255, 0.4);
  text-underline-offset: 2px;
}

.tpl-link:hover {
  color: #66b1ff;
  text-decoration-color: #66b1ff;
}

.tpl-link-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-link-icon {
  font-size: 11px;
  opacity: 0.65;
  flex-shrink: 0;
}

.tpl-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
