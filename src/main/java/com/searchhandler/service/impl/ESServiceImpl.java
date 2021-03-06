package com.searchhandler.service.impl;

import com.searchhandler.common.LocalConfig;
import com.searchhandler.common.constants.BusinessConstants.ESConfig;
import com.searchhandler.common.constants.BusinessConstants.ResultConfig;
import com.searchhandler.common.constants.BusinessConstants.SysConfig;
import com.searchhandler.common.constants.ResultEnum;
import com.searchhandler.common.utils.DataUtils;
import com.searchhandler.exception.SearchHandlerException;
import com.searchhandler.service.ESService;
import lombok.Data;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.SingleBucketAggregation;
import org.elasticsearch.search.aggregations.metrics.ParsedSingleValueNumericMetricsAggregation;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@Slf4j
@Service
@SuppressWarnings({ "unchecked" })
public class ESServiceImpl implements ESService {

    private String ES_TYPE;
    private String ES_INDEX;
    private String ES_ADDRESSES;
    private Integer ES_HTTP_PORT;

    private int ES_BULK_SIZE;
    private int ES_BULK_FLUSH;
    private int ES_SOCKET_TIMEOUT;
    private int ES_CONNECT_TIMEOUT;
    private int ES_BULK_CONCURRENT;
    private int ES_MAX_RETRY_TINEOUT_MILLIS;
    private int ES_CONNECTION_REQUEST_TIMEOUT;

    private RequestOptions COMMON_OPTIONS = RequestOptions.DEFAULT.toBuilder().build();
    private static RestClient restClient;
    private static RestHighLevelClient restHighLevelClient;
    private static BulkProcessor bulkProcessor;
    private List<String> esHttpAddress = new ArrayList<>();
    private AtomicInteger bulkCount = new AtomicInteger(0);

    @Override
    public Map simpleSearch(Map param) throws SearchHandlerException {

        SearchSourceBuilder sourceBuilder = simpleSearchBuilder(param);

        return fullSearch(param, sourceBuilder);
    }

    @Override
    public Map doAnalyze(Map param) {

        Map result = getBaseResult();

        List textList = DataUtils.getNotNullValue(param, ESConfig.QUERY_KEY, List.class, new ArrayList<>());
        if (textList.isEmpty()) {
            log.error("No trgt text found");
            return result;
        }

        AnalyzeRequest request = new AnalyzeRequest();

        // text(s)
        textList.parallelStream().forEach(x -> request.text(String.valueOf(x).trim()));

        // analyzer
        request.analyzer(DataUtils.getNotNullValue(param, ESConfig.ANALYZER_KEY, String.class, ESConfig.DEFAULT_ANALYZER));

        // set index if exists
        request.index((String) param.get(ESConfig.INDEX_KEY));

        // set field if exists
        request.field((String) param.get(ESConfig.FIELD_KEY));

        // char filters
        List charFilters = DataUtils.getNotNullValue(param, ESConfig.CHAR_FILTER_KEY, List.class, new ArrayList<>());
        charFilters.parallelStream().forEach(x -> {
            if (x instanceof String)
                request.addCharFilter((String) x);
            if (x instanceof Map)
                request.addCharFilter((Map) x);
        });

        // token filter
        List tokenFilters = DataUtils.getNotNullValue(param, ESConfig.TOKEN_FILTER_KEY, List.class, new ArrayList<>());
        tokenFilters.parallelStream().forEach(x -> {
            if (x instanceof String)
                request.addTokenFilter((String) x);
            if (x instanceof Map)
                request.addTokenFilter((Map) x);
        });

        // tokenizer
        Object tokenizer = DataUtils.getNotNullValue(param, ESConfig.TOKENIZER_KEY, Object.class, new Object());
        if (tokenizer instanceof String)
            request.tokenizer((String) tokenizer);
        if (tokenizer instanceof Map)
            request.tokenizer((Map) tokenizer);

        // normalizer
        request.normalizer((String) param.get(ESConfig.NORMALIZER_KEY));

        try {
            AnalyzeResponse response = restHighLevelClient.indices().analyze(request, COMMON_OPTIONS);
            List terms = Optional.ofNullable(response.getTokens()).orElse(new ArrayList<>())
                    .stream().map(AnalyzeResponse.AnalyzeToken::getTerm).collect(Collectors.toList());
            result.put(ResultConfig.DATA_KEY, terms);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error happened when we do analyze for:[{}], {}", textList, e);
        }

        return result;
    }

