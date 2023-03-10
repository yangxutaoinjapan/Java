package cn.lili.modules.order.cart.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.utils.CurrencyUtil;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.entity.dos.Wholesale;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsSalesModeEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.goods.service.WholesaleService;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dos.MemberAddress;
import cn.lili.modules.member.service.MemberAddressService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.order.cart.entity.dto.MemberCouponDTO;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.cart.entity.enums.CartTypeEnum;
import cn.lili.modules.order.cart.entity.enums.DeliveryMethodEnum;
import cn.lili.modules.order.cart.entity.vo.CartSkuVO;
import cn.lili.modules.order.cart.entity.vo.CartVO;
import cn.lili.modules.order.cart.entity.vo.TradeParams;
import cn.lili.modules.order.cart.render.TradeBuilder;
import cn.lili.modules.order.order.entity.dos.Trade;
import cn.lili.modules.order.order.entity.vo.ReceiptVO;
import cn.lili.modules.promotion.entity.dos.KanjiaActivity;
import cn.lili.modules.promotion.entity.dos.MemberCoupon;
import cn.lili.modules.promotion.entity.dto.search.KanjiaActivitySearchParams;
import cn.lili.modules.promotion.entity.dto.search.MemberCouponSearchParams;
import cn.lili.modules.promotion.entity.enums.KanJiaStatusEnum;
import cn.lili.modules.promotion.entity.enums.MemberCouponStatusEnum;
import cn.lili.modules.promotion.entity.enums.PromotionsScopeTypeEnum;
import cn.lili.modules.promotion.entity.vos.PointsGoodsVO;
import cn.lili.modules.promotion.service.KanjiaActivityService;
import cn.lili.modules.promotion.service.MemberCouponService;
import cn.lili.modules.promotion.service.PointsGoodsService;
import cn.lili.modules.promotion.service.PromotionGoodsService;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.service.EsGoodsSearchService;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.dos.StoreAddress;
import cn.lili.modules.store.service.StoreAddressService;
import cn.lili.modules.store.service.StoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ????????????????????????
 *
 * @author Chopper
 * @since 2020-03-23 12:29 ??????
 */
@Slf4j
@Service
public class CartServiceImpl implements CartService {

    static String errorMessage = "?????????????????????????????????";

    /**
     * ??????
     */
    @Autowired
    private Cache<Object> cache;
    /**
     * ???????????????
     */
    @Autowired
    private MemberCouponService memberCouponService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsSkuService goodsSkuService;
    /**
     * ????????????
     */
    @Autowired
    private PointsGoodsService pointsGoodsService;
    /**
     * ????????????
     */
    @Autowired
    private MemberAddressService memberAddressService;
    /**
     * ES??????
     */
    @Autowired
    private EsGoodsSearchService esGoodsSearchService;
    /**
     * ??????
     */
    @Autowired
    private KanjiaActivityService kanjiaActivityService;
    /**
     * ??????
     */
    @Autowired
    private TradeBuilder tradeBuilder;

    @Autowired
    private MemberService memberService;

    @Autowired
    private PromotionGoodsService promotionGoodsService;

    @Autowired
    private WholesaleService wholesaleService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreAddressService storeAddressService;

