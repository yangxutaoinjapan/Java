package cn.lili.modules.promotion.serviceimpl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapBuilder;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.json.JSONUtil;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.modules.promotion.entity.dos.Seckill;
import cn.lili.modules.promotion.entity.dos.SeckillApply;
import cn.lili.modules.promotion.entity.dto.search.SeckillSearchParams;
import cn.lili.modules.promotion.entity.enums.PromotionsApplyStatusEnum;
import cn.lili.modules.promotion.entity.vos.SeckillVO;
import cn.lili.modules.promotion.mapper.SeckillMapper;
import cn.lili.modules.promotion.service.SeckillApplyService;
import cn.lili.modules.promotion.service.SeckillService;
import cn.lili.modules.promotion.tools.PromotionTools;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.SeckillSetting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.GoodsTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * ???????????????????????????
 *
 * @author Chopper
 * @since 2020/8/21
 */
@Service
@Slf4j
public class SeckillServiceImpl extends AbstractPromotionsServiceImpl<SeckillMapper, Seckill> implements SeckillService {

    /**
     * ??????
     */
    @Autowired
    private SettingService settingService;

    @Autowired
    private SeckillApplyService seckillApplyService;

    /**
     * rocketMq??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    /**
     * rocketMq
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;


    @Override
    public SeckillVO getSeckillDetail(String id) {
        Seckill seckill = this.checkSeckillExist(id);
        SeckillVO seckillVO = new SeckillVO();
        BeanUtils.copyProperties(seckill, seckillVO);
        SeckillSearchParams searchParams = new SeckillSearchParams();
        searchParams.setSeckillId(id);
        seckillVO.setSeckillApplyList(this.seckillApplyService.getSeckillApplyList(searchParams));
        return seckillVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void init() {
        //??????????????????

        List<Seckill> seckillList = this.list();
        for (Seckill seckill : seckillList) {
            seckill.setStartTime(null);
            seckill.setEndTime(null);
            this.updateEsGoodsIndex(seckill);
        }
        this.remove(new QueryWrapper<>());

        Setting setting = settingService.get(SettingEnum.SECKILL_SETTING.name());
        SeckillSetting seckillSetting = new Gson().fromJson(setting.getSettingValue(), SeckillSetting.class);

        for (int i = 1; i <= PRE_CREATION; i++) {
            Seckill seckill = new Seckill(i, seckillSetting.getHours(), seckillSetting.getSeckillRule());
            this.savePromotions(seckill);
        }
    }

    @Override
    public long getApplyNum() {
        DateTime now = DateUtil.date();
        LambdaQueryWrapper<Seckill> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.ge(Seckill::getApplyEndTime, now);
        queryWrapper.le(Seckill::getStartTime, now);
        queryWrapper.ge(Seckill::getEndTime, now);
        return this.count(queryWrapper);
    }

    @Override
    public void updateSeckillGoodsNum(String seckillId) {
        Seckill seckill = this.getById(seckillId);
        if (seckill != null) {
            SeckillSearchParams searchParams = new SeckillSearchParams();
            searchParams.setSeckillId(seckillId);
            LambdaUpdateWrapper<Seckill> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Seckill::getId, seckillId);
            updateWrapper.set(Seckill::getGoodsNum,
                    this.seckillApplyService.getSeckillApplyCount(searchParams));
            this.update(updateWrapper);

        }
    }

    /**
     * <<<<<<< HEAD
     * =======
     * <<<<<<< HEAD
     * >>>>>>> origin/master
     * ??????????????????
     * ????????????:
     * 1. checkStatus ??????????????????
     * 2. checkPromotions ??????????????????
     * 3. saveOrUpdate ??????????????????
     * 4. updatePromotionGoods ????????????????????????
     * 5. updateEsGoodsIndex ??????????????????????????????
     *
     * @param promotions ????????????
     * @return ??????????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePromotions(Seckill promotions) {
        this.checkStatus(promotions);
        this.checkPromotions(promotions);
        //?????????????????????????????????????????????
        if (promotions.getApplyEndTime().before(new Date()) || promotions.getApplyEndTime().after(promotions.getStartTime())) {
            throw new ServiceException(ResultCode.STORE_NAME_EXIST_ERROR);
        }
        boolean result = this.updateById(promotions);
        seckillApplyService.updateSeckillApplyTime(promotions);
        return result;
    }


    /**
     * ????????????????????????????????????
     *
     * @param seckill ??????????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEsGoodsSeckill(Seckill seckill, List<SeckillApply> seckillApplies) {
        if (seckillApplies != null && !seckillApplies.isEmpty()) {
            // ??????????????????
            seckill.setScopeId(ArrayUtil.join(seckillApplies.stream().map(SeckillApply::getSkuId).toArray(), ","));
            UpdateWrapper<Seckill> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", seckill.getId());
            updateWrapper.set("scope_id", seckill.getScopeId());
            this.update(updateWrapper);
            //???????????????????????????????????????????????????????????????
            for (SeckillApply seckillApply : seckillApplies) {
                if (seckillApply.getPromotionApplyStatus().equals(PromotionsApplyStatusEnum.PASS.name())) {
                    this.setSeckillApplyTime(seckill, seckillApply);
                }
            }
            if (!seckillApplies.isEmpty()) {
                this.updateEsGoodsIndex(seckill);
            }
        }
    }

    /**
     * ????????????????????????????????????
     *
     * @param seckill ??????????????????
     * @param skuIds  ??????skuId??????
     */
    @Override
    public void deleteEsGoodsSeckill(Seckill seckill, List<String> skuIds) {
        Map<Object, Object> build = MapBuilder.create().put("promotionKey", this.getPromotionType() + "-" + seckill.getId()).put("scopeId", ArrayUtil.join(skuIds.toArray(), ",")).build();
        //????????????????????????
        String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.DELETE_GOODS_INDEX_PROMOTIONS.name();
        //??????mq??????
        rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(build), RocketmqSendCallbackBuilder.commonCallback());
    }

    @Override
    public void setSeckillApplyTime(Seckill seckill, SeckillApply seckillApply) {
        //?????????????????????????????????????????????
        int nextHour = PromotionTools.nextHour(seckill.getHours().split(","), seckillApply.getTimeLine());

        String format = DateUtil.format(seckill.getStartTime(), DatePattern.NORM_DATE_PATTERN);
        DateTime parseStartTime = DateUtil.parse((format + " " + seckillApply.getTimeLine()), "yyyy-MM-dd HH");
        DateTime parseEndTime = DateUtil.parse((format + " " + nextHour), "yyyy-MM-dd HH");
        //??????????????????????????????????????????????????????????????????59???59???
        if (nextHour == seckillApply.getTimeLine()) {
            parseEndTime = DateUtil.parse((format + " " + nextHour + ":59:59"), DatePattern.NORM_DATETIME_PATTERN);
        }
        seckill.setStartTime(parseStartTime);
        //????????????????????????????????????????????????????????????????????????
        seckill.setEndTime(parseEndTime);
    }

    /**
     * ?????????????????????????????????
     *
     * @param id ??????????????????
     * @return ??????????????????
     */
    private Seckill checkSeckillExist(String id) {
        Seckill seckill = this.getById(id);
        if (seckill == null) {
            throw new ServiceException(ResultCode.SECKILL_NOT_EXIST_ERROR);
        }
        return seckill;
    }

    /**
     * ?????????????????????
     *
     * @param promotions ????????????
     */
    @Override
    public void initPromotion(Seckill promotions) {
        super.initPromotion(promotions);
        if (promotions.getStartTime() != null && promotions.getEndTime() == null) {
            promotions.setEndTime(DateUtil.endOfDay(promotions.getStartTime()));
        }
    }

    /**
     * ??????????????????
     *
     * @param promotions ????????????
     */
    @Override
    public void checkStatus(Seckill promotions) {
        super.checkStatus(promotions);
        if (promotions.getStartTime() != null && CharSequenceUtil.isNotEmpty(promotions.getHours())) {
            Integer[] split = Convert.toIntArray(promotions.getHours().split(","));
            Arrays.sort(split);
            String startTimeStr = DateUtil.format(promotions.getStartTime(), DatePattern.NORM_DATE_PATTERN) + " " + split[0] + ":00";
            promotions.setStartTime(DateUtil.parse(startTimeStr, DatePattern.NORM_DATETIME_MINUTE_PATTERN));
            promotions.setEndTime(DateUtil.endOfDay(promotions.getStartTime()));
        }
        if (promotions.getStartTime() != null && promotions.getEndTime() != null) {
            //?????????????????????????????????
            QueryWrapper<Seckill> queryWrapper = PromotionTools.checkActiveTime(promotions.getStartTime(), promotions.getEndTime(), PromotionTypeEnum.SECKILL, null, promotions.getId());
            long sameNum = this.count(queryWrapper);
            //???????????????????????????????????????
            if (sameNum > 0) {
                throw new ServiceException(ResultCode.PROMOTION_SAME_ACTIVE_EXIST);
            }
        }


    }

    /**
     * ??????????????????
     *
     * @return ??????????????????
     */
    @Override
    public PromotionTypeEnum getPromotionType() {
        return PromotionTypeEnum.SECKILL;
    }
}