    @Override
    public Map complexSearch(Map param) throws SearchHandlerException {
        SearchSourceBuilder sourceBuilder = makeBaseSearchBuilder(param);

        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        // build query conditions
        Map query = DataUtils.getNotNullValue(param, ESConfig.QUERY_KEY, Map.class, new HashMap<>());
        if (!query.isEmpty()) {
            boolQueryBuilder.must(buildBoolQuery(query));
        }

        // build filter conditions
        Map filter = DataUtils.getNotNullValue(param, ESConfig.FILTER_KEY, Map.class, new HashMap<>());
        if (!filter.isEmpty()) {
            boolQueryBuilder.filter(buildBoolQuery(filter));
        }

        // set query & filter conditions
        sourceBuilder.query(boolQueryBuilder);

        // build aggregation
        Map aggregationInfo = DataUtils.getNotNullValue(param, ESConfig.AGGREGATION_KEY, Map.class, new HashMap<>());
        if (!aggregationInfo.isEmpty()) {
            aggregationInfo.forEach((k, v) -> sourceBuilder.aggregation(buildCommonAgg((Map) v)));
        }

        // if highlight exists
        List<String> highlightList = DataUtils.getNotNullValue(param, ESConfig.HIGHLIGHT_KEY, List.class, new ArrayList<>());
        if (!highlightList.isEmpty()) {
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightList.parallelStream().forEach(highlightBuilder::field);
            sourceBuilder.highlighter(highlightBuilder);
        }

        return fullSearch(param, sourceBuilder);
    }

