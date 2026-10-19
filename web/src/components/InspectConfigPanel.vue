<template>
  <el-drawer :visible.sync="innerVisible" direction="rtl" size="62%"
             :with-header="false" :before-close="close">
    <div class="cfg-wrap" v-loading="loading">
      <!-- 标题栏 -->
      <div class="cfg-head">
        <div>
          <div class="cfg-title">巡检配置</div>
          <div class="cfg-sub">
            改完立即生效，不需要发版
            <span v-if="modifiedTotal > 0" class="mod-count">
              · {{ modifiedTotal }} 项已改过默认值
            </span>
          </div>
        </div>
        <el-button size="mini" icon="el-icon-close" circle @click="close()" />
      </div>

      <div class="cfg-body">
        <!--
          左侧导航取代折叠面板：折叠的问题是收起来之后，
          人不知道里面还有什么，只能一个个点开找
        -->
        <div class="cfg-nav">
          <div v-for="g in visibleGroups" :key="g.key"
               class="nav-item" :class="{ active: activeGroup === g.key }"
               @click="activeGroup = g.key">
            <i :class="g.icon"></i>
            <span class="nav-label">{{ g.label }}</span>
            <span v-if="modifiedIn(g.key) > 0" class="nav-dot"></span>
          </div>
        </div>

        <div class="cfg-main">
          <div v-for="item in currentItems" :key="item.cfgKey" class="item">
            <div class="item-left">
              <div class="item-label">
                {{ labelOf(item) }}
                <el-tooltip placement="top" :open-delay="200">
                  <div slot="content" class="tip">
                    <div class="tip-key">{{ item.cfgKey }}</div>
                    <div class="tip-desc">{{ item.description }}</div>
                    <div class="tip-default">默认值：{{ item.defaultValue || '（空）' }}</div>
                  </div>
                  <i class="el-icon-question tip-icon"></i>
                </el-tooltip>
                <span v-if="item.modified" class="tag-mod">已改</span>
              </div>
              <div v-if="hintOf(item)" class="item-hint">{{ hintOf(item) }}</div>
            </div>

            <div class="item-right">
              <!-- 开关 -->
              <el-switch v-if="item.valueType === 'BOOL'"
                         :value="item.cfgValue === 'true'"
                         @change="(v) => save(item, String(v))" />

              <!-- 枚举 -->
              <el-select v-else-if="metaOf(item).options" v-model="item.cfgValue"
                         size="small" style="width: 110px"
                         @change="save(item, item.cfgValue)">
                <el-option v-for="o in metaOf(item).options" :key="o" :label="o" :value="o" />
              </el-select>

              <!-- 长文本：映射表这类塞不进单行输入框 -->
              <el-input v-else-if="metaOf(item).long" v-model="item.cfgValue"
                        type="textarea" :rows="4" size="small"
                        @blur="save(item, item.cfgValue)" />

              <!-- 普通值 -->
              <div v-else class="value-row">
                <el-input v-model="item.cfgValue" size="small"
                          :style="{ width: metaOf(item).unit ? '120px' : '200px' }"
                          @blur="save(item, item.cfgValue)">
                  <template v-if="metaOf(item).unit" slot="append">
                    {{ metaOf(item).unit }}
                  </template>
                </el-input>
                <span v-if="humanized(item)" class="human">{{ humanized(item) }}</span>
              </div>

              <el-button v-if="item.modified" type="text" size="mini"
                         class="reset" @click="reset(item)">恢复默认</el-button>
            </div>
          </div>

          <div v-if="!currentItems.length" class="empty">这一组暂无配置项</div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script>
import { getGroupedConfigs, saveConfig, resetConfig } from '../api/inspect';
import { GROUPS, META, DEPRECATED, humanize } from '../utils/inspectConfigMeta';

