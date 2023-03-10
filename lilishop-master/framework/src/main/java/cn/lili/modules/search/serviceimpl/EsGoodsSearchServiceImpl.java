package cn.lili.modules.search.serviceimpl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.entity.dos.EsGoodsRelatedInfo;
import cn.lili.modules.search.entity.dto.EsGoodsSearchDTO;
import cn.lili.modules.search.entity.dto.ParamOptions;
import cn.lili.modules.search.entity.dto.SelectorOptions;
import cn.lili.modules.search.service.EsGoodsSearchService;
import com.alibaba.druid.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.bucket.nested.ParsedNested;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHitSupport;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchPage;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ES???????????????????????????
 *
 * @author paulG
 * @since 2020/10/16
 **/
@Slf4j
@Service
public class EsGoodsSearchServiceImpl implements EsGoodsSearchService {

    // ??????????????????
    private static final String MINIMUM_SHOULD_MATCH = "20%";

    private static final String ATTR_PATH = "attrList";
    private static final String ATTR_VALUE = "attrList.value";
    private static final String ATTR_NAME = "attrList.name";
    private static final String ATTR_SORT = "attrList.sort";
    private static final String ATTR_BRAND_ID = "brandId";
    private static final String ATTR_BRAND_NAME = "brandNameAgg";
    private static final String ATTR_BRAND_URL = "brandUrlAgg";
    private static final String ATTR_NAME_KEY = "nameList";
    private static final String ATTR_VALUE_KEY = "valueList";
    /**
     * ES
     */
    @Autowired
    private ElasticsearchOperations restTemplate;
    /**
     * ??????
     */
    @Autowired
    private Cache<Object> cache;

    @Override
    public SearchPage<EsGoodsIndex> searchGoods(EsGoodsSearchDTO searchDTO, PageVO pageVo) {
        if (CharSequenceUtil.isNotBlank(searchDTO.getKeyword())) {
            cache.incrementScore(CachePrefix.HOT_WORD.getPrefix(), searchDTO.getKeyword());
        }
        NativeSearchQueryBuilder searchQueryBuilder = createSearchQueryBuilder(searchDTO, pageVo);
        NativeSearchQuery searchQuery = searchQueryBuilder.build();
        log.debug("searchGoods DSL:{}", searchQuery.getQuery());
        SearchHits<EsGoodsIndex> search = restTemplate.search(searchQuery, EsGoodsIndex.class);
        return SearchHitSupport.searchPageFor(search, searchQuery.getPageable());
    }


    @Override
    public EsGoodsRelatedInfo getSelector(EsGoodsSearchDTO goodsSearch, PageVO pageVo) {
        NativeSearchQueryBuilder builder = createSearchQueryBuilder(goodsSearch, null);
        //??????
        AggregationBuilder categoryNameBuilder = AggregationBuilders.terms("categoryNameAgg").field("categoryNamePath.keyword");
        builder.addAggregation(AggregationBuilders.terms("categoryAgg").field("categoryPath").subAggregation(categoryNameBuilder));

        //??????
        AggregationBuilder brandNameBuilder = AggregationBuilders.terms(ATTR_BRAND_NAME).field("brandName.keyword");
        builder.addAggregation(AggregationBuilders.terms("brandIdNameAgg").field(ATTR_BRAND_ID).size(Integer.MAX_VALUE).subAggregation(brandNameBuilder));
        AggregationBuilder brandUrlBuilder = AggregationBuilders.terms(ATTR_BRAND_URL).field("brandUrl.keyword");
        builder.addAggregation(AggregationBuilders.terms("brandIdUrlAgg").field(ATTR_BRAND_ID).size(Integer.MAX_VALUE).subAggregation(brandUrlBuilder));
        //??????
        AggregationBuilder valuesBuilder = AggregationBuilders.terms("valueAgg").field(ATTR_VALUE);
        AggregationBuilder sortBuilder = AggregationBuilders.sum("sortAgg").field(ATTR_SORT);
        AggregationBuilder paramsNameBuilder = AggregationBuilders.terms("nameAgg").field(ATTR_NAME).subAggregation(sortBuilder).order(BucketOrder.aggregation("sortAgg", false)).subAggregation(valuesBuilder);
        builder.addAggregation(AggregationBuilders.nested("attrAgg", ATTR_PATH).subAggregation(paramsNameBuilder));
        NativeSearchQuery searchQuery = builder.build();
        SearchHits<EsGoodsIndex> search = restTemplate.search(searchQuery, EsGoodsIndex.class);

        log.debug("getSelector DSL:{}", searchQuery.getQuery());
        Map<String, Aggregation> aggregationMap = Objects.requireNonNull(search.getAggregations()).getAsMap();
        return convertToEsGoodsRelatedInfo(aggregationMap, goodsSearch);
    }