    private AggregationBuilder buildCommonAgg(Map param) {
        String type = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_TYPE_KEY, String.class, "");
        String aggrName = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_NAME_KEY, String.class, "");
        String field = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_FIELD_KEY, String.class, "");
        if (ESConfig.SIMPLE_AGGREGATION_LIST.contains(type)) {
            switch (type) {
                case ESConfig.COUNT_KEY:
                    return AggregationBuilders.count(aggrName).field(field);
                case ESConfig.SUM_KEY:
                    return AggregationBuilders.sum(aggrName).field(field);
                case ESConfig.MAX_KEY:
                    return AggregationBuilders.max(aggrName).field(field);
                case ESConfig.MIN_KEY:
                    return AggregationBuilders.min(aggrName).field(field);
                case ESConfig.AVG_KEY:
                    return AggregationBuilders.avg(aggrName).field(field);
                case ESConfig.TERMS_KEY:
                    return AggregationBuilders.terms(aggrName).field(field);
            }
        } else if (ESConfig.NESTED_KEY.equalsIgnoreCase(type)) {
            List subAgg = DataUtils.getNotNullValue(param, ESConfig.SUB_AGG_KEY, List.class, new ArrayList<>());
            String path = DataUtils.getNotNullValue(param, ESConfig.PATH_KEY, String.class, "");
            AggregationBuilder aggregationBuilder = AggregationBuilders.nested(aggrName, path);
            subAgg.parallelStream().forEach(x -> aggregationBuilder.subAggregation(buildCommonAgg((Map) x)));
            return aggregationBuilder;
        }

        String from = DataUtils.getNotNullValue(param, ESConfig.FROM_KEY, String.class, "");
        String to = DataUtils.getNotNullValue(param, ESConfig.TO_KEY, String.class, "");
        return AggregationBuilders.dateRange(field).addRange(from, to);
    }

    private BoolQueryBuilder buildBoolQuery(Map param) {

        BoolQueryBuilder query = QueryBuilders.boolQuery();
        param.keySet().stream().filter(ESConfig.BOOL_CONDITION_LIST::contains).forEach(x -> {
            String key = (String) x;
            List trgt = DataUtils.getNotNullValue(param, key, List.class, new ArrayList<>());
            switch (key) {
                case ESConfig.MUST_KEY:
                    trgt.parallelStream().forEach(y -> query.must(buildCommonQuery().apply((Map) y)));
                    break;
                case ESConfig.SHOULD_KEY:
                    trgt.parallelStream().forEach(y -> query.should(buildCommonQuery().apply((Map) y)));
                    break;
                case ESConfig.MUST_NOT_KEY:
                    trgt.parallelStream().forEach(y -> query.mustNot(buildCommonQuery().apply((Map) y)));
                    break;
            }
        });
        return query;
    }

    private Function<Map, QueryBuilder> buildCommonQuery() {

        return (param) -> {
            String type = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_TYPE_KEY, String.class, "");
            if (ESConfig.SIMPLE_CONDITION_LIST.contains(type)) {
                return buildSimpleQuery(param);
            } else if (ESConfig.RANGE_KEY.equalsIgnoreCase(type)) {
                return buildRangeQuery(param);
            } else if (ESConfig.MULTIMATCH_KEY.equalsIgnoreCase(type)) {
                return buildMultiMatchQuery(param);
            } else if (ESConfig.NESTED_KEY.equalsIgnoreCase(type)) {
                return buildNestedQuery(param);
            }
            return QueryBuilders.matchAllQuery();
        };
    }

    private QueryBuilder buildNestedQuery(Map param) {
        String path = DataUtils.getNotNullValue(param, ESConfig.PATH_KEY, String.class, "");
        Map query = DataUtils.getNotNullValue(param, ESConfig.QUERY_KEY, Map.class, new HashMap<>());
        return QueryBuilders.nestedQuery(path, buildCommonQuery().apply(query), ScoreMode.Avg);
    }

    private QueryBuilder buildMultiMatchQuery(Map param) {
        Object value = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_VALUE_KEY, Object.class, new Object());
        Object fieldNames = DataUtils.getNotNullValue(param, ESConfig.FIELDNAMES_KEY, Object.class, new Object());
        Collection fieldNamesCollection = (fieldNames instanceof Collection) ? (Collection) fieldNames : Collections.singletonList(fieldNames);
        MultiMatchQueryBuilder multiMatchQueryBuilder = QueryBuilders.multiMatchQuery(value);
        fieldNamesCollection.parallelStream().forEach(x -> {
            String nameStr = (String) x;
            if (0 > nameStr.indexOf('^')) {
                multiMatchQueryBuilder.field(nameStr);
            } else {
                String[] arr = nameStr.split("\\^");
                multiMatchQueryBuilder.field(arr[0], Float.valueOf(arr[1]));
            }
        });
        return multiMatchQueryBuilder;
    }

    private QueryBuilder buildRangeQuery(Map param) {
        Object field = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_FIELD_KEY, Object.class, new Object());
        RangeQueryBuilder queryBuilder = QueryBuilders.rangeQuery(String.valueOf(field).trim());
        param.keySet().parallelStream().forEach(x -> {
            String key = (String) x;
            Object value = DataUtils.getNotNullValue(param, key, Object.class, new Object());
            switch (key) {
                case ESConfig.INCLUDE_LOWER_KEY:
                    queryBuilder.includeLower((Boolean) value);
                    break;
                case ESConfig.INCLUDE_UPPER_KEY:
                    queryBuilder.includeUpper((Boolean) value);
                    break;
                case ESConfig.FROM_KEY:
                    queryBuilder.from(value);
                    break;
                case ESConfig.LTE_KEY:
                    queryBuilder.lte(value);
                    break;
                case ESConfig.GTE_KEY:
                    queryBuilder.gte(value);
                    break;
                case ESConfig.LT_KEY:
                    queryBuilder.lt(value);
                    break;
                case ESConfig.GT_KEY:
                    queryBuilder.gt(value);
                    break;
                case ESConfig.TO_KEY:
                    queryBuilder.to(value);
                    break;
                default:
                    break;
            }
        });
        return queryBuilder;
    }

    private QueryBuilder buildSimpleQuery(Map param) {

        String type = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_TYPE_KEY, String.class, "");
        Object field = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_FIELD_KEY, Object.class, new Object());
        Object value = DataUtils.getNotNullValue(param, ESConfig.SIMPLE_QUERY_VALUE_KEY, Object.class, new Object());

        switch (type) {
            case ESConfig.MATCH_KEY:
                return QueryBuilders.matchQuery(String.valueOf(field).trim(), value);
            case ESConfig.TERM_KEY:
                return QueryBuilders.termQuery(String.valueOf(field).trim(), value);
            case ESConfig.FUZZY_KEY:
                return QueryBuilders.fuzzyQuery(String.valueOf(field).trim(), value);
            case ESConfig.PREFIX_KEY:
                return QueryBuilders.prefixQuery(String.valueOf(field).trim(), String.valueOf(value).trim());
            case ESConfig.REGEXP_KEY:
                return QueryBuilders.regexpQuery(String.valueOf(field).trim(), String.valueOf(value).trim());
            case ESConfig.WRAPPER_KEY:
                return QueryBuilders.wrapperQuery(String.valueOf(value).trim());
            case ESConfig.WILDCARD_KEY:
                return QueryBuilders.wildcardQuery(String.valueOf(field).trim(), String.valueOf(value).trim());
            case ESConfig.COMMONTERMS_KEY:
                return QueryBuilders.commonTermsQuery(String.valueOf(field).trim(), value);
            case ESConfig.QUERY_STRING_KEY:
                return QueryBuilders.queryStringQuery(String.valueOf(value).trim());
            case ESConfig.MATCH_PHRASE_KEY:
                return QueryBuilders.matchPhraseQuery(String.valueOf(field).trim(), value);
            case ESConfig.MATCH_PHRASE_PREFIX_KEY:
                return QueryBuilders.matchPhrasePrefixQuery(String.valueOf(field).trim(), value);
            default:
                return QueryBuilders.termsQuery(String.valueOf(field).trim(), (Collection<?>) value);
        }
    }

    private SearchSourceBuilder simpleSearchBuilder(Map param) {
        SearchSourceBuilder sourceBuilder = makeBaseSearchBuilder(param);
        Object queryItem = DataUtils.getNotNullValue(param, ESConfig.QUERY_KEY, Object.class, "");
        List<String> fieldList = DataUtils.getNotNullValue(param, ESConfig.FIELDNAMES_KEY, List.class, new ArrayList<>());
        String[] fieldArr = fieldList.parallelStream().toArray(String[]::new);
        MultiMatchQueryBuilder multiMatchQueryBuilder = QueryBuilders.multiMatchQuery(queryItem, fieldArr);
        sourceBuilder.query(multiMatchQueryBuilder);

        HighlightBuilder highlightBuilder = new HighlightBuilder();
        DataUtils.getNotNullValue(param, ESConfig.HIGHLIGHT_KEY, List.class, new ArrayList<>()).parallelStream()
                .forEach(x -> highlightBuilder.field((String) x));
        sourceBuilder.highlighter(highlightBuilder);
        return sourceBuilder;
    }

    private SearchSourceBuilder makeBaseSearchBuilder(Map param) {
        Integer from = DataUtils.getNotNullValue(param, "from", Integer.class, ESConfig.DEFAULT_ES_FROM);
        Integer size = DataUtils.getNotNullValue(param, "size", Integer.class, ESConfig.DEFAULT_ES_SIZE);
        if (from + size > ESConfig.DEFAULT_ES_MAX_SIZE) {
            log.error("Over size limit, please try scroll");
            size = ESConfig.DEFAULT_ES_MAX_SIZE - from;
        }

        return new SearchSourceBuilder().from(from).size(size);
    }

    private Map fullSearch(Map param, SearchSourceBuilder sourceBuilder) throws SearchHandlerException {
        String trgtIndex = DataUtils.getNotNullValue(param, "index", String.class, ES_INDEX);
        String trgtType = DataUtils.getNotNullValue(param, "type", String.class, ES_TYPE);
        if (StringUtils.isBlank(trgtIndex) || StringUtils.isBlank(trgtType)) {
            log.error("Can't find index:[{}] or type:[{}] info", trgtIndex, trgtType);
            return new HashMap();
        }

        Long startTime = System.currentTimeMillis();
        SearchRequest searchRequest = new SearchRequest().indices(trgtIndex).types(trgtType).source(sourceBuilder);
        try {
            log.info("Try to query at:[{}] with request:[{}]", startTime, searchRequest.source().toString());
            SearchResponse response = restHighLevelClient.search(searchRequest, COMMON_OPTIONS);
            log.debug("Got response as:[{}]", response);
            Long endTime = System.currentTimeMillis();
            log.info("Finish query at:[{}] which took:[{}]", endTime, (endTime - startTime) / 1000);
            return buildResult(response);
        } catch (Exception e) {
            e.printStackTrace();
            String errMsg = "Error happened when we try to query ES as:" + searchRequest;
            log.error(errMsg);
            throw new SearchHandlerException(ResultEnum.ES_QUERY);
        }
    }

    private Map<String, Object> buildResult(SearchResponse response) {
        SearchHits hits = response.getHits();
        long total = hits.totalHits;
        log.info("Got {} data in total", total);
        Map result = getBaseResult();
        result.put(ResultConfig.TOTAL_KEY, hits.totalHits);

        List dataList = Stream.of(hits.getHits()).map(x -> {
            Map<String, Object> sourceAsMap = x.getSourceAsMap();
            sourceAsMap.put(ESConfig.SCORE_KEY, x.getScore());
            Map<String, HighlightField> highlightFields = x.getHighlightFields();
            if (!highlightFields.isEmpty()) {
                Map highlight = new HashMap();
                highlightFields.forEach((k, v) -> highlight.put(k, v.fragments()[0].string()));
                sourceAsMap.put(ResultConfig.HIGHLIGH_KEY, highlight);
            }
            return sourceAsMap;
        }).collect(Collectors.toList());

        result.put(ResultConfig.DATA_KEY, dataList);
        log.info("Build as {} data", dataList.size());

        Map<String, String> aggMap = new HashMap<>();
        List<Aggregation> aggregations = Optional.ofNullable(response.getAggregations()).orElse(new Aggregations(Collections.emptyList())).asList();
        aggregations.forEach(aggregation -> getAggrInfo(aggMap, "", aggregation));
        log.info("Build as {} aggregation data", aggMap.size());
        result.put(ResultConfig.AGGREGATION_KEY, aggMap);
        return result;
    }

    private void getAggrInfo(Map<String, String> aggMap, String parentName, Aggregation aggregation) {

        String key = (StringUtils.isNotBlank(parentName)) ? parentName + "." + aggregation.getName() : aggregation.getName();
        if ("nested".equalsIgnoreCase(aggregation.getType())) {
            SingleBucketAggregation sbA = (SingleBucketAggregation) aggregation;
            aggMap.put(key + ".count", Long.toString(sbA.getDocCount()));
            List<Aggregation> aggregations = Optional.ofNullable(sbA.getAggregations()).orElse(new Aggregations(Collections.emptyList())).asList();
            aggregations.forEach(subAggregation -> getAggrInfo(aggMap, key, subAggregation));
        } else {
            ParsedSingleValueNumericMetricsAggregation psA = (ParsedSingleValueNumericMetricsAggregation) aggregation;
            aggMap.put(key + ".value", psA.getValueAsString());
        }
    }

    @Override
    public Integer bulkInsert(String idKey, List dataList) {

        return bulkInsert(ES_INDEX, ES_TYPE, idKey, dataList);
    }

    @Override
    public Integer bulkInsert(String index, String type, String idKey, List dataList) {

        String trgtIndex = (StringUtils.isBlank(index)) ? ES_INDEX : index;
        String trgtType = (StringUtils.isBlank(type)) ? ES_TYPE : type;
        if (StringUtils.isBlank(trgtIndex) || StringUtils.isBlank(trgtType) || CollectionUtils.isEmpty(dataList)) {
            log.error("Important info missing for index:[{}] type:[{}] and data:[{}]", trgtIndex, trgtType, dataList);
            return 0;
        }

        log.info("Try to bulk insert into index:[{}] type:[{}]", index, type);
        dataList.parallelStream().forEach(x -> {
                    Map data = (Map) x;
                    String pk = DataUtils.getNotNullValue(data, idKey, String.class, "");
                    IndexRequest indexRequest = StringUtils.isNotBlank(pk) ? new IndexRequest(trgtIndex, trgtType, pk) : new IndexRequest(trgtIndex, trgtType);
                    bulkCommit(indexRequest.source(data));
                });

        int size = dataList.size();
        log.info("Bulk inserted {} data", size);
        return size;
    }

    private void bulkCommit(DocWriteRequest request) {

        /*if (bulkCount.incrementAndGet() >= ES_BULK_SIZE) {
            synchronized (ESServiceImpl.class) {
                if (bulkCount.get() >= ES_BULK_SIZE) {
                    log.info("Reach the bulk gap:[{}] refresh the client", ES_BULK_SIZE);
                    // reset es client
                    // and reset counter
                    closeESClient();
                    initESClient();
                    bulkCount.set(0);
                    log.info("Client refresh done");
                }
            }
        }*/

        bulkProcessor.add(request);
    }

    private Map getBaseResult() {
        return new HashMap() {{
            put(ResultConfig.TOTAL_KEY, 0);
            put(ResultConfig.DATA_KEY, new ArrayList<>());
            put(ResultConfig.AGGREGATION_KEY, new HashMap<>());
        }};
    }

    @Override
    public String getESHttpAddr(Boolean isRandom) {

        int index = (isRandom) ? new Random().nextInt(esHttpAddress.size()) : 0;
        return esHttpAddress.get(index);
    }

    @Override
    public RestClient getESClient() throws SearchHandlerException {
        if (null == restClient) {
            synchronized (ESServiceImpl.class) {
                if (null == restClient) {
                    initESClient();
                }
            }
        }
        return restClient;
    }

    @Override
    public RestHighLevelClient getESHighLevelClient() throws SearchHandlerException {

        if (null == restHighLevelClient) {
            synchronized (ESServiceImpl.class) {
                if (null == restHighLevelClient) {
                    initESClient();
                }
            }
        }
        return restHighLevelClient;
    }

    @Override
    @Synchronized
    @PostConstruct
    public void initESClient() throws SearchHandlerException {
        log.info("Init ES client");
        closeESClient();
        initStaticVariables();

        try {
            HttpHost[] httpHosts = Arrays.stream(ES_ADDRESSES.split(",")).parallel().map(x -> {
                esHttpAddress.add(x + ES_HTTP_PORT);
                return new HttpHost(x, ES_HTTP_PORT, "http");
            }).collect(Collectors.toList()).parallelStream().toArray(HttpHost[]::new);

            RestClientBuilder builder = RestClient.builder(httpHosts)
                    .setRequestConfigCallback((RequestConfig.Builder requestConfigBuilder) ->
                            requestConfigBuilder.setConnectTimeout(ES_CONNECT_TIMEOUT)
                                    .setSocketTimeout(ES_SOCKET_TIMEOUT)
                                    .setConnectionRequestTimeout(ES_CONNECTION_REQUEST_TIMEOUT))
                                    .setMaxRetryTimeoutMillis(ES_MAX_RETRY_TINEOUT_MILLIS);

            restClient = builder.build();
            restHighLevelClient = new RestHighLevelClient(builder);

            bulkProcessor = BulkProcessor.builder((request, bulkListener) ->
                    restHighLevelClient.bulkAsync(request, COMMON_OPTIONS, bulkListener),
                    getBPListener())
                    .setBulkActions(ES_BULK_FLUSH)
                    .setBulkSize(new ByteSizeValue(ES_BULK_SIZE, ByteSizeUnit.MB))
                    .setFlushInterval(TimeValue.timeValueSeconds(10L))
                    .setConcurrentRequests(ES_BULK_CONCURRENT)
                    .setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(1L), 3))
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            String errMsg = "Error happened when we init ES transport client" + e;
            log.error(errMsg);
            throw new SearchHandlerException(ResultEnum.ES_CLIENT_INIT);
        }
    }

    private BulkProcessor.Listener getBPListener() {
        return new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                log.info("Start to handle bulk commit executionId:[{}] for {} requests", executionId, request.numberOfActions());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                log.info("Finished handling bulk commit executionId:[{}]", executionId);

                if (response.hasFailures()) {
                    List<String> errMsg = new ArrayList<>();
                    response.spliterator().forEachRemaining(x -> {
                        if (x.isFailed()) {
                            errMsg.add(String.format("\tid:[%s], item:[%s]: %s", x.getId(), x.getItemId(), x.getFailureMessage()));
                        }
                    });
                    log.error("Bulk executionId:[{}] has error messages:\n", executionId, String.join("\n", errMsg));
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                failure.printStackTrace();
                log.error("Bulk finished with error:[{}]", failure.getMessage());
                request.requests().parallelStream().filter(x -> x instanceof IndexRequest)
                        .forEach(x -> {
                            Map source = ((IndexRequest) x).sourceAsMap();
                            String pk = DataUtils.getNotNullValue(source, "id", String.class, "");
                            log.error("Failure to handle index:[{}], type:[{}] id:[{}]", x.index(), x.type(), pk);
                        });
            }
        };
    }

    private void initStaticVariables() {
        esHttpAddress = new ArrayList<>();
        ES_TYPE = LocalConfig.get(SysConfig.ES_TYPE_KEY, String.class, ESConfig.DEFAULT_ES_TYPE);
        ES_INDEX = LocalConfig.get(SysConfig.ES_INDEX_KEY, String.class, ESConfig.DEFAULT_ES_INDEX);
        ES_HTTP_PORT = LocalConfig.get(SysConfig.ES_HTTP_PORT_KEY, Integer.class, ESConfig.DEFAULT_ES_HTTP_PORT);
        ES_ADDRESSES = LocalConfig.get(SysConfig.ES_ADDRESSES_KEY, String.class, ESConfig.DEFAULT_ES_ADDRESSES);
        ES_BULK_SIZE = LocalConfig.get(SysConfig.ES_BULK_SIZE_KEY, Integer.class, ESConfig.DEFAULT_ES_BULK_SIZE);
        ES_BULK_FLUSH = LocalConfig.get(SysConfig.ES_BULK_FLUSH_KEY, Integer.class, ESConfig.DEFAULT_ES_BULK_FLUSH);
        ES_SOCKET_TIMEOUT = LocalConfig.get(SysConfig.ES_SOCKET_TIMEOUT_KEY, Integer.class, ESConfig.DEFAULT_ES_SOCKET_TIMEOUT);
        ES_BULK_CONCURRENT = LocalConfig.get(SysConfig.ES_BULK_CONCURRENT_KEY, Integer.class, ESConfig.DEFAULT_ES_BULK_CONCURRENT);
        ES_CONNECT_TIMEOUT = LocalConfig.get(SysConfig.ES_CONNECT_TIMEOUT_KEY, Integer.class, ESConfig.DEFAULT_ES_CONNECT_TIMEOUT);
        ES_MAX_RETRY_TINEOUT_MILLIS = LocalConfig.get(SysConfig.ES_MAX_RETRY_TINEOUT_MILLIS_KEY, Integer.class, ESConfig.DEFAULT_ES_MAX_RETRY_TINEOUT_MILLIS);
        ES_CONNECTION_REQUEST_TIMEOUT = LocalConfig.get(SysConfig.ES_CONNECTION_REQUEST_TIMEOUT_KEY, Integer.class, ESConfig.DEFAULT_ES_CONNECTION_REQUEST_TIMEOUT);
    }

    private void closeESClient() throws SearchHandlerException {
        try {
            if (null != bulkProcessor) {
                boolean terminated = bulkProcessor.awaitClose(30L, TimeUnit.SECONDS);
                if (terminated) {
                    if (null != restClient) {
                        restClient.close();
                    }

                    if (null != restHighLevelClient) {
                        restHighLevelClient.close();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            String errMsg = "Error happened when we try to close ES client" + e;
            log.error(errMsg);
            throw new SearchHandlerException(ResultEnum.ES_CLIENT_CLOSE);
        }
    }
}
