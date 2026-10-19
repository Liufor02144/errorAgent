package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 异常模版。
 *
 * <p>模版库收录<b>全部</b>异常模版，而不是「允许发生的白名单」——
 * 后者的问题是：不在白名单里的异常没有身份，只能按服务+正则拼出来的粗指纹去聚合，
 * 于是 order 和 product 的同一个 Redis 超时变成两件事，
 * 「组合商品下"夏季限定 冰美式（大杯）"未同步」里的商品名也永远抹不掉。
 *
 * <p>模版由大模型抽取，同时产出 {@link #pattern} 用于之后的纯正则匹配 ——
 * 只有出现没见过的模式才需要再调模型。
 *
 * <p>{@link #businessExpected} 是唯一的人工判断：标记它 = 承认这是业务逻辑
 * 正常走到的分支（「租户收款账号未设置」这种），正常抛出不报；
 * <b>但突增仍然要报</b>，否则它会变成看不见的盲区。
 */
@Data
public class InspectLibrary {

    /** 由大模型抽取的语义模版，页面加入/编辑的都是这种 */
    public static final String TYPE_TEMPLATE = "TEMPLATE";
    /** 整个 logger 都是噪音，如 xxl-job 写日志文件失败 */
    public static final String TYPE_LOGGER = "LOGGER";
    public static final String TYPE_EXCEPTION = "EXCEPTION";
    public static final String TYPE_APP = "APP";

    private Long id;

    private String matchType;

    /** 匹配值。TEMPLATE 类型下存模版的归一化标识 */
    private String matchValue;

    /** 人可读的异常模版，变量已抹成占位符 */
    private String template;

    /**
     * 匹配正则。
     *
     * <p>由大模型生成，入库前校验过「能编译」且「能匹配自身样例」。
     * 写得太宽比碎片化更危险 —— 会把不同异常吞进同一模版，
     * 真问题被藏进「业务预期」里，所以页面上必须可人工修正。
     */
    private String pattern;

    /**
     * 模版的两种状态，没有第三种：
     * <ul>
     *   <li>1 = <b>业务预期</b>：业务规则正常走到的分支，正常抛出不报（突增仍报）</li>
     *   <li>0 = <b>系统故障</b>：<b>默认值</b>，每次出现都报，需要人工分析</li>
     * </ul>
     * 默认是「系统故障」而不是「待定」—— 没人确认过的异常按故障对待才安全，
     * 判错方向的代价完全不对等：误当故障只是多报一条，误当业务预期会让真故障永久静默。
     */
    private Integer businessExpected;

    /** 抽取时的原始日志样例，用于校验正则与人工复核 */
    private String sampleMessage;

    /** 人可读标题，列表展示用 */
    private String headline;

    /** 为什么它属于业务预期 */
    private String reason;

    private String operator;

    private Long hitCount;

    /** 长期不命中说明条目可以清理 */
    private Date lastHitTime;

    private Date firstSeenTime;

    private Integer enabled;

    private Date createTime;

    private Date updateTime;

    private Integer deleted;

    /**
     * 是否已确认为业务预期内。
     *
     * <p>方法名刻意不叫 {@code isBusinessExpected} —— Lombok 会为 Integer 字段生成
     * {@code getBusinessExpected()}，再加一个同属性的 boolean getter 就成了
     * 「重载的 getter 且返回类型不一致」，MyBatis 反射时无法判断该用哪个，
     * 直接抛 ReflectionException 让整批写入失败。
     */
    public boolean expected() {
        return businessExpected != null && businessExpected == 1;
    }
}