    @Override
    public List<EsGoodsIndex> getEsGoodsBySkuIds(List<String> skuIds, PageVO pageVo) {
        NativeSearchQueryBuilder searchQueryBuilder = new NativeSearchQueryBuilder();
        NativeSearchQuery build = searchQueryBuilder.build();
        build.setIds(skuIds);
        if (pageVo != null) {
            int pageNumber = pageVo.getPageNumber() - 1;
            if (pageNumber < 0) {
                pageNumber = 0;
            }
            Pageable pageable = PageRequest.of(pageNumber, pageVo.getPageSize());
            //??????
            searchQueryBuilder.withPageable(pageable);
        }
        return restTemplate.multiGet(build, EsGoodsIndex.class, restTemplate.getIndexCoordinatesFor(EsGoodsIndex.class));
    }

    /**
     * ??????id??????????????????
     *
     * @param id ??????skuId
     * @return ????????????
     */
    @Override
    public EsGoodsIndex getEsGoodsById(String id) {
        return this.restTemplate.get(id, EsGoodsIndex.class);
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param aggregationMap ????????????
     * @return ????????????????????????
     */
    private EsGoodsRelatedInfo convertToEsGoodsRelatedInfo(Map<String, Aggregation> aggregationMap, EsGoodsSearchDTO goodsSearch) {
        EsGoodsRelatedInfo esGoodsRelatedInfo = new EsGoodsRelatedInfo();
        //??????
        List<SelectorOptions> categoryOptions = new ArrayList<>();
        ParsedStringTerms categoryTerms = (ParsedStringTerms) aggregationMap.get("categoryAgg");
        List<? extends Terms.Bucket> categoryBuckets = categoryTerms.getBuckets();
        if (categoryBuckets != null && !categoryBuckets.isEmpty()) {
            categoryOptions = this.convertCategoryOptions(categoryBuckets);
        }
        esGoodsRelatedInfo.setCategories(categoryOptions);

        //??????
        ParsedStringTerms brandNameTerms = (ParsedStringTerms) aggregationMap.get("brandIdNameAgg");
        ParsedStringTerms brandUrlTerms = (ParsedStringTerms) aggregationMap.get("brandIdUrlAgg");
        List<? extends Terms.Bucket> brandBuckets = brandNameTerms.getBuckets();
        List<? extends Terms.Bucket> brandUrlBuckets = brandUrlTerms.getBuckets();
        List<SelectorOptions> brandOptions = new ArrayList<>();
        if (brandBuckets != null && !brandBuckets.isEmpty()) {
            brandOptions = this.convertBrandOptions(goodsSearch, brandBuckets, brandUrlBuckets);
        }
        esGoodsRelatedInfo.setBrands(brandOptions);

        //??????
        ParsedNested attrTerms = (ParsedNested) aggregationMap.get("attrAgg");
        if (!goodsSearch.getNotShowCol().isEmpty()) {
            if (goodsSearch.getNotShowCol().containsKey(ATTR_NAME_KEY) && goodsSearch.getNotShowCol().containsKey(ATTR_VALUE_KEY)) {
                esGoodsRelatedInfo.setParamOptions(buildGoodsParam(attrTerms, goodsSearch.getNotShowCol().get(ATTR_NAME_KEY)));
            }
        } else {
            esGoodsRelatedInfo.setParamOptions(buildGoodsParam(attrTerms, null));
        }

        return esGoodsRelatedInfo;
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param goodsSearch     ????????????
     * @param brandBuckets    ?????????????????????
     * @param brandUrlBuckets ???????????????????????????
     * @return ?????????????????????
     */
    private List<SelectorOptions> convertBrandOptions(EsGoodsSearchDTO goodsSearch, List<? extends Terms.Bucket> brandBuckets, List<? extends Terms.Bucket> brandUrlBuckets) {
        List<SelectorOptions> brandOptions = new ArrayList<>();
        for (int i = 0; i < brandBuckets.size(); i++) {
            String brandId = brandBuckets.get(i).getKey().toString();
            //???????????????id???0?????????????????????????????????????????????????????????????????????
            //?????????id????????????
            if (brandId.equals("0") ||
                    (CharSequenceUtil.isNotEmpty(goodsSearch.getBrandId())
                            && Arrays.asList(goodsSearch.getBrandId().split("@")).contains(brandId))) {
                continue;
            }

            String brandName = "";
            if (brandBuckets.get(i).getAggregations() != null && brandBuckets.get(i).getAggregations().get(ATTR_BRAND_NAME) != null) {
                ParsedStringTerms aggregation = brandBuckets.get(i).getAggregations().get(ATTR_BRAND_NAME);
                brandName = this.getAggregationsBrandOptions(aggregation);
                if (StringUtils.isEmpty(brandName)) {
                    continue;
                }
            }

            String brandUrl = "";
            if (brandUrlBuckets != null && !brandUrlBuckets.isEmpty() &&
                    brandUrlBuckets.get(i).getAggregations() != null &&
                    brandUrlBuckets.get(i).getAggregations().get(ATTR_BRAND_URL) != null) {
                ParsedStringTerms aggregation = brandUrlBuckets.get(i).getAggregations().get(ATTR_BRAND_URL);
                brandUrl = this.getAggregationsBrandOptions(aggregation);
                if (StringUtils.isEmpty(brandUrl)) {
                    continue;
                }
            }
            SelectorOptions so = new SelectorOptions();
            so.setName(brandName);
            so.setValue(brandId);
            so.setUrl(brandUrl);
            brandOptions.add(so);
        }
        return brandOptions;
    }

    /**
     * ????????????????????????????????????
     *
     * @param brandAgg ??????????????????
     * @return ??????????????????????????????
     */
    private String getAggregationsBrandOptions(ParsedStringTerms brandAgg) {
        List<? extends Terms.Bucket> brandAggBuckets = brandAgg.getBuckets();
        if (brandAggBuckets != null && !brandAggBuckets.isEmpty()) {
            return brandAggBuckets.get(0).getKey().toString();
        }
        return "";
    }


    /**
     * ??????????????????????????????????????????
     *
     * @param categoryBuckets ??????????????????
     * @return ?????????????????????
     */
    private List<SelectorOptions> convertCategoryOptions(List<? extends Terms.Bucket> categoryBuckets) {
        List<SelectorOptions> categoryOptions = new ArrayList<>();
        for (Terms.Bucket categoryBucket : categoryBuckets) {
            String categoryPath = categoryBucket.getKey().toString();
            ParsedStringTerms categoryNameAgg = categoryBucket.getAggregations().get("categoryNameAgg");
            List<? extends Terms.Bucket> categoryNameBuckets = categoryNameAgg.getBuckets();


            String categoryNamePath = categoryPath;
            if (!categoryNameBuckets.isEmpty()) {
                categoryNamePath = categoryNameBuckets.get(0).getKey().toString();
            }
            String[] split = ArrayUtil.distinct(categoryPath.split(","));
            String[] nameSplit = categoryNamePath.split(",");
            if (split.length == nameSplit.length) {
                for (int i = 0; i < split.length; i++) {
                    SelectorOptions so = new SelectorOptions();
                    so.setName(nameSplit[i]);
                    so.setValue(split[i]);
                    if (!categoryOptions.contains(so)) {
                        categoryOptions.add(so);
                    }
                }
            }
        }
        return categoryOptions;
    }

    /**
     * ????????????????????????
     *
     * @param attrTerms ????????????????????????
     * @param nameList  ??????????????????
     * @return ??????????????????
     */
    private List<ParamOptions> buildGoodsParam(ParsedNested attrTerms, List<String> nameList) {
        if (attrTerms != null) {
            Aggregations attrAggregations = attrTerms.getAggregations();
            Map<String, Aggregation> attrMap = attrAggregations.getAsMap();
            ParsedStringTerms nameAgg = (ParsedStringTerms) attrMap.get("nameAgg");

            if (nameAgg != null) {
                return this.buildGoodsParamOptions(nameAgg, nameList);
            }

        }
        return new ArrayList<>();
    }

    /**
     * ????????????????????????
     *
     * @param nameAgg  ????????????????????????
     * @param nameList ??????????????????
     * @return ????????????????????????
     */
    private List<ParamOptions> buildGoodsParamOptions(ParsedStringTerms nameAgg, List<String> nameList) {
        List<ParamOptions> paramOptions = new ArrayList<>();
        List<? extends Terms.Bucket> nameBuckets = nameAgg.getBuckets();

        for (Terms.Bucket bucket : nameBuckets) {
            String name = bucket.getKey().toString();
            ParamOptions paramOptions1 = new ParamOptions();
            ParsedStringTerms valueAgg = bucket.getAggregations().get("valueAgg");
            List<? extends Terms.Bucket> valueBuckets = valueAgg.getBuckets();
            List<String> valueSelectorList = new ArrayList<>();

            for (Terms.Bucket valueBucket : valueBuckets) {
                String value = valueBucket.getKey().toString();

                if (CharSequenceUtil.isNotEmpty(value)) {
                    valueSelectorList.add(value);
                }

            }
            if (nameList == null || !nameList.contains(name)) {
                paramOptions1.setKey(name);
                paramOptions1.setValues(valueSelectorList);
                paramOptions.add(paramOptions1);
            }
        }
        return paramOptions;
    }

    /**
     * ??????es??????builder
     *
     * @param searchDTO ????????????
     * @param pageVo    ????????????
     * @return es??????builder
     */
    private NativeSearchQueryBuilder createSearchQueryBuilder(EsGoodsSearchDTO searchDTO, PageVO pageVo) {
        NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder();
        if (pageVo != null) {
            int pageNumber = pageVo.getPageNumber() - 1;
            if (pageNumber < 0) {
                pageNumber = 0;
            }
            Pageable pageable = PageRequest.of(pageNumber, pageVo.getPageSize());
            //??????
            nativeSearchQueryBuilder.withPageable(pageable);
        }
        //????????????????????????
        if (searchDTO != null) {
            //????????????
            BoolQueryBuilder filterBuilder = QueryBuilders.boolQuery();

            //???????????????????????????
            this.commonSearch(filterBuilder, searchDTO);

            //????????????
            this.recommended(filterBuilder, searchDTO);

            //???????????????????????????
            filterBuilder.must(QueryBuilders.matchQuery("marketEnable", GoodsStatusEnum.UPPER.name()));
            //?????????????????????????????????????????????
            filterBuilder.must(QueryBuilders.matchQuery("authFlag", GoodsAuthEnum.PASS.name()));


            //???????????????
            if (CharSequenceUtil.isEmpty(searchDTO.getKeyword())) {
                List<FunctionScoreQueryBuilder.FilterFunctionBuilder> filterFunctionBuilders = this.buildFunctionSearch();
                FunctionScoreQueryBuilder.FilterFunctionBuilder[] builders = new FunctionScoreQueryBuilder.FilterFunctionBuilder[filterFunctionBuilders.size()];
                filterFunctionBuilders.toArray(builders);
                FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders.functionScoreQuery(QueryBuilders.matchAllQuery(), builders)
                        .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                        .setMinScore(2);
                //??????????????????????????????????????????
                filterBuilder.must(functionScoreQueryBuilder);
            } else {
                this.keywordSearch(filterBuilder, searchDTO.getKeyword());
            }

            //?????????????????????

            nativeSearchQueryBuilder.withQuery(filterBuilder);


            if (pageVo != null && CharSequenceUtil.isNotEmpty(pageVo.getOrder()) && CharSequenceUtil.isNotEmpty(pageVo.getSort())) {
                nativeSearchQueryBuilder.withSort(SortBuilders.fieldSort(pageVo.getSort()).order(SortOrder.valueOf(pageVo.getOrder().toUpperCase())));
            } else {
                nativeSearchQueryBuilder.withSort(SortBuilders.scoreSort().order(SortOrder.DESC));
            }

        }
        return nativeSearchQueryBuilder;
    }

    /**
     * ????????????
     *
     * @param filterBuilder ????????????
     * @param searchDTO     ????????????
     */
    private void recommended(BoolQueryBuilder filterBuilder, EsGoodsSearchDTO searchDTO) {

        String currentGoodsId = searchDTO.getCurrentGoodsId();
        if (CharSequenceUtil.isEmpty(currentGoodsId)) {
            return;
        }

        //??????????????????
        filterBuilder.mustNot(QueryBuilders.matchQuery("id", currentGoodsId));

        //???????????????????????????????????????
        EsGoodsIndex esGoodsIndex = restTemplate.get(currentGoodsId, EsGoodsIndex.class);
        if (esGoodsIndex == null) {
            return;
        }
        //???????????????????????????????????????????????????????????????
        String categoryPath = esGoodsIndex.getCategoryPath();
        if (CharSequenceUtil.isNotEmpty(categoryPath)) {
            //??????????????????
            String substring = categoryPath.substring(0, categoryPath.lastIndexOf(","));
            filterBuilder.must(QueryBuilders.wildcardQuery("categoryPath", substring + "*"));
        }

    }

    /**
     * ??????????????????
     *
     * @param filterBuilder ???????????????
     * @param searchDTO     ????????????
     */
    private void commonSearch(BoolQueryBuilder filterBuilder, EsGoodsSearchDTO searchDTO) {
        //????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getBrandId())) {
            String[] brands = searchDTO.getBrandId().split("@");
            filterBuilder.must(QueryBuilders.termsQuery(ATTR_BRAND_ID, brands));
        }
        if (searchDTO.getRecommend() != null) {
            filterBuilder.filter(QueryBuilders.termQuery("recommend", searchDTO.getRecommend()));
        }
        //???????????????
        if (searchDTO.getNameIds() != null && !searchDTO.getNameIds().isEmpty()) {
            filterBuilder.must(QueryBuilders.nestedQuery(ATTR_PATH, QueryBuilders.termsQuery("attrList.nameId", searchDTO.getNameIds()), ScoreMode.None));
        }
        //????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getCategoryId())) {
            filterBuilder.must(QueryBuilders.wildcardQuery("categoryPath", "*" + searchDTO.getCategoryId() + "*"));
        }
        //??????????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getStoreCatId())) {
            filterBuilder.must(QueryBuilders.wildcardQuery("storeCategoryPath", "*" + searchDTO.getStoreCatId() + "*"));
        }
        //????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getStoreId())) {
            filterBuilder.filter(QueryBuilders.termQuery("storeId", searchDTO.getStoreId()));
        }
        //????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getProp())) {
            this.propSearch(filterBuilder, searchDTO);
        }
        // ??????????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getPromotionsId()) && CharSequenceUtil.isNotEmpty(searchDTO.getPromotionType())) {
            filterBuilder.must(QueryBuilders.wildcardQuery("promotionMapJson", "*" + searchDTO.getPromotionType() + "-" + searchDTO.getPromotionsId() + "*"));
        }
        //??????????????????
        if (CharSequenceUtil.isNotEmpty(searchDTO.getPrice())) {
            String[] prices = searchDTO.getPrice().split("_");
            if (prices.length == 0) {
                return;
            }
            double min = Convert.toDouble(prices[0], 0.0);
            double max = Integer.MAX_VALUE;

            if (prices.length == 2) {
                max = Convert.toDouble(prices[1], Double.MAX_VALUE);
            }
            if (min > max) {
                throw new ServiceException("??????????????????");
            }
            if (min > Double.MAX_VALUE) {
                min = Double.MAX_VALUE;
            }
            if (max > Double.MAX_VALUE) {
                max = Double.MAX_VALUE;
            }
            filterBuilder.must(QueryBuilders.rangeQuery("price").from(min).to(max).includeLower(true).includeUpper(true));
        }
    }

    /**
     * ????????????????????????
     *
     * @param filterBuilder ???????????????
     * @param searchDTO     ????????????
     */
    private void propSearch(BoolQueryBuilder filterBuilder, EsGoodsSearchDTO searchDTO) {
        String[] props = searchDTO.getProp().split("@");
        List<String> nameList = new ArrayList<>();
        List<String> valueList = new ArrayList<>();
        Map<String, List<String>> valueMap = new HashMap<>(16);
        for (String prop : props) {
            String[] propValues = prop.split("_");
            String name = propValues[0];
            String value = propValues[1];
            if (!nameList.contains(name)) {
                nameList.add(name);
            }
            if (!valueList.contains(value)) {
                valueList.add(value);
            }
            //???????????????????????????????????????
            if (!valueMap.containsKey(name)) {
                List<String> values = new ArrayList<>();
                values.add(value);
                valueMap.put(name, values);
            } else {
                valueMap.get(name).add(value);
            }
        }
        //?????????????????????
        for (Map.Entry<String, List<String>> entry : valueMap.entrySet()) {
            filterBuilder.must(QueryBuilders.nestedQuery(ATTR_PATH, QueryBuilders.matchQuery(ATTR_NAME, entry.getKey()), ScoreMode.None));
            BoolQueryBuilder shouldBuilder = QueryBuilders.boolQuery();
            for (String s : entry.getValue()) {
                shouldBuilder.should(QueryBuilders.nestedQuery(ATTR_PATH, QueryBuilders.matchQuery(ATTR_VALUE, s), ScoreMode.None));
            }
            filterBuilder.must(shouldBuilder);
        }
        searchDTO.getNotShowCol().put(ATTR_NAME_KEY, nameList);
        searchDTO.getNotShowCol().put(ATTR_VALUE_KEY, valueList);
    }

    /**
     * ?????????????????????
     *
     * @param filterBuilder ???????????????
     * @param keyword       ?????????
     */
    private void keywordSearch(BoolQueryBuilder filterBuilder, String keyword) {

        List<FunctionScoreQueryBuilder.FilterFunctionBuilder> filterFunctionBuilders = this.buildFunctionSearch();

        //????????????
        // operator ??? AND ??? ??????????????????????????? OR ??? ????????? minimumShouldMatch?????????????????????????????????????????????1
        MatchQueryBuilder goodsNameMatchQuery = QueryBuilders.matchQuery("goodsName", keyword).operator(Operator.OR).minimumShouldMatch(MINIMUM_SHOULD_MATCH);

        FunctionScoreQueryBuilder.FilterFunctionBuilder[] builders = new FunctionScoreQueryBuilder.FilterFunctionBuilder[filterFunctionBuilders.size()];
        filterFunctionBuilders.toArray(builders);
        FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders.functionScoreQuery(goodsNameMatchQuery, builders)
                .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                .setMinScore(2);
        //??????????????????????????????????????????
        filterBuilder.must(functionScoreQueryBuilder);
        filterBuilder.should(QueryBuilders.boolQuery().should(QueryBuilders.matchPhraseQuery("goodsName", keyword).boost(10)));
    }

    /**
     * ?????????????????????
     *
     * @return ?????????????????????
     */
    private List<FunctionScoreQueryBuilder.FilterFunctionBuilder> buildFunctionSearch() {
        List<FunctionScoreQueryBuilder.FilterFunctionBuilder> filterFunctionBuilders = new ArrayList<>();

//        GaussDecayFunctionBuilder skuNoScore = ScoreFunctionBuilders.gaussDecayFunction("skuSource", 100, 10).setWeight(2);
//        FunctionScoreQueryBuilder.FilterFunctionBuilder skuNoBuilder = new FunctionScoreQueryBuilder.FilterFunctionBuilder(skuNoScore);
//        filterFunctionBuilders.add(skuNoBuilder);
        FieldValueFactorFunctionBuilder skuNoScore = ScoreFunctionBuilders.fieldValueFactorFunction("skuSource").modifier(FieldValueFactorFunction.Modifier.LOG1P).setWeight(3);
        FunctionScoreQueryBuilder.FilterFunctionBuilder skuNoBuilder = new FunctionScoreQueryBuilder.FilterFunctionBuilder(skuNoScore);
        filterFunctionBuilders.add(skuNoBuilder);

        // ???????????????????????????????????????????????????
//        FieldValueFactorFunctionBuilder buyCountScore = ScoreFunctionBuilders.fieldValueFactorFunction("buyCount").modifier(FieldValueFactorFunction.Modifier.NONE).setWeight(10);
//        FunctionScoreQueryBuilder.FilterFunctionBuilder buyCountBuilder = new FunctionScoreQueryBuilder.FilterFunctionBuilder(buyCountScore);
//        filterFunctionBuilders.add(buyCountBuilder);
        return filterFunctionBuilders;
    }

}