    @Override
    public void add(String skuId, Integer num, String cartType, Boolean cover) {
        AuthUser currentUser = Objects.requireNonNull(UserContext.getCurrentUser());
        if (num <= 0) {
            throw new ServiceException(ResultCode.CART_NUM_ERROR);
        }
        CartTypeEnum cartTypeEnum = getCartType(cartType);
        GoodsSku dataSku = checkGoods(skuId);
        Map<String, Object> promotionMap = promotionGoodsService.getCurrentGoodsPromotion(dataSku, cartTypeEnum.name());

        try {
            //?????????????????????????????????????????????????????????????????????????????????????????????????????????
            TradeDTO tradeDTO;
            if (cartTypeEnum.equals(CartTypeEnum.CART)) {

                //?????????????????????????????????????????????????????????????????????????????????
                tradeDTO = this.readDTO(cartTypeEnum);
                List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
                CartSkuVO cartSkuVO = cartSkuVOS.stream().filter(i -> i.getGoodsSku().getId().equals(skuId)).findFirst().orElse(null);


                //???????????????????????????????????????
                if (cartSkuVO != null && dataSku.getCreateTime().equals(cartSkuVO.getGoodsSku().getCreateTime())) {

                    //????????????????????????????????????
                    if (Boolean.TRUE.equals(cover)) {
                        cartSkuVO.setNum(num);
                        this.checkSetGoodsQuantity(cartSkuVO, skuId, num);
                    } else {
                        int oldNum = cartSkuVO.getNum();
                        int newNum = oldNum + num;
                        this.checkSetGoodsQuantity(cartSkuVO, skuId, newNum);
                    }
                    cartSkuVO.setPromotionMap(promotionMap);
                    //?????????????????????
                    cartSkuVO.setSubTotal(CurrencyUtil.mul(cartSkuVO.getPurchasePrice(), cartSkuVO.getNum()));
                } else {

                    //??????????????? ????????????????????????
                    cartSkuVOS.remove(cartSkuVO);
                    //???????????????????????????????????????????????????
                    cartSkuVO = new CartSkuVO(dataSku, promotionMap);

                    cartSkuVO.setCartType(cartTypeEnum);
                    //?????????????????????????????????
                    this.checkSetGoodsQuantity(cartSkuVO, skuId, num);
                    //?????????????????????
                    cartSkuVO.setSubTotal(CurrencyUtil.mul(cartSkuVO.getPurchasePrice(), cartSkuVO.getNum()));
                    cartSkuVOS.add(cartSkuVO);
                }

                //?????????????????????????????????
                cartSkuVO.setChecked(true);
            } else {
                tradeDTO = new TradeDTO(cartTypeEnum);
                tradeDTO.setMemberId(currentUser.getId());
                tradeDTO.setMemberName(currentUser.getUsername());
                List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();

                //???????????????????????????????????????????????????
                CartSkuVO cartSkuVO = new CartSkuVO(dataSku, promotionMap);
                cartSkuVO.setCartType(cartTypeEnum);
                //?????????????????????
                checkCart(cartTypeEnum, cartSkuVO, skuId, num);
                //?????????????????????
                cartSkuVO.setSubTotal(CurrencyUtil.mul(cartSkuVO.getPurchasePrice(), cartSkuVO.getNum()));
                cartSkuVOS.add(cartSkuVO);
            }

            this.checkGoodsSaleModel(dataSku, tradeDTO.getSkuList());
            tradeDTO.setCartTypeEnum(cartTypeEnum);

            remoteCoupon(tradeDTO);

            this.resetTradeDTO(tradeDTO);
        } catch (ServiceException serviceException) {
            throw serviceException;
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(errorMessage);
        }
    }

    /**
     * ????????????????????????????????????key
     *
     * @param cartTypeEnum ????????????
     * @return ??????????????????????????????key
     */
    private String getOriginKey(CartTypeEnum cartTypeEnum) {

        //??????key????????????????????????
        if (cartTypeEnum != null) {
            AuthUser currentUser = UserContext.getCurrentUser();
            return cartTypeEnum.getPrefix() + currentUser.getId();
        }
        throw new ServiceException(ResultCode.ERROR);
    }

    @Override
    public TradeDTO readDTO(CartTypeEnum checkedWay) {
        TradeDTO tradeDTO = (TradeDTO) cache.get(this.getOriginKey(checkedWay));
        if (tradeDTO == null) {
            tradeDTO = new TradeDTO(checkedWay);
            AuthUser currentUser = UserContext.getCurrentUser();
            tradeDTO.setMemberId(currentUser.getId());
            tradeDTO.setMemberName(currentUser.getUsername());
        }
        if (tradeDTO.getMemberAddress() == null) {
            tradeDTO.setMemberAddress(this.memberAddressService.getDefaultMemberAddress());
        }
        return tradeDTO;
    }

    @Override
    public void checked(String skuId, boolean checked) {
        TradeDTO tradeDTO = this.readDTO(CartTypeEnum.CART);

        remoteCoupon(tradeDTO);

        List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            if (cartSkuVO.getGoodsSku().getId().equals(skuId)) {
                cartSkuVO.setChecked(checked);
            }
        }