export default {
  name: 'InspectConfigPanel',
  props: {
    visible: { type: Boolean, default: false }
  },
  data() {
    return {
      innerVisible: this.visible,
      loading: false,
      items: [],
      activeGroup: 'JUDGE'
    };
  },
  computed: {
    /** 只显示确实有配置项的分组，避免点进去是空的 */
    visibleGroups() {
      return GROUPS.filter((g) => this.items.some((i) => this.groupOf(i) === g.key));
    },
    currentItems() {
      return this.items.filter((i) => this.groupOf(i) === this.activeGroup);
    },
    modifiedTotal() {
      return this.items.filter((i) => i.modified).length;
    }
  },
  watch: {
    visible(v) {
      this.innerVisible = v;
      if (v) this.load();
    },
    innerVisible(v) {
      if (v !== this.visible) this.$emit('update:visible', v);
    }
  },
  methods: {
    async load() {
      this.loading = true;
      try {
        const res = await getGroupedConfigs();
        if (res.data && res.data.code === '0') {
          // 后端按代码模块分组，这里按「人想调什么」重新归类，所以先拍平
          const flat = [];
          Object.values(res.data.data || {}).forEach((arr) => flat.push(...arr));
          this.items = flat.filter((i) => !DEPRECATED.includes(i.cfgKey));

          if (!this.visibleGroups.some((g) => g.key === this.activeGroup)) {
            this.activeGroup = (this.visibleGroups[0] || {}).key;
          }
        } else {
          this.$message.error((res.data && res.data.msg) || '加载配置失败');
        }
      } catch (e) {
        this.$message.error('加载配置失败：' + (e.message || e));
      } finally {
        this.loading = false;
      }
    },

    metaOf(item) {
      return META[item.cfgKey] || {};
    },
    /** 没配元数据的新增项退回显示键名，至少不会消失 */
    labelOf(item) {
      return this.metaOf(item).label || item.cfgKey;
    },
    hintOf(item) {
      return this.metaOf(item).hint || '';
    },
    groupOf(item) {
      return this.metaOf(item).group || 'MONITOR';
    },
    humanized(item) {
      return humanize(item.cfgKey, item.cfgValue);
    },
    modifiedIn(groupKey) {
      return this.items.filter((i) => this.groupOf(i) === groupKey && i.modified).length;
    },

    async save(item, value) {
      if (value === undefined || value === null) return;
      const v = String(value);
      if (v === item.lastSaved) return;
      try {
        const res = await saveConfig(item.cfgKey, v);
        if (res.data && res.data.code === '0') {
          item.cfgValue = v;
          item.modified = v !== item.defaultValue;
          item.lastSaved = v;
          this.$message({ message: this.labelOf(item) + ' 已保存', type: 'success', duration: 1500 });
        } else {
          this.$message.error((res.data && res.data.msg) || '保存失败');
          this.load();
        }
      } catch (e) {
        this.$message.error('保存失败：' + (e.message || e));
        this.load();
      }
    },

    async reset(item) {
      try {
        const res = await resetConfig(item.cfgKey);
        if (res.data && res.data.code === '0') {
          item.cfgValue = res.data.data;
          item.lastSaved = res.data.data;
          item.modified = false;
          this.$message({ message: '已恢复默认值', type: 'success', duration: 1500 });
        } else {
          this.$message.error((res.data && res.data.msg) || '恢复失败');
        }
      } catch (e) {
        this.$message.error('恢复失败：' + (e.message || e));
      }
    },

    close(done) {
      this.innerVisible = false;
      if (typeof done === 'function') done();
    }
  }
};
</script>

<style scoped>
.cfg-wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.cfg-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 24px 14px;
  border-bottom: 1px solid #ebeef5;
}

.cfg-title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}

.cfg-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.mod-count {
  color: #e6a23c;
}

.cfg-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.cfg-nav {
  width: 150px;
  flex-shrink: 0;
  border-right: 1px solid #ebeef5;
  padding: 10px 0;
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 16px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  border-left: 2px solid transparent;
}

.nav-item:hover {
  background: #f5f7fa;
}

.nav-item.active {
  color: #409eff;
  background: #ecf5ff;
  border-left-color: #409eff;
  font-weight: 500;
}

.nav-dot {
  position: absolute;
  right: 12px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
}

.cfg-main {
  flex: 1;
  overflow-y: auto;
  padding: 6px 24px 40px;
}

.item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 0;
  border-bottom: 1px solid #f5f7fa;
}

.item-left {
  flex: 1;
  min-width: 0;
}

.item-label {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tip-icon {
  color: #c0c4cc;
  cursor: help;
  font-size: 13px;
}

.tip-icon:hover {
  color: #409eff;
}

.tag-mod {
  font-size: 11px;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 2px;
  padding: 1px 5px;
  font-weight: normal;
}

.item-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.6;
}

.item-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  min-width: 260px;
  justify-content: flex-end;
}

.value-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.human {
  font-size: 12px;
  color: #c0c4cc;
  white-space: nowrap;
}

.reset {
  padding: 0;
}

.empty {
  color: #c0c4cc;
  font-size: 13px;
  text-align: center;
  padding: 40px 0;
}

/* 长文本项换行排布，输入框占满宽度 */
.item >>> .el-textarea {
  width: 360px;
}

.tip {
  max-width: 320px;
  line-height: 1.7;
}

.tip-key {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  opacity: 0.75;
  margin-bottom: 4px;
}

.tip-desc {
  font-size: 12px;
}

.tip-default {
  font-size: 12px;
  opacity: 0.75;
  margin-top: 4px;
}
</style>
