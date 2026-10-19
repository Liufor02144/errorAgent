/**
 * 巡检页面的共用格式化。
 *
 * 抽出来是因为之前列表页和详情页各写了一份 formatTime，
 * 只改了一处，另一处仍然把毫秒时间戳原样显示成一串数字。
 */

/**
 * 统一时间格式：yyyy-MM-dd HH:mm:ss
 *
 * 后端返回的既可能是毫秒时间戳（数字或数字串），也可能是 ISO 字符串，
 * 只做 replace 处理不了前者。
 */
export function formatTime(t) {
  if (t === null || t === undefined || t === '') return '—';

  let d;
  if (typeof t === 'number' || /^\d{10,}$/.test(String(t).trim())) {
    const n = Number(t);
    // 10 位是秒、13 位是毫秒
    d = new Date(String(t).trim().length <= 10 ? n * 1000 : n);
  } else {
    // Safari 不认 'yyyy-MM-dd HH:mm:ss'，要换成斜杠
    d = new Date(String(t).replace('T', ' ').replace(/-/g, '/'));
  }
  if (isNaN(d.getTime())) return String(t);

  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 列表里用的短格式：MM-dd HH:mm */
export function formatTimeShort(t) {
  const full = formatTime(t);
  return full === '—' || full.length < 16 ? full : full.substring(5, 16);
}

/** 千分位。null 显示为破折号，不显示 0 —— 0 会被读成「没有」 */
export function formatNum(n) {
  if (n === null || n === undefined || n === '') return '—';
  const v = Number(n);
  return isNaN(v) ? String(n) : v.toLocaleString();
}

/**
 * 标题取最能说明问题的那句话。
 *
 * 不固定用异常类名：近半数 ERROR 日志没有异常对象
 * （log.error 直接打印，没有 throw），它们的异常类名只会是占位符，
 * 而消息内容完全可读。
 */
export function headlineOf(fp) {
  if (!fp) return '—';
  return fp.excCause || fp.msgTpl || fp.excSimple || '（无可用描述）';
}

/** 副标题：异常类 + 出错位置 */
export function sublineOf(fp) {
  if (!fp) return '';
  const parts = [];
  if (fp.excSimple) parts.push(fp.excSimple);
  if (fp.hasStack && fp.bizFrame) {
    parts.push(fp.bizFrame);
  } else if (fp.loggerClass) {
    // logger 是「谁打的日志」，不等于「谁抛的异常」
    parts.push(`${fp.loggerClass}（打印位置，非抛出点）`);
  }
  return parts.join(' · ');
}

/**
 * 持续时长，人可读。
 *
 * 事件列表里最重要的一列：「4182 次」不够说明问题，
 * 「3 小时里 4182 次」才说明问题还在继续。
 */
export function durationTextOf(from, to) {
  if (!from || !to) return '刚刚发生';
  const ms = new Date(from).getTime && new Date(to).getTime
    ? new Date(to) - new Date(from)
    : 0;
  if (!ms || isNaN(ms) || ms < 60000) return '刚刚发生';

  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return `${minutes} 分钟`;

  const hours = Math.floor(minutes / 60);
  const restMin = minutes % 60;
  if (hours < 24) {
    return restMin === 0 ? `${hours} 小时` : `${hours} 小时 ${restMin} 分`;
  }
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

export const SEVERITY_TAG = { P0: 'danger', P1: 'warning', P2: 'primary', P3: 'info' };

/** 事件成因。只有两种：不在异常库、在库但突增 */
export const EVENT_TYPE_TEXT = { NEW: '新增', SURGE: '突增' };