        this.resetTradeDTO(tradeDTO);
    }

    @Override
    public void checkedStore(String storeId, boolean checked) {
        TradeDTO tradeDTO = this.readDTO(CartTypeEnum.CART);

        remoteCoupon(tradeDTO);

        List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            if (cartSkuVO.getStoreId().equals(storeId)) {
                cartSkuVO.setChecked(checked);
            }
        }

        resetTradeDTO(tradeDTO);
    }

    @Override
    public void checkedAll(boolean checked) {
        TradeDTO tradeDTO = this.readDTO(CartTypeEnum.CART);

        remoteCoupon(tradeDTO);

        List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            cartSkuVO.setChecked(checked);
        }
        resetTradeDTO(tradeDTO);
    }

    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param tradeDTO
     */
    private void remoteCoupon(TradeDTO tradeDTO) {
        tradeDTO.setPlatformCoupon(null);
        tradeDTO.setStoreCoupons(new HashMap<>());
    }

    @Override
    public void delete(String[] skuIds) {
        TradeDTO tradeDTO = this.readDTO(CartTypeEnum.CART);
        List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
        List<CartSkuVO> deleteVos = new ArrayList<>();
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            for (String skuId : skuIds) {
                if (cartSkuVO.getGoodsSku().getId().equals(skuId)) {
                    deleteVos.add(cartSkuVO);
                }
            }
        }
        cartSkuVOS.removeAll(deleteVos);
        resetTradeDTO(tradeDTO);
    }

    @Override
    public void clean() {
        cache.remove(this.getOriginKey(CartTypeEnum.CART));
    }

    public void cleanChecked(TradeDTO tradeDTO) {
        List<CartSkuVO> cartSkuVOS = tradeDTO.getSkuList();
        List<CartSkuVO> deleteVos = new ArrayList<>();
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            if (Boolean.TRUE.equals(cartSkuVO.getChecked())) {
                deleteVos.add(cartSkuVO);
            }
        }
        cartSkuVOS.removeAll(deleteVos);
        //????????????????????????
        tradeDTO.setPlatformCoupon(null);
        tradeDTO.setStoreCoupons(null);
        //????????????????????????
        tradeDTO.setStoreRemark(null);

        resetTradeDTO(tradeDTO);
    }

    @Override
    public void resetTradeDTO(TradeDTO tradeDTO) {
        cache.put(this.getOriginKey(tradeDTO.getCartTypeEnum()), tradeDTO);
    }

    @Override
    public TradeDTO getCheckedTradeDTO(CartTypeEnum way) {
        return tradeBuilder.buildChecked(way);
    }

    /**
     * ?????????????????????????????????
     *
     * @param checkedWay ??????????????????CART/???????????????BUY_NOW/???????????????PINTUAN / ???????????????POINT
     * @return ???????????????????????????
     */
    @Override
    public Long getCanUseCoupon(CartTypeEnum checkedWay) {
        TradeDTO tradeDTO = this.readDTO(checkedWay);
        long count = 0L;
        double totalPrice = tradeDTO.getSkuList().stream().mapToDouble(i -> i.getPurchasePrice() * i.getNum()).sum();
        if (tradeDTO.getSkuList() != null && !tradeDTO.getSkuList().isEmpty()) {
            List<String> ids = tradeDTO.getSkuList().stream().filter(i -> Boolean.TRUE.equals(i.getChecked())).map(i -> i.getGoodsSku().getId()).collect(Collectors.toList());

            List<EsGoodsIndex> esGoodsList = esGoodsSearchService.getEsGoodsBySkuIds(ids, null);
            for (EsGoodsIndex esGoodsIndex : esGoodsList) {
                if (esGoodsIndex != null && esGoodsIndex.getPromotionMap() != null && !esGoodsIndex.getPromotionMap().isEmpty()) {
                    List<String> couponIds = esGoodsIndex.getPromotionMap().keySet().stream().filter(i -> i.contains(PromotionTypeEnum.COUPON.name())).map(i -> i.substring(i.lastIndexOf("-") + 1)).collect(Collectors.toList());
                    if (!couponIds.isEmpty()) {
                        List<MemberCoupon> currentGoodsCanUse = memberCouponService.getCurrentGoodsCanUse(tradeDTO.getMemberId(), couponIds, totalPrice);
                        count = currentGoodsCanUse.size();
                    }
                }
            }

            List<String> storeIds = new ArrayList<>();
            for (CartSkuVO cartSkuVO : tradeDTO.getSkuList()) {
                if (!storeIds.contains(cartSkuVO.getStoreId())) {
                    storeIds.add(cartSkuVO.getStoreId());
                }
            }

            //?????????????????????????????????
            List<MemberCoupon> allScopeMemberCoupon = memberCouponService.getAllScopeMemberCoupon(tradeDTO.getMemberId(), storeIds);
            if (allScopeMemberCoupon != null && !allScopeMemberCoupon.isEmpty()) {
                //????????????????????????
                count += allScopeMemberCoupon.stream().filter(i -> i.getConsumeThreshold() <= totalPrice).count();
            }
        }
        return count;
    }

    @Override
    public TradeDTO getAllTradeDTO() {
        return tradeBuilder.buildCart(CartTypeEnum.CART);
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     *
     * @param skuId ??????skuId
     */
    private GoodsSku checkGoods(String skuId) {
        GoodsSku dataSku = this.goodsSkuService.getGoodsSkuByIdFromCache(skuId);
        if (dataSku == null) {
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        if (!GoodsAuthEnum.PASS.name().equals(dataSku.getAuthFlag()) || !GoodsStatusEnum.UPPER.name().equals(dataSku.getMarketEnable())) {
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        return dataSku;
    }

    /**
     * ????????????????????????????????????
     *
     * @param cartSkuVO ?????????????????????
     * @param skuId     ??????id
     * @param num       ????????????
     */
    private void checkSetGoodsQuantity(CartSkuVO cartSkuVO, String skuId, Integer num) {
        Integer enableStock = goodsSkuService.getStock(skuId);

        //??????sku???????????????????????????0??????????????????????????????????????????????????????
        if (enableStock <= 0 || enableStock < num) {
            throw new ServiceException(ResultCode.GOODS_SKU_QUANTITY_NOT_ENOUGH);
        }

        if (enableStock <= num) {
            cartSkuVO.setNum(enableStock);
        } else {
            cartSkuVO.setNum(num);
        }

        if (cartSkuVO.getGoodsSku() != null && !GoodsSalesModeEnum.WHOLESALE.name().equals(cartSkuVO.getGoodsSku().getSalesModel()) && cartSkuVO.getNum() > 99) {
            cartSkuVO.setNum(99);
        }
    }

    @Override
    public void shippingAddress(String shippingAddressId, String way) {

        //???????????????
        CartTypeEnum cartTypeEnum = CartTypeEnum.CART;
        if (CharSequenceUtil.isNotEmpty(way)) {
            cartTypeEnum = CartTypeEnum.valueOf(way);
        }

        TradeDTO tradeDTO = this.readDTO(cartTypeEnum);
        MemberAddress memberAddress = memberAddressService.getById(shippingAddressId);
        tradeDTO.setMemberAddress(memberAddress);
        this.resetTradeDTO(tradeDTO);
    }

    @Override
    public void shippingSelfAddress(String shopAddressId, String way) {
        //???????????????
        CartTypeEnum cartTypeEnum = CartTypeEnum.CART;
        if (CharSequenceUtil.isNotEmpty(way)) {
            cartTypeEnum = CartTypeEnum.valueOf(way);
        }

        TradeDTO tradeDTO = this.readDTO(cartTypeEnum);
        StoreAddress storeAddress = storeAddressService.getById(shopAddressId);
        tradeDTO.setStoreAddress(storeAddress);
        this.resetTradeDTO(tradeDTO);
    }

    /**
     * ????????????
     *
     * @param receiptVO ????????????
     * @param way       ???????????????
     */
    @Override
    public void shippingReceipt(ReceiptVO receiptVO, String way) {
        CartTypeEnum cartTypeEnum = CartTypeEnum.CART;
        if (CharSequenceUtil.isNotEmpty(way)) {
            cartTypeEnum = CartTypeEnum.valueOf(way);
        }
        TradeDTO tradeDTO = this.readDTO(cartTypeEnum);
        tradeDTO.setNeedReceipt(true);
        tradeDTO.setReceiptVO(receiptVO);
        this.resetTradeDTO(tradeDTO);
    }

    /**
     * ??????????????????
     *
     * @param deliveryMethod ????????????
     * @param way            ???????????????
     */
    @Override
    public void shippingMethod(String deliveryMethod, String way) {
        CartTypeEnum cartTypeEnum = CartTypeEnum.CART;
        if (CharSequenceUtil.isNotEmpty(way)) {
            cartTypeEnum = CartTypeEnum.valueOf(way);
        }
        TradeDTO tradeDTO = this.getCheckedTradeDTO(cartTypeEnum);
        for (CartVO cartVO : tradeDTO.getCartList()) {
            cartVO.setDeliveryMethod(DeliveryMethodEnum.valueOf(deliveryMethod).name());
        }
        this.resetTradeDTO(tradeDTO);
        TradeDTO neTradeDTO = (TradeDTO) cache.get(this.getOriginKey(cartTypeEnum));
    }

    /**
     * ???????????????????????????
     *
     * @param checked ????????????
     * @return ?????????????????????
     */
    @Override
    public Long getCartNum(Boolean checked) {
        //???????????????
        TradeDTO tradeDTO = this.getAllTradeDTO();
        //??????sku??????
        List<CartSkuVO> collect = tradeDTO.getSkuList().stream().filter(i -> Boolean.FALSE.equals(i.getInvalid())).collect(Collectors.toList());
        long count = 0L;
        if (!tradeDTO.getSkuList().isEmpty()) {
            if (checked != null) {
                count = collect.stream().filter(i -> i.getChecked().equals(checked)).count();
            } else {
                count = collect.size();
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void selectCoupon(String couponId, String way, boolean use) {
        AuthUser currentUser = Objects.requireNonNull(UserContext.getCurrentUser());
        //?????????????????????????????????????????????
        CartTypeEnum cartTypeEnum = getCartType(way);

        //????????????????????????????????????
        if (cartTypeEnum.equals(CartTypeEnum.POINTS)) {
            throw new ServiceException(ResultCode.SPECIAL_CANT_USE);
        }

        TradeDTO tradeDTO = this.readDTO(cartTypeEnum);

        MemberCouponSearchParams searchParams = new MemberCouponSearchParams();
        searchParams.setMemberCouponStatus(MemberCouponStatusEnum.NEW.name());
        searchParams.setMemberId(currentUser.getId());
        searchParams.setId(couponId);
        MemberCoupon memberCoupon = memberCouponService.getMemberCoupon(searchParams);
        if (memberCoupon == null) {
            throw new ServiceException(ResultCode.COUPON_EXPIRED);
        }
        //??????????????? ??????
        if (use) {
            this.useCoupon(tradeDTO, memberCoupon, cartTypeEnum);
        } else {
            if (Boolean.TRUE.equals(memberCoupon.getPlatformFlag())) {
                tradeDTO.setPlatformCoupon(null);
            } else {
                tradeDTO.getStoreCoupons().remove(memberCoupon.getStoreId());
            }
        }
        this.resetTradeDTO(tradeDTO);
    }


    @Override
    public Trade createTrade(TradeParams tradeParams) {
        //???????????????
        CartTypeEnum cartTypeEnum = getCartType(tradeParams.getWay());
        TradeDTO tradeDTO = this.readDTO(cartTypeEnum);
        //??????????????????
        tradeDTO.setClientType(tradeParams.getClient());
        tradeDTO.setStoreRemark(tradeParams.getRemark());
        tradeDTO.setParentOrderSn(tradeParams.getParentOrderSn());
        //???????????????????????????
        if(tradeDTO.getStoreAddress() == null){
            if (tradeDTO.getMemberAddress() == null) {
                throw new ServiceException(ResultCode.MEMBER_ADDRESS_NOT_EXIST);
            }
        }
        //????????????
        Trade trade = tradeBuilder.createTrade(tradeDTO);
        this.cleanChecked(this.readDTO(cartTypeEnum));
        return trade;
    }

    @Override
    public List<String> shippingMethodList(String way) {
        List<String> list = new ArrayList<String>();
        list.add(DeliveryMethodEnum.LOGISTICS.name());
        TradeDTO tradeDTO = this.getCheckedTradeDTO(CartTypeEnum.valueOf(way));
        if(tradeDTO.getCartList().size()==1){
            for (CartVO cartVO : tradeDTO.getCartList()) {
                Store store = storeService.getById(cartVO.getStoreId());
                if(store.getSelfPickFlag() != null && store.getSelfPickFlag()){
                    list.add(DeliveryMethodEnum.SELF_PICK_UP.name());
                }
            }
        }
        return list;
    }


    /**
     * ?????????????????????
     *
     * @param way
     * @return
     */
    private CartTypeEnum getCartType(String way) {
        //???????????????
        CartTypeEnum cartTypeEnum = CartTypeEnum.CART;
        if (CharSequenceUtil.isNotEmpty(way)) {
            try {
                cartTypeEnum = CartTypeEnum.valueOf(way);
            } catch (IllegalArgumentException e) {
                log.error("????????????????????????????????????", e);
            }
        }
        return cartTypeEnum;
    }

    /**
     * ?????????????????????
     *
     * @param tradeDTO     ????????????
     * @param memberCoupon ???????????????
     * @param cartTypeEnum ?????????
     */
    private void useCoupon(TradeDTO tradeDTO, MemberCoupon memberCoupon, CartTypeEnum cartTypeEnum) {

        //??????????????????????????????
        List<CartSkuVO> cartSkuVOS = checkCoupon(memberCoupon, tradeDTO);

        //??????????????????????????????????????????
        Map<String, Double> skuPrice = new HashMap<>(1);


        //???????????????
        double cartPrice = 0d;

        //??????????????????????????????
        for (CartSkuVO cartSkuVO : cartSkuVOS) {
            if (Boolean.FALSE.equals(cartSkuVO.getChecked())) {
                continue;
            }
            //?????????????????????????????????????????????????????????
            if (cartSkuVO.getPromotionMap() != null && !cartSkuVO.getPromotionMap().isEmpty()) {
                if (cartSkuVO.getPromotionMap().keySet().stream().anyMatch(i -> i.contains(PromotionTypeEnum.PINTUAN.name()) || i.contains(PromotionTypeEnum.SECKILL.name()))) {
                    cartPrice = CurrencyUtil.add(cartPrice, CurrencyUtil.mul(cartSkuVO.getPurchasePrice(), cartSkuVO.getNum()));
                    skuPrice.put(cartSkuVO.getGoodsSku().getId(), CurrencyUtil.mul(cartSkuVO.getPurchasePrice(), cartSkuVO.getNum()));
                } else {
                    cartPrice = CurrencyUtil.add(cartPrice, CurrencyUtil.mul(cartSkuVO.getGoodsSku().getPrice(), cartSkuVO.getNum()));
                    skuPrice.put(cartSkuVO.getGoodsSku().getId(), CurrencyUtil.mul(cartSkuVO.getGoodsSku().getPrice(), cartSkuVO.getNum()));
                }
            } else {
                cartPrice = CurrencyUtil.add(cartPrice, CurrencyUtil.mul(cartSkuVO.getGoodsSku().getPrice(), cartSkuVO.getNum()));
                skuPrice.put(cartSkuVO.getGoodsSku().getId(), CurrencyUtil.mul(cartSkuVO.getGoodsSku().getPrice(), cartSkuVO.getNum()));
            }
        }


        //????????????????????????????????????????????????
        if (cartPrice >= memberCoupon.getConsumeThreshold()) {
            //????????????????????????
            if (Boolean.TRUE.equals(memberCoupon.getPlatformFlag())) {
                tradeDTO.setPlatformCoupon(new MemberCouponDTO(skuPrice, memberCoupon));
            } else {
                tradeDTO.getStoreCoupons().put(memberCoupon.getStoreId(), new MemberCouponDTO(skuPrice, memberCoupon));
            }
        }

    }

    /**
     * ??????????????????????????????????????????
     *
     * @param memberCoupon ?????????????????????????????????
     * @param tradeDTO     ???????????????
     * @return ???????????????????????????
     */
    private List<CartSkuVO> checkCoupon(MemberCoupon memberCoupon, TradeDTO tradeDTO) {
        List<CartSkuVO> cartSkuVOS;
        //??????????????????????????????????????????
        if (Boolean.FALSE.equals(memberCoupon.getPlatformFlag())) {
            cartSkuVOS = tradeDTO.getSkuList().stream().filter(i -> i.getStoreId().equals(memberCoupon.getStoreId())).collect(Collectors.toList());
        }
        //??????????????????????????????????????????????????????
        else {
            cartSkuVOS = tradeDTO.getSkuList();
        }

        //??????????????????????????????????????????????????????????????????sku
        if (memberCoupon.getScopeType().equals(PromotionsScopeTypeEnum.ALL.name())) {
            return cartSkuVOS;
        } else if (memberCoupon.getScopeType().equals(PromotionsScopeTypeEnum.PORTION_GOODS_CATEGORY.name())) {
            //????????????????????????
            return cartSkuVOS.stream().filter(i -> CharSequenceUtil.contains(memberCoupon.getScopeId(), i.getGoodsSku().getCategoryPath())).collect(Collectors.toList());
        } else if (memberCoupon.getScopeType().equals(PromotionsScopeTypeEnum.PORTION_GOODS.name())) {
            //????????????ID????????????
            return cartSkuVOS.stream().filter(i -> CharSequenceUtil.contains(memberCoupon.getScopeId(), i.getGoodsSku().getId())).collect(Collectors.toList());
        } else if (memberCoupon.getScopeType().equals(PromotionsScopeTypeEnum.PORTION_SHOP_CATEGORY.name())) {
            //??????????????????????????????
            return cartSkuVOS.stream().filter(i -> CharSequenceUtil.contains(memberCoupon.getScopeId(), i.getGoodsSku().getStoreCategoryPath())).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    /**
     * ???????????????
     *
     * @param cartTypeEnum ???????????????
     * @param cartSkuVO    SKUVO
     * @param skuId        SkuId
     * @param num          ??????
     */
    private void checkCart(CartTypeEnum cartTypeEnum, CartSkuVO cartSkuVO, String skuId, Integer num) {

        this.checkSetGoodsQuantity(cartSkuVO, skuId, num);
        //????????????
        if (cartTypeEnum.equals(CartTypeEnum.PINTUAN)) {
            //????????????
            checkPintuan(cartSkuVO);
        } else if (cartTypeEnum.equals(CartTypeEnum.KANJIA)) {
            //????????????????????????
            checkKanjia(cartSkuVO);
        } else if (cartTypeEnum.equals(CartTypeEnum.POINTS)) {
            //????????????????????????
            checkPoint(cartSkuVO);
        }
    }


    private void checkGoodsSaleModel(GoodsSku dataSku, List<CartSkuVO> cartSkuVOS) {
        if (dataSku.getSalesModel().equals(GoodsSalesModeEnum.WHOLESALE.name())) {
            int numSum = 0;
            List<CartSkuVO> sameGoodsIdSkuList = cartSkuVOS.stream().filter(i -> i.getGoodsSku().getGoodsId().equals(dataSku.getGoodsId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(sameGoodsIdSkuList)) {
                numSum += sameGoodsIdSkuList.stream().mapToInt(CartSkuVO::getNum).sum();
            }
            Wholesale match = wholesaleService.match(dataSku.getGoodsId(), numSum);
            if (match != null) {
                sameGoodsIdSkuList.forEach(i -> {
                    i.setPurchasePrice(match.getPrice());
                    i.setSubTotal(CurrencyUtil.mul(i.getPurchasePrice(), i.getNum()));
                });
            }
        }
    }

    /**
     * ??????????????????
     *
     * @param cartSkuVO ???????????????
     */
    private void checkPintuan(CartSkuVO cartSkuVO) {
        //????????????????????????????????????????????????
        //??????????????????
        if (cartSkuVO.getPromotionMap() != null && !cartSkuVO.getPromotionMap().isEmpty()) {
            Optional<Map.Entry<String, Object>> pintuanPromotions = cartSkuVO.getPromotionMap().entrySet().stream().filter(i -> i.getKey().contains(PromotionTypeEnum.PINTUAN.name())).findFirst();
            if (pintuanPromotions.isPresent()) {
                JSONObject promotionsObj = JSONUtil.parseObj(pintuanPromotions.get().getValue());
                //??????????????????
                cartSkuVO.setPintuanId(promotionsObj.get("id").toString());
                //????????????????????????
                Integer limitNum = promotionsObj.get("limitNum", Integer.class);
                if (limitNum != 0 && cartSkuVO.getNum() > limitNum) {
                    throw new ServiceException(ResultCode.CART_PINTUAN_LIMIT_ERROR);
                }
            }
        }
    }

    /**
     * ??????????????????
     *
     * @param cartSkuVO ???????????????
     */
    private void checkKanjia(CartSkuVO cartSkuVO) {
        if (cartSkuVO.getPromotionMap() != null && !cartSkuVO.getPromotionMap().isEmpty()) {
            Optional<Map.Entry<String, Object>> kanjiaPromotions = cartSkuVO.getPromotionMap().entrySet().stream().filter(i -> i.getKey().contains(PromotionTypeEnum.KANJIA.name())).findFirst();
            if (kanjiaPromotions.isPresent()) {
                JSONObject promotionsObj = JSONUtil.parseObj(kanjiaPromotions.get().getValue());
                //???????????????????????????????????????
                KanjiaActivitySearchParams kanjiaActivitySearchParams = new KanjiaActivitySearchParams();
                kanjiaActivitySearchParams.setKanjiaActivityGoodsId(promotionsObj.get("id", String.class));
                kanjiaActivitySearchParams.setMemberId(UserContext.getCurrentUser().getId());
                kanjiaActivitySearchParams.setStatus(KanJiaStatusEnum.SUCCESS.name());
                KanjiaActivity kanjiaActivity = kanjiaActivityService.getKanjiaActivity(kanjiaActivitySearchParams);

                //????????????????????????????????????
                //????????????????????????
                if (kanjiaActivity == null) {
                    throw new ServiceException(ResultCode.KANJIA_ACTIVITY_NOT_FOUND_ERROR);
                    //???????????????????????????????????????
                } else if (!KanJiaStatusEnum.SUCCESS.name().equals(kanjiaActivity.getStatus())) {
                    cartSkuVO.setKanjiaId(kanjiaActivity.getId());
                    cartSkuVO.setPurchasePrice(0D);
                    throw new ServiceException(ResultCode.KANJIA_ACTIVITY_NOT_PASS_ERROR);
                }
                //??????????????????????????????
                cartSkuVO.setKanjiaId(kanjiaActivity.getId());
                cartSkuVO.setNum(1);
            }
        }
    }

    /**
     * ????????????????????????
     *
     * @param cartSkuVO ???????????????
     */
    private void checkPoint(CartSkuVO cartSkuVO) {

        PointsGoodsVO pointsGoodsVO = pointsGoodsService.getPointsGoodsDetailBySkuId(cartSkuVO.getGoodsSku().getId());

        if (pointsGoodsVO != null) {
            Member userInfo = memberService.getUserInfo();
            if (userInfo.getPoint() < pointsGoodsVO.getPoints()) {
                throw new ServiceException(ResultCode.POINT_NOT_ENOUGH);
            }
            if (pointsGoodsVO.getActiveStock() < 1) {
                throw new ServiceException(ResultCode.POINT_GOODS_ACTIVE_STOCK_INSUFFICIENT);
            }
            cartSkuVO.setPoint(pointsGoodsVO.getPoints());
            cartSkuVO.setPurchasePrice(0D);
            cartSkuVO.setPointsId(pointsGoodsVO.getId());
        }
    }
}
