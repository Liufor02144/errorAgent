package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectLibrary;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 异常库。
 */
public interface InspectLibraryMapper {

    List<InspectLibrary> selectEnabled();

    /**
     * @param businessExpected 1-只看业务预期 / 0-只看系统故障 / null-不限
     * @param keyword          按模版、匹配值或原因模糊查
     */
    List<InspectLibrary> selectList(@Param("businessExpected") Integer businessExpected,
                                    @Param("keyword") String keyword);

    /**
     * 待 AI 复核的模版：判为系统故障、且从没复核过的。
     *
     * <p>排除已复核过的，否则每轮都会把同一批重新送给模型 ——
     * 判为故障的那些永远是故障，反复问只是在烧 token。
     * 复核过的标志是 reason 被改写成「AI 复核：…」或「技术故障，不可标…」。
     *
     * @param limit 单次上限，按命中次数降序取 —— 影响面大的先处理
     */
    List<InspectLibrary> selectPendingReview(@Param("limit") int limit);

    int countAll();

    /** 已标记为业务预期内的条目数 */
    int countBusinessExpected();

    /** 判为系统故障的条目数 —— 这些异常每次出现都会报 */
    int countUnconfirmed();

    InspectLibrary selectByValue(@Param("matchType") String matchType,
                                 @Param("matchValue") String matchValue);

    /** 已存在时忽略，返回 0 —— 重复加入不应该报错 */
    int insertIgnore(InspectLibrary record);

    /** 首轮存量批量纳入 */
    int batchInsertIgnore(@Param("list") List<InspectLibrary> list);

    int updateReason(@Param("id") Long id,
                     @Param("reason") String reason,
                     @Param("operator") String operator);

    /** 标记/取消「业务预期内」。这是模版库上唯一的人工判断 */
    int updateBusinessExpected(@Param("id") Long id,
                               @Param("businessExpected") Integer businessExpected,
                               @Param("reason") String reason,
                               @Param("operator") String operator);

    /** 人工修正模版正则 —— 模型生成的正则可能写宽了或写错了 */
    int updatePattern(@Param("id") Long id,
                      @Param("template") String template,
                      @Param("pattern") String pattern,
                      @Param("operator") String operator);

    InspectLibrary selectById(@Param("id") Long id);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);

    int deleteById(@Param("id") Long id);

    int incrHit(@Param("ids") List<Long> ids,
                @Param("delta") long delta,
                @Param("hitTime") Date hitTime);

    /** 页面列表：分页，不带原始样例 */
    List<InspectLibrary> selectPage(@Param("businessExpected") Integer businessExpected,
                                    @Param("keyword") String keyword,
                                    @Param("offset") int offset, @Param("size") int size);

    int countList(@Param("businessExpected") Integer businessExpected,
                  @Param("keyword") String keyword);
}
