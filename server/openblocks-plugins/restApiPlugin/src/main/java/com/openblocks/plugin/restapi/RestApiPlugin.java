/**
 * Copyright 2021 Appsmith Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p>
 */

// adapted for rest api queries

package com.openblocks.plugin.restapi;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.openblocks.plugin.restapi.RestApiError.REST_API_EXECUTION_ERROR;
import static com.openblocks.sdk.exception.PluginCommonError.JSON_PARSE_ERROR;
import static com.openblocks.sdk.exception.PluginCommonError.QUERY_ARGUMENT_ERROR;
import static com.openblocks.sdk.exception.PluginCommonError.QUERY_EXECUTION_ERROR;
import static com.openblocks.sdk.exception.PluginCommonError.QUERY_EXECUTION_TIMEOUT;
import static com.openblocks.sdk.plugin.restapi.DataUtils.parseJsonBody;
import static com.openblocks.sdk.plugin.restapi.auth.RestApiAuthType.DIGEST_AUTH;
import static com.openblocks.sdk.plugin.restapi.auth.RestApiAuthType.OAUTH2_INHERIT_FROM_LOGIN;
import static com.openblocks.sdk.util.JsonUtils.readTree;
import static com.openblocks.sdk.util.JsonUtils.toJsonThrows;
import static com.openblocks.sdk.util.MustacheHelper.renderMustacheJsonString;
import static com.openblocks.sdk.util.MustacheHelper.renderMustacheString;
import static com.openblocks.sdk.util.StreamUtils.collectList;
import static com.openblocks.sdk.util.StreamUtils.distinctByKey;
import static java.util.Collections.emptySet;
import static org.apache.commons.collections4.MapUtils.emptyIfNull;
import static org.apache.commons.lang3.StringUtils.firstNonBlank;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.internal.Base64;
import org.pf4j.Extension;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.openblocks.plugin.restapi.constants.ResponseDataType;
import com.openblocks.plugin.restapi.helpers.AuthHelper;
import com.openblocks.plugin.restapi.helpers.BufferingFilter;
import com.openblocks.plugin.restapi.model.RestApiQueryConfig;
import com.openblocks.plugin.restapi.model.RestApiQueryExecutionContext;
import com.openblocks.sdk.exception.PluginException;
import com.openblocks.sdk.models.DatasourceTestResult;
import com.openblocks.sdk.models.Property;
import com.openblocks.sdk.models.QueryExecutionResult;
import com.openblocks.sdk.plugin.common.DatasourceQueryEngine;
import com.openblocks.sdk.plugin.common.QueryExecutionUtils;
import com.openblocks.sdk.plugin.restapi.DataUtils;
import com.openblocks.sdk.plugin.restapi.RestApiDatasourceConfig;
import com.openblocks.sdk.plugin.restapi.auth.AuthConfig;
import com.openblocks.sdk.plugin.restapi.auth.BasicAuthConfig;
import com.openblocks.sdk.plugin.restapi.auth.RestApiAuthType;
import com.openblocks.sdk.query.QueryVisitorContext;
import com.openblocks.sdk.webclient.WebClients;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class RestApiPlugin extends Plugin {

    private static final int MAX_REDIRECTS = 5;

    public RestApiPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @SuppressWarnings("DuplicatedCode")
    @Slf4j
    @Extension
    public static class RestApiEngine implements DatasourceQueryEngine<RestApiDatasourceConfig, Object, RestApiQueryExecutionContext> {

        private final Scheduler scheduler = QueryExecutionUtils.querySharedScheduler();
        private static final String RESPONSE_DATA_TYPE = "X-OPENBLOCKS-RESPONSE-DATA-TYPE";
        private static final Set<String> BINARY_DATA_TYPES = Set.of("application/zip",
                "application/octet-stream",
                "application/pdf",
                "application/pkcs8",
                "application/x-binary");

        Consumer<HttpHeaders> DEFAULT_HEADERS_CONSUMER = httpHeaders -> {};

        private final DataUtils dataUtils = DataUtils.getInstance();

        // Setting max content length. This would've been coming from `spring.codec.max-in-memory-size` property if the
        // `WebClient` instance was loaded as an auto-wired bean.
        private final ExchangeStrategies EXCHANGE_STRATEGIES;

        public RestApiEngine() {
            EXCHANGE_STRATEGIES = ExchangeStrategies
                    .builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
        }

        private Mono<Void> authByOauth2InheritFromLogin(RestApiQueryExecutionContext context) {
            if (context.getAuthConfig() == null || context.getAuthConfig().getType() != OAUTH2_INHERIT_FROM_LOGIN) {
                return Mono.empty();
            }
            return context.getAuthTokenMono()
                    .doOnNext(properties -> {
                        Map<String, List<Property>> propertyMap = properties.stream()
                                .collect(Collectors.groupingBy(Property::getType));

                        List<Property> params = propertyMap.get("param");
                        if (CollectionUtils.isNotEmpty(params)) {
                            Map<String, String> paramMap = new HashMap<>(emptyIfNull(context.getUrlParams()));
                            for (Property param : params) {
                                paramMap.put(param.getKey(), param.getValue());
                            }
                            context.setUrlParams(ImmutableMap.copyOf(paramMap));
                        }

                        List<Property> headers = propertyMap.get("header");
                        if (CollectionUtils.isNotEmpty(headers)) {
                            Map<String, String> headerMap = new HashMap<>(emptyIfNull(context.getHeaders()));
                            for (Property header : headers) {
                                headerMap.put(header.getKey(), header.getValue());
                            }
                            context.setHeaders(ImmutableMap.copyOf(headerMap));
                        }
                    })
                    .switchIfEmpty(Mono.error(new PluginException(REST_API_EXECUTION_ERROR, "REST_API_EXECUTION_ERROR",
                            "$ACCESS_TOKEN parameter missing.")))
                    .onErrorResume(throwable -> Mono.error(new PluginException(REST_API_EXECUTION_ERROR, "REST_API_EXECUTION_ERROR",
                            "get access token error: " + throwable.getMessage())))
                    .then();
        }

        @Override
        public Mono<QueryExecutionResult> executeQuery(Object webClientFilter, RestApiQueryExecutionContext context) {

            return Mono.defer(() -> authByOauth2InheritFromLogin(context))
                    .then(Mono.defer(() -> {
                        URI uri;
                        try {
                            uri = buildUri(context.getUrl(), context.getUrlParams(), context.isEncodeParams());
                        } catch (URISyntaxException e) {
                            return Mono.just(QueryExecutionResult.error(QUERY_ARGUMENT_ERROR, "QUERY_ARGUMENT_ERROR", e));
                        }

                        WebClient.Builder webClientBuilder = WebClients.builder();

                        Map<String, String> allHeaders = context.getHeaders();
                        String contentType = context.getContentType();
                        allHeaders.forEach(webClientBuilder::defaultHeader);

                        //basic auth
                        AuthConfig authConfig = context.getAuthConfig();
                        if (authConfig != null && authConfig.getType() == RestApiAuthType.BASIC_AUTH) {
                            webClientBuilder.defaultHeaders(AuthHelper.basicAuth((BasicAuthConfig) authConfig));
                        }

                        if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(contentType)) {
                            webClientBuilder.filter(new BufferingFilter());
                        }

                        webClientBuilder.defaultCookies(injectCookies(context));

                        WebClient client = webClientBuilder
                                .exchangeStrategies(EXCHANGE_STRATEGIES)
                                .build();

                        BodyInserter<?, ? super ClientHttpRequest> bodyInserter = buildBodyInserter(
                                context.getHttpMethod(),
                                context.isEncodeParams(),
                                contentType,
                                context.getQueryBody(),
                                context.getBodyParams());
                        return httpCall(client, context.getHttpMethod(), uri, bodyInserter, 0, authConfig, DEFAULT_HEADERS_CONSUMER)
                                .flatMap(clientResponse -> clientResponse.toEntity(byte[].class))
                                .map(this::convertToQueryExecutionResult)
                                .onErrorResume(error -> {
                                    if (error instanceof TimeoutException) {
                                        return Mono.just(QueryExecutionResult.error(QUERY_EXECUTION_TIMEOUT, "QUERY_TIMEOUT_ERROR", error));
                                    }
                                    if (error instanceof PluginException pluginException) {
                                        throw pluginException;
                                    }
                                    return Mono.just(
                                            QueryExecutionResult.error(REST_API_EXECUTION_ERROR, "REST_API_EXECUTION_ERROR", error));
                                });
                    }));
        }

        private Consumer<MultiValueMap<String, String>> injectCookies(RestApiQueryExecutionContext request) {
            return currentCookies -> {
                Set<String> forwardCookies = request.getForwardCookies();
                MultiValueMap<String, HttpCookie> requestCookies = request.getRequestCookies();
                if (requestCookies == null) {
                    return;
                }

                if (request.isForwardAllCookies()) {
                    requestCookies.forEach(
                            (cookieName, httpCookies) -> currentCookies.addAll(cookieName, collectList(httpCookies, HttpCookie::getValue)));
                    return;
                }

                requestCookies.entrySet()
                        .stream()
                        .filter(it -> forwardCookies.contains(it.getKey()))
                        .forEach(entry -> {
                            String cookieName = entry.getKey();
                            List<HttpCookie> httpCookies = entry.getValue();
                            currentCookies.addAll(cookieName, collectList(httpCookies, HttpCookie::getValue));
                        });
            };
        }

        private QueryExecutionResult convertToQueryExecutionResult(ResponseEntity<byte[]> stringResponseEntity) {
            HttpHeaders headers = stringResponseEntity.getHeaders();
            MediaType contentType = firstNonNull(headers.getContentType(), MediaType.TEXT_PLAIN); // text type if null
            byte[] body = stringResponseEntity.getBody();
            HttpStatus statusCode = stringResponseEntity.getStatusCode();
            JsonNode resultHeaders = parseExecuteResultHeaders(headers);

            if (body == null) {
                return QueryExecutionResult.ofRestApiResult(statusCode, resultHeaders, null);
            }

            ResponseBodyData responseBodyData = parseResponseDataInfo(body, contentType);
            ResponseDataType responseDataType = responseBodyData.getDataType();
            ObjectNode headersObjectNode = (ObjectNode) resultHeaders;
            headersObjectNode.putArray(RESPONSE_DATA_TYPE).add(String.valueOf(responseDataType));

            return QueryExecutionResult.ofRestApiResult(statusCode, headersObjectNode, responseBodyData.getBody());
        }

        @Getter
        @Builder
        private static class ResponseBodyData {
            private ResponseDataType dataType;
            private Object body;
        }

        private ResponseBodyData parseResponseDataInfo(byte[] body, MediaType contentType) {

            if (contentType.includes(MediaType.APPLICATION_JSON)) {
                try {
                    return ResponseBodyData.builder()
                            .body(readTree(body))
                            .dataType(ResponseDataType.JSON)
                            .build();

                } catch (IOException e) {
                    throw new PluginException(REST_API_EXECUTION_ERROR, "INVALID_JSON_FROM_RESPONSE");
                }
            }

            if (MediaType.IMAGE_GIF.equals(contentType) ||
                    MediaType.IMAGE_JPEG.equals(contentType) ||
                    MediaType.IMAGE_PNG.equals(contentType)) {
                return ResponseBodyData.builder()
                        .body(Base64.encode(body))
                        .dataType(ResponseDataType.IMAGE)
                        .build();
            }
            if (BINARY_DATA_TYPES.contains(contentType.toString())) {
                return ResponseBodyData.builder()
                        .body(Base64.encode(body))
                        .dataType(ResponseDataType.BINARY)
                        .build();
            }

            return ResponseBodyData.builder()
                    .body(new String(body, StandardCharsets.UTF_8).trim())
                    .dataType(ResponseDataType.TEXT)
                    .build();
        }

        private JsonNode parseExecuteResultHeaders(HttpHeaders headers) {
            // Convert the headers into json tree to store in the results
            String headerInJsonString;
            try {
                headerInJsonString = toJsonThrows(headers);
            } catch (JsonProcessingException e) {
                throw new PluginException(QUERY_EXECUTION_ERROR, "QUERY_EXECUTION_ERROR", e.getMessage());
            }

            // Set headers in the result now
            try {
                return (readTree(headerInJsonString));
            } catch (IOException e) {
                throw new PluginException(JSON_PARSE_ERROR, "JSON_PARSE_ERROR", headerInJsonString, e.getMessage());
            }
        }

        @Override
        public RestApiQueryExecutionContext buildQueryExecutionContext(RestApiDatasourceConfig datasourceConfig,
                Map<String, Object> queryConfigMap,
                Map<String, Object> requestParams, QueryVisitorContext queryVisitorContext) {

            RestApiQueryConfig queryConfig = RestApiQueryConfig.from(queryConfigMap);

            // from datasource config
            String urlDomain = datasourceConfig.getUrl();
            String datasourceBody = datasourceConfig.getBody();
            List<Property> datasourceHeaders = datasourceConfig.getHeaders();
            List<Property> datasourceUrlParams = datasourceConfig.getParams();
            List<Property> datasourceBodyFormData = datasourceConfig.getBodyFormData();
            Set<String> forwardCookies = datasourceConfig.getForwardCookies();
            boolean forwardAllCookies = datasourceConfig.isForwardAllCookies();

            // from query config
            HttpMethod httpMethod = queryConfig.getHttpMethod();
            boolean encodeParams = !queryConfig.isDisableEncodingParams();

            String queryBody = trimToEmpty(queryConfig.getBody());
            String queryPath = trimToEmpty(queryConfig.getPath());
            List<Property> queryParams = queryConfig.getParams();
            List<Property> queryHeaders = queryConfig.getHeaders();
            List<Property> queryBodyParams = queryConfig.getBodyFormData();

            String updatedQueryPath = renderMustacheString(queryPath, requestParams);

            List<Property> updatedQueryParams = renderMustacheValueInProperties(queryParams, requestParams);
            List<Property> updatedQueryHeaders = renderMustacheValueInProperties(queryHeaders, requestParams);
            List<Property> updatedQueryBodyParams = renderMustacheValueInProperties(queryBodyParams, requestParams);

            String normalizedUrl = buildUrl(urlDomain, updatedQueryPath, requestParams);

            Map<String, String> allHeaders = buildHeaders(datasourceHeaders, updatedQueryHeaders);
            String contentType = parseContentType(allHeaders).toLowerCase();
            if (!isValidContentType(contentType)) {
                throw new PluginException(QUERY_ARGUMENT_ERROR, "INVALID_CONTENT_TYPE", contentType);
            }

            String updatedQueryBody;
            if (isJsonContentType(contentType)) {
                updatedQueryBody = renderMustacheJsonString(queryBody, requestParams);
            } else {
                updatedQueryBody = renderMustacheString(queryBody, requestParams);
            }

            Map<String, String> urlParams = buildUrlParams(datasourceUrlParams, updatedQueryParams);
            List<Property> bodyParams = buildBodyParams(datasourceBodyFormData, updatedQueryBodyParams);

            return RestApiQueryExecutionContext.builder()
                    .httpMethod(httpMethod)
                    .url(normalizedUrl)
                    .headers(allHeaders)
                    .contentType(contentType)
                    .urlParams(urlParams)
                    .bodyParams(bodyParams)
                    .encodeParams(encodeParams)
                    .queryBody(firstNonBlank(updatedQueryBody, datasourceBody))
                    .forwardCookies(forwardCookies)
                    .forwardAllCookies(forwardAllCookies)
                    .requestCookies(queryVisitorContext.getCookies())
                    .authConfig(datasourceConfig.getAuthConfig())
                    .authTokenMono(queryVisitorContext.getAuthTokenMono())
                    .build();
        }

        private String parseContentType(Map<String, String> allHeaders) {
            return allHeaders.entrySet()
                    .stream()
                    .filter(it -> HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(it.getKey()))
                    .map(Entry::getValue)
                    .findFirst()
                    .orElse("");
        }

        private String buildUrl(String urlDomain, String updatedQueryPath, Map<String, Object> paramsMap) {
            String url = urlDomain.trim() + updatedQueryPath.trim();
            if (StringUtils.isEmpty(url)) {
                throw new PluginException(QUERY_ARGUMENT_ERROR, "REQUEST_URL_EMPTY");
            }

            url = renderMustacheString(url, paramsMap);

            try {
                return new URI(url).normalize().toString();
            } catch (URISyntaxException e) {
                throw new PluginException(QUERY_ARGUMENT_ERROR, "INVALID_REQUEST_URL", url);
            }
        }

        private List<Property> buildBodyParams(List<Property> datasourceBodyFormData, List<Property> updatedQueryBodyParams) {
            return Stream.concat(datasourceBodyFormData.stream(),
                            updatedQueryBodyParams.stream())
                    .filter(it -> it.getKey() != null)
                    .filter(distinctByKey(Property::getKey))
                    .toList();
        }

        private Map<String, String> buildUrlParams(List<Property> datasourceUrlParams, List<Property> updatedQueryParams) {
            return Stream.concat(datasourceUrlParams.stream(),
                            updatedQueryParams.stream())
                    .collect(Collectors.toUnmodifiableMap(Property::getKey,
                            Property::getValue,
                            (oldValue, newValue) -> newValue));
        }

        private Map<String, String> buildHeaders(List<Property> datasourceHeaders, List<Property> updatedQueryHeaders) {
            return Stream.concat(datasourceHeaders.stream(),
                            updatedQueryHeaders.stream())
                    .filter(it -> StringUtils.isNotBlank(it.getKey()) && StringUtils.isNotBlank(it.getValue()))
                    .collect(Collectors.toUnmodifiableMap(property -> property.getKey().trim().toLowerCase(),
                            Property::getValue,
                            (oldValue, newValue) -> newValue));
        }

        private BodyInserter<?, ? super ClientHttpRequest> buildBodyInserter(HttpMethod httpMethod,
                boolean isEncodeParams,
                String requestContentType,
                String queryBody,
                List<Property> bodyFormData) {

            if (HttpMethod.GET.equals(httpMethod)) {
                return BodyInserters.fromValue(new byte[0]);
            }

            if (isNoneContentType(requestContentType)) {
                return BodyInserters.fromValue(new byte[0]);
            }

            if (isJsonContentType(requestContentType)) {
                return BodyInserters.fromValue(parseJsonBody(queryBody));
            }

            if (MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(requestContentType)
                    || MediaType.MULTIPART_FORM_DATA_VALUE.equals(requestContentType)) {
                return dataUtils.buildBodyInserter(bodyFormData, requestContentType, isEncodeParams);
            }
            return BodyInserters.fromValue(queryBody);
        }

        @SuppressWarnings("deprecation")
        private boolean isJsonContentType(String requestContentType) {
            return MediaType.APPLICATION_JSON_VALUE.equals(requestContentType)
                    || MediaType.APPLICATION_JSON_UTF8_VALUE.equals(requestContentType)
                    || MediaType.APPLICATION_PROBLEM_JSON_VALUE.equals(requestContentType)
                    || MediaType.APPLICATION_PROBLEM_JSON_UTF8_VALUE.equals(requestContentType);
        }

        private boolean isNoneContentType(String requestContentType) {
            return StringUtils.isBlank(requestContentType);
        }

        private URI buildUri(String url, Map<String, String> urlParams, boolean encodeParams) throws URISyntaxException {
            String httpUrl = addHttpToUrlWhenPrefixNotPresent(url);
            httpUrl = httpUrl.replaceAll("(?<!http:|https:)/{2,}", "/"); // remove redundant "/"
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.newInstance();
            uriBuilder.uri(new URI(httpUrl));

            urlParams.forEach((key, value) -> {
                if (encodeParams) {
                    uriBuilder.queryParam(URLEncoder.encode(key, StandardCharsets.UTF_8),
                            URLEncoder.encode(value, StandardCharsets.UTF_8)
                    );
                } else {
                    uriBuilder.queryParam(key, value);
                }
            });
            return uriBuilder.build(true).toUri();
        }

        private static List<Property> renderMustacheValueInProperties(List<Property> properties, Map<String, Object> paramMap) {
            return properties.stream()
                    .map(it -> {
                        Property newProperty =
                                new Property(renderMustacheString(it.getKey(), paramMap), renderMustacheString(it.getValue(), paramMap));
                        newProperty.setType(it.getType());
                        return newProperty;
                    })
                    .toList();
        }

        private boolean isValidContentType(String requestContentType) {
            if (StringUtils.isEmpty(requestContentType)) {
                return true;
            }

            try {
                MediaType.valueOf(requestContentType);
            } catch (InvalidMediaTypeException e) {
                return false;
            }

            return true;
        }

        private Mono<ClientResponse> httpCall(WebClient webClient, HttpMethod httpMethod,
                URI uri,
                BodyInserter<?, ? super ClientHttpRequest> requestBody,
                int iteration,
                @Nullable AuthConfig authConfig,
                Consumer<HttpHeaders> headersConsumer) {
            if (iteration == MAX_REDIRECTS) {
                return Mono.error(new PluginException(QUERY_EXECUTION_ERROR, "REACH_REDIRECT_LIMIT", MAX_REDIRECTS));
            }

            return webClient
                    .method(httpMethod)
                    .uri(uri)
                    .headers(headersConsumer)
                    .body(requestBody)
                    .exchange()
                    .onErrorMap(e -> new PluginException(QUERY_EXECUTION_ERROR, "QUERY_EXECUTION_ERROR", e.getMessage()))
                    .flatMap(response -> {
                        if (response.statusCode().is3xxRedirection()) {
                            String redirectUrl = response.headers().header("Location").get(0);
                            /*
                              TODO
                              In case the redirected URL is not absolute (complete), create the new URL using the relative path
                              This particular scenario is seen in the URL : https://rickandmortyapi.com/api/character
                              It redirects to partial URI : /api/character/
                              In this scenario we should convert the partial URI to complete URI
                             */
                            URI redirectUri;
                            try {
                                redirectUri = new URI(redirectUrl);
                            } catch (URISyntaxException e) {
                                return Mono.error(new PluginException(QUERY_EXECUTION_ERROR, "QUERY_EXECUTION_ERROR",
                                        e.getMessage()));
                            }
                            return httpCall(webClient, httpMethod, redirectUri, requestBody, iteration + 1, authConfig, headersConsumer);
                        }
                        //digest auth
                        if (authConfig != null && authConfig.getType() == DIGEST_AUTH && AuthHelper.shouldDigestAuth(response)) {
                            try {
                                return httpCall(webClient, httpMethod, uri, requestBody, iteration + 1, authConfig,
                                        headersConsumer.andThen(
                                                AuthHelper.digestAuth((BasicAuthConfig) authConfig, response, httpMethod, uri.getPath())));
                            } catch (ParseException e) {
                                return Mono.error(new PluginException(QUERY_EXECUTION_ERROR, "QUERY_EXECUTION_ERROR",
                                        e.getMessage()));
                            }
                        }
                        return Mono.just(response);
                    })
                    .subscribeOn(scheduler);
        }

        @Override
        public Mono<Object> createConnection(RestApiDatasourceConfig connectionConfig) {
            return Mono.just(new Object());
        }

        @Override
        public Set<String> validateConfig(RestApiDatasourceConfig connectionConfig) {
            return emptySet();
        }

        private String addHttpToUrlWhenPrefixNotPresent(String url) {
            if (url == null || url.toLowerCase().startsWith("http") || url.contains("://")) {
                return url;
            }
            return "http://" + url;
        }

        @Override
        public Mono<DatasourceTestResult> testConnection(RestApiDatasourceConfig connectionConfig) {
            return Mono.just(DatasourceTestResult.testSuccess());
        }

        @Override
        public Mono<Void> destroyConnection(Object connection) {
            return Mono.empty();
        }

        @Nonnull
        @Override
        public RestApiDatasourceConfig resolveConfig(Map<String, Object> configMap) {
            return RestApiDatasourceConfig.buildFrom(configMap);
        }
    }
